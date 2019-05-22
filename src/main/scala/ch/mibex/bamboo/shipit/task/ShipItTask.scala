package ch.mibex.bamboo.shipit.task

import java.io.{File, FileInputStream}

import ch.mibex.bamboo.shipit.Constants.BambooVariables.BambooDataCenterBuildNrVariableKey
import ch.mibex.bamboo.shipit.mpac.MpacError.MpacUploadError
import ch.mibex.bamboo.shipit.mpac.{MpacCredentials, MpacError, MpacFacade, NewPluginVersionDetails}
import ch.mibex.bamboo.shipit.settings.AdminSettingsDao
import ch.mibex.bamboo.shipit.task.artifacts.{ArtifactDownloaderTaskId, ArtifactSubscriptionId, DownloaderArtifactCollector, SubscribedArtifactCollector}
import ch.mibex.bamboo.shipit.{Constants, Logging}
import com.atlassian.bamboo.build.logger.BuildLogger
import com.atlassian.bamboo.deployments.execution.{DeploymentTaskContext, DeploymentTaskType}
import com.atlassian.bamboo.deployments.projects.service.DeploymentProjectService
import com.atlassian.bamboo.security.EncryptionService
import com.atlassian.bamboo.task._
import com.atlassian.bamboo.v2.build.CommonContext
import com.atlassian.bamboo.v2.build.trigger.{DependencyTriggerReason, TriggerReason}
import com.atlassian.marketplace.client.model.AddonVersion
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.plugin.tool.PluginInfoTool
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

case class JiraProjectData(projectKey: String, version: String, triggerUserName: String)


