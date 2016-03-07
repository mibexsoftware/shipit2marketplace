package ch.mibex.bamboo.shipit.task

import java.io.File
import java.util.{Map => JMap}

import ch.mibex.bamboo.shipit.mpac.MpacError.MpacUploadError
import ch.mibex.bamboo.shipit.mpac.{MpacError, MpacCredentials, MpacFacade, NewPluginVersionDetails}
import ch.mibex.bamboo.shipit.task.artifacts.{ArtifactDownloaderTaskId, ArtifactSubscriptionId, DownloaderArtifactCollector, SubscribedArtifactCollector}
import ch.mibex.bamboo.shipit.{Constants, Logging, Utils}
import com.atlassian.bamboo.deployments.execution.{DeploymentTaskContext, DeploymentTaskType}
import com.atlassian.bamboo.deployments.projects.service.DeploymentProjectService
import com.atlassian.bamboo.security.EncryptionService
import com.atlassian.bamboo.task._
import com.atlassian.bamboo.v2.build.CommonContext
import com.atlassian.bamboo.v2.build.trigger.{DependencyTriggerReason, TriggerReason}
import com.atlassian.marketplace.client.api.PluginVersionUpdate.Deployment
import com.atlassian.marketplace.client.model.{Plugin, PluginVersion}
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.plugin.tool.{PluginArtifactDetails, PluginInfoTool}
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._