@Component
class ShipItTask @Autowired()(@ComponentImport encryptionService: EncryptionService,
                              @ComponentImport deploymentProjectService: DeploymentProjectService,
                              @ComponentImport i18nResolver: I18nResolver,
                              mpacCredentialsDao: AdminSettingsDao,
                              buildArtifactCollector: DownloaderArtifactCollector,
                              newPluginDataCollector: NewPluginVersionDataCollector,
                              subscribedArtifactCollector: SubscribedArtifactCollector)
  extends TaskType with DeploymentTaskType with Logging {

  import Constants._
  import ShipItTaskConfigurator._

  override def execute(taskContext: TaskContext): TaskResult =
    runTask(
      taskContext = taskContext,
      commonContext = taskContext.getBuildContext,
      triggerReason => triggerReason.getKey == JiraReleaseTriggerReasonKey,
      isBranchBuild = taskContext.getBuildContext.isBranch
    )

  override def execute(deploymentTaskContext: DeploymentTaskContext): TaskResult =
    runTask(
      taskContext = deploymentTaskContext,
      commonContext = deploymentTaskContext.getDeploymentContext,
      triggerReason => Array(JiraReleaseTriggerReasonKey, DependencyTriggerReason.KEY) contains triggerReason.getKey,
      isBranchBuild = false // for deployment projects we can use conditional triggers for this
    )

  private def runTask(taskContext: CommonTaskContext,
                      commonContext: CommonContext,
                      isAllowedTriggerReason: TriggerReason => Boolean,
                      isBranchBuild: Boolean) = {
    val buildLogger = taskContext.getBuildLogger
    val taskBuilder = TaskResultBuilder.newBuilder(taskContext)

    if (isBranchBuild && !isBranchBuildEnabled(taskContext)) {
      buildLogger.addBuildLogEntry(i18nResolver.getText("shipit.task.branch.builds.not.enabled"))
      taskBuilder.success.build
    } else if (isOnlyDeployFromJiraReleasePanelAllowed(taskContext)
      && !(isTriggeredFromJira(commonContext) && isAllowedTriggerReason(commonContext.getTriggerReason))) {
      buildLogger.addBuildLogEntry(i18nResolver.getText("shipit.task.not.triggered.from.jira"))
      // we should not fail the build in this case because this could be a plan branch which should
      // not result in a Marketplace deployment
      taskBuilder.success.build
    } else {
      try {
        createNewPluginVersion(taskContext, commonContext, taskBuilder)
      } catch {
        case e: TaskException =>
          // it is much nicer to directly see an error in the build log summary than to see a stack trace in the build
          // log; this is why we add a log entry instead of just throwing the TaskException
          buildLogger.addErrorLogEntry(s"ShipIt to Marketplace task: ${e.getMessage}")
          taskBuilder.failed.build()
      }
    }
  }

  private def createNewPluginVersion(taskContext: CommonTaskContext,
                                     commonContext: CommonContext,
                                     taskBuilder: TaskResultBuilder): TaskResult =
    MpacFacade.withMpac(getMpacCredentials) { mpac =>
      val buildLogger = taskContext.getBuildLogger
      val artifact = findArtifact(taskContext)
      val pluginInfo = PluginInfoTool.parsePluginArtifact(artifact)
      mpac.findPlugin(pluginInfo.getKey) match {
        case Left(error) =>
          buildLogger.addErrorLogEntry(i18nResolver.getText(error.i18n))
          taskBuilder.failed().build
        case Right(Some(plugin)) =>
          findBaseVersionForNewSubmission(plugin.getKey, commonContext, mpac) match {
            case Left(error) =>
              val msg = i18nResolver.getText("shipit.task.plugin.notfound.error", pluginInfo.getKey, i18nResolver.getText(error.i18n))
              buildLogger.addErrorLogEntry(msg)
              taskBuilder.failed().build
            case Right(Some(baseVersion)) =>
              val pluginMarketing = getPluginMarketingInfo(artifact, taskContext)
              val newPluginVersion = newPluginDataCollector.collectData(
                taskContext, commonContext, artifact, baseVersion, pluginInfo, plugin, pluginMarketing
              )(mpac)
              uploadNewPluginVersion(taskContext, taskBuilder, buildLogger, mpac, newPluginVersion)
            case _ =>
              buildLogger.addErrorLogEntry(i18nResolver.getText("shipit.task.plugin.notfound.error", pluginInfo.getKey))
              taskBuilder.failed().build
          }
        case _ =>
          buildLogger.addErrorLogEntry(i18nResolver.getText("shipit.task.plugin.notfound.error", pluginInfo.getKey))
          taskBuilder.failed().build
      }
    }

  private def getPluginMarketingInfo(artifact: File, taskContext: CommonTaskContext) = {
    val is = new FileInputStream(artifact)
    try {
      Option(PluginInfoTool.getPluginDetailsFromJar(is).getMarketingBean)
    } catch {
      case e: Exception if isDcDeployment(taskContext) =>
        // DC deployment requires atlassian-plugin-marketing.xml
        throw e
      case e: Exception =>
        // we don't necessarily need the marketing plug-in details if non-dc deployment
        debug("SHIPIT2MARKETPLACE: failed to get marketing plug-in details from JAR", e)
        None
    } finally {
      is.close()
    }
  }

  private def uploadNewPluginVersion(taskContext: CommonTaskContext,
                                     taskBuilder: TaskResultBuilder,
                                     buildLogger: BuildLogger,
                                     mpac: MpacFacade,
                                     newPluginVersion: NewPluginVersionDetails): TaskResult = {
    debug(s"SHIPIT2MARKETPLACE: new plug-in version to upload: $newPluginVersion")
    mpac.publish(newPluginVersion) match {
      case Right(newVersion) =>
        val successMsg = i18nResolver.getText("shipit.task.successfully.shipped",
          newVersion.getName.getOrElse("?"), newPluginVersion.plugin.getName)
        buildLogger.addBuildLogEntry(successMsg)
        storeResultsLinkInfo(taskContext, newVersion)
        taskBuilder.success.build
      case Left(me: MpacError) =>
        me match {
          case e: MpacUploadError =>
            buildLogger.addErrorLogEntry(i18nResolver.getText(me.i18n, e.reason, newPluginVersion.toString()))
          case _ =>
            buildLogger.addErrorLogEntry(i18nResolver.getText(me.i18n))
        }
        taskBuilder.failed().build
    }
  }

  private def isOnlyDeployFromJiraReleasePanelAllowed(taskContext: CommonTaskContext) =
    Option(taskContext.getConfigurationMap.getAsBoolean(IsJiraReleasePanelModeField)).getOrElse(false)

  private def isBranchBuildEnabled(taskContext: CommonTaskContext) =
    Option(taskContext.getConfigurationMap.getAsBoolean(RunOnBranchBuildsField)).getOrElse(false)

  private def isDcDeployment(taskContext: CommonTaskContext) = {
    val vars = taskContext.getCommonContext.getVariableContext.getEffectiveVariables
    val isDcBuildNrConfigured = Option(vars.get(BambooDataCenterBuildNrVariableKey)) match {
      case Some(dcBuildNrVariable) => Option(dcBuildNrVariable).map(_.getValue).getOrElse("").trim.nonEmpty
      case None => false
    }
    Option(taskContext.getConfigurationMap.getAsBoolean(CreateDcDeploymentField)).getOrElse(false) || isDcBuildNrConfigured
  }

  // this is an additional safety check that this build has been triggered from JIRA because
  // the trigger reason JIRA is not always propagated to the deployment project
  private def isTriggeredFromJira(buildContext: CommonContext) = {
    val vars = buildContext.getVariableContext.getEffectiveVariables
    Option(vars.get("jira.version")) match {
      case Some(jiraVersion) if jiraVersion.getValue.nonEmpty => true
      case _ => false
    }
  }

  private def storeResultsLinkInfo(commonTaskContext: CommonTaskContext, newVersion: AddonVersion) {
    commonTaskContext match {
      case t: TaskContext if newVersion.getArtifactInfo.isDefined && newVersion.getName.isDefined =>
        val customBuildData = t.getBuildContext.getBuildResult.getCustomBuildData
        customBuildData.put(ResultLinkPluginBinaryUrl, newVersion.getArtifactInfo.get().getBinaryUri.toString)
        customBuildData.put(ResultLinkPluginVersion, newVersion.getName.get())
      case _ => // do not store the link as it is a deployment project where this is not supported yet
    }
  }

  private def getMpacCredentials = mpacCredentialsDao.find() match {
    case Some(credentials) =>
      MpacCredentials(
        vendorUserName = credentials.getVendorUserName,
        vendorPassword = encryptionService.decrypt(credentials.getVendorPassword)
      )
    case None => throw new TaskException("Marketplace credentials not found")
  }

  private def findArtifact(taskContext: CommonTaskContext) = {
    val artifactToDeployId = Option(taskContext.getConfigurationMap.get(ArtifactToDeployKeyField)).getOrElse(
      throw new TaskException("Artifact to deploy setting not configured")
    )
    (artifactToDeployId match {
      case ArtifactSubscriptionId(artifactId, artifactName) =>
        subscribedArtifactCollector.findArtifactInSubscriptions(taskContext, artifactId)
      case ArtifactDownloaderTaskId(artifactId, artifactName, downloaderTaskId, transferId) =>
        buildArtifactCollector.findArtifactInDownloaderTask(taskContext, artifactId, downloaderTaskId, transferId)
      case _ => throw new TaskException(s"Artifact deploy ID format '$artifactToDeployId' unknown")
    }).getOrElse(throw new TaskException("Artifact to deploy setting not found"))
  }

  private def findBaseVersionForNewSubmission(pluginKey: String, commonContext: CommonContext, mpac: MpacFacade) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    Option(vars.get(BambooVariables.BambooPluginBaseVersionVariableKey)) match {
      case Some(baseVersion) if Option(baseVersion.getValue).isDefined && baseVersion.getValue.nonEmpty =>
        // Bamboo variable has always precedence
        mpac.getVersion(pluginKey, Option(baseVersion.getValue))
      case _ =>
        mpac.getVersion(pluginKey)
    }
  }

}