@Component
class ShipItTask @Autowired()(@ComponentImport encryptionService: EncryptionService,
                              @ComponentImport deploymentProjectService: DeploymentProjectService,
                              @ComponentImport i18nResolver: I18nResolver,
                              buildArtifactCollector: DownloaderArtifactCollector,
                              subscribedArtifactCollector: SubscribedArtifactCollector)
    extends TaskType with DeploymentTaskType with Logging {

  import Constants._
  import ShipItTaskConfigurator._
  import ShipItTaskDataProvider._

  lazy val FullyQualifiedPluginTaskKey = s"${Utils.findPluginKeyInDescriptor()}:$PluginTaskKey"

  override def execute(taskContext: TaskContext): TaskResult =
    runTask(
      taskContext = taskContext,
      commonContext = taskContext.getBuildContext,
      triggerReason => triggerReason.getKey == JiraReleaseTriggerReasonKey
    )

  override def execute(deploymentTaskContext: DeploymentTaskContext): TaskResult =
    runTask(
      taskContext = deploymentTaskContext,
      commonContext = deploymentTaskContext.getDeploymentContext,
      triggerReason => Array(JiraReleaseTriggerReasonKey, DependencyTriggerReason.KEY) contains triggerReason.getKey
    )

  private def runTask(taskContext: CommonTaskContext,
                      commonContext: CommonContext,
                      isAllowedTriggerReason: TriggerReason => Boolean): TaskResult = {
    val buildLogger = taskContext.getBuildLogger
    val taskBuilder = TaskResultBuilder.newBuilder(taskContext)
    if (!isTriggeredFromJira(commonContext) || !isAllowedTriggerReason(commonContext.getTriggerReason)) {
      buildLogger.addBuildLogEntry(i18nResolver.getText("shipit.task.not.triggered.from.jira"))
      return taskBuilder.success.build
    }
    val taskDefinition = getTaskDefinitionFromBuild(commonContext).getOrElse(
      throw new TaskException("Task definition not found")
    )
    val runtimeContext = getRuntimeContext(commonContext, taskDefinition)
    // report errors detected in the run-time task provider
    Option(runtimeContext.get(RunTimeTaskError)) match {
      case Some(error) =>
        buildLogger.addErrorLogEntry(error)
        taskBuilder.failed().build
      case None =>
        uploadNewPluginVersion(taskContext, commonContext, runtimeContext, taskBuilder)
    }
  }

  private def uploadNewPluginVersion(taskContext: CommonTaskContext,
                                     commonContext: CommonContext,
                                     runtimeContext: JMap[String, String],
                                     taskBuilder: TaskResultBuilder): TaskResult = {
    val buildLogger = taskContext.getBuildLogger
    MpacFacade.withMpac(getMpacCredentials(runtimeContext)) { mpac =>
      val artifact = findArtifact(taskContext)
      val pluginInfo = PluginInfoTool.parsePluginArtifact(artifact)

      mpac.findPlugin(pluginInfo.getKey) match {
        case Left(error) =>
          buildLogger.addErrorLogEntry(i18nResolver.getText(error.i18n))
          taskBuilder.failed().build
        case Right(plugin) if plugin.isDefined =>
          val newPluginVersion = prepareDataForNewPluginVersion(
            taskContext, commonContext, runtimeContext, artifact, pluginInfo, plugin.get
          )
          log.debug(s"SHIPIT2MARKETPLACE: new plug-in version to upload: $newPluginVersion")
          mpac.publish(newPluginVersion) match {
            case Right(newVersion) =>
              buildLogger.addBuildLogEntry(
                i18nResolver.getText("shipit.task.successfully.shipped",
                newVersion.getVersion,
                newPluginVersion.plugin.getName)
              )
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
        case _ =>
          buildLogger.addErrorLogEntry(i18nResolver.getText("shipit.task.plugin.notfound.error", pluginInfo.getKey))
          taskBuilder.failed().build
      }
    }
  }

  private def prepareDataForNewPluginVersion(taskContext: CommonTaskContext,
                                             commonContext: CommonContext,
                                             runtimeContext: JMap[String, String],
                                             artifact: File,
                                             pluginInfo: PluginArtifactDetails,
                                             plugin: Plugin): NewPluginVersionDetails = {
    val releaseSummary = geValueFromPlanVariableOrRuntime(
      planVariableKey = BambooReleaseSummaryVariableKey,
      runtimeVariableKey = ShipItVersionDescription,
      commonContext, runtimeContext
    )
    val releaseNotes = geValueFromPlanVariableOrRuntime(
      planVariableKey = BambooReleaseNotesVariableKey,
      runtimeVariableKey = ShipItReleaseNotes,
      commonContext, runtimeContext
    )
    val isPublicVersion = Option(taskContext.getConfigurationMap.get(IsPublicVersionField)).getOrElse(
      throw new TaskException("Public version setting not found")
    ).toBoolean
    val deduceBuildNr = Option(taskContext.getConfigurationMap.get(DeduceBuildNrField)).getOrElse(
      throw new TaskException("Deduce build number setting not found")
    ).toBoolean
    val buildNumber = determineBuildNumber(commonContext, deduceBuildNr, pluginInfo)
    val binary = Deployment.deployableFromFile(artifact)
    val lastPluginVersion = findLastPublishedPluginVersion(plugin).orNull
    NewPluginVersionDetails(
      plugin = plugin,
      baseVersion = lastPluginVersion,
      buildNumber = buildNumber,
      versionNumber = pluginInfo.getVersion,
      binary = binary,
      isPublicVersion = isPublicVersion,
      releaseSummary = releaseSummary,
      releaseNotes = releaseNotes
    )
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

  private def storeResultsLinkInfo(commonTaskContext: CommonTaskContext, newVersion: PluginVersion) {
    commonTaskContext match {
      case t: TaskContext if newVersion.getBinaryUri.isDefined =>
        val customBuildData = t.getBuildContext.getBuildResult.getCustomBuildData
        customBuildData.put(ResultLinkPluginBinaryUrl, newVersion.getBinaryUri.get().toString)
        customBuildData.put(ResultLinkPluginVersion, newVersion.getVersion)
      case _ => // do not store the link as it is a deployment project where this is not supported yet
    }
  }

  private def getRuntimeContext(taskContext: CommonContext, taskDefinition: TaskDefinition) =
    taskContext.getRuntimeTaskContext.getRuntimeContextForTask(taskDefinition)

  private def getMpacCredentials(runtimeContext: JMap[String, String]) = {
    val vendorName = Option(runtimeContext.get(MpacVendorName)).getOrElse(
      throw new TaskException("Marketplace vendor name in plug-in settings not configured")
    )
    val vendorPassword = Option(runtimeContext.get(MpacVendorPassword)) match {
      case Some(pw) if pw.trim.nonEmpty => encryptionService.decrypt(pw)
      case None => throw new TaskException("Marketplace vendor name in plug-in settings not configured")
    }
    MpacCredentials(vendorName, vendorPassword)
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
      case _ => throw new TaskException("Artifact deploy ID format unknown")
    }).getOrElse(throw new TaskException("Artifact to deploy setting not found"))
  }

  private def geValueFromPlanVariableOrRuntime(planVariableKey: String,
                                               runtimeVariableKey: String,
                                               commonContext: CommonContext,
                                               runtimeContext: JMap[String, String]) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    Option(vars.get(planVariableKey)) match {
      case Some(variable) if variable.getValue.trim.nonEmpty => // plan variables have precedence
        variable.getValue
      case _ =>
        Option(runtimeContext.get(runtimeVariableKey)).getOrElse(
          throw new TaskException(s"$planVariableKey and $runtimeVariableKey could not be determined")
        )
    }
  }

  private def determineBuildNumber(commonContext: CommonContext,
                                   deduceBuildNr: Boolean,
                                   pluginInfo: PluginArtifactDetails) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    val context = vars.get(BambooBuildNrVariableKey)
    Option(context) match {
      case Some(buildNr) =>
        if (buildNr.getValue.nonEmpty) { // plan variable has always precedence
          buildNr.getValue.toInt
        } else {
          Utils.toBuildNumber(pluginInfo.getVersion)
        }
      case None if deduceBuildNr => // otherwise we deduce the build number if the setting is active
        Utils.toBuildNumber(pluginInfo.getVersion)
      case _ =>
        throw new TaskException(
          s"""A build number has to be specified with the plan variable
             | '$BambooBuildNrVariableKey' if the build number deduction feature is disabled.""".stripMargin
        )
    }
  }

  private def getTaskDefinitionFromBuild(commonContext: CommonContext) =
    commonContext.getTaskDefinitions.asScala.find(_.getPluginKey == FullyQualifiedPluginTaskKey)

  private def findLastPublishedPluginVersion(plugin: Plugin) =
    plugin.getVersions.asScala.filter(_.isPublished) match {
      case Nil => None
      case versions =>
        val maxVersion = versions.maxBy(_.getBuildNumber)
        log.info(s"SHIPT2MARKETPLACE: going to take version ${maxVersion.getVersion} as the basis for the new version")
        Some(maxVersion)
    }

}
