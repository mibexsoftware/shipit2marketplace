package ch.mibex.bamboo.shipit.task

import java.util.{Map => JMap}

import ch.mibex.bamboo.shipit.mpac.{MpacCredentials, MpacFacade, NewPluginVersion}
import ch.mibex.bamboo.shipit.task.artifacts.{SubscribedArtifactCollector, DownloaderArtifactCollector, ArtifactDownloaderTaskId, ArtifactSubscriptionId}
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
    if (!isAllowedTriggerReason(commonContext.getTriggerReason)) {
      buildLogger.addBuildLogEntry(i18nResolver.getText("shipit.task.not.triggered.from.jira"))
      return taskBuilder.success.build
    }
    val taskDefinition = getTaskDefinitionFromBuild(commonContext).getOrElse(
      throw new TaskException("Task definition not found")
    )
    val runtimeContext = getRuntimeContext(commonContext, taskDefinition)
    MpacFacade.withMpac(getMpacCredentials(runtimeContext)) { mpac =>
      val newPluginVersion = collectDataForNewPluginVersion(taskContext, commonContext, taskDefinition, mpac)
      log.debug(s"SHIPIT2MARKETPLACE: new plug-in version to upload: $newPluginVersion")
      mpac.publish(newPluginVersion) match {
        case Right(newVersion) =>
          buildLogger.addBuildLogEntry(i18nResolver.getText("shipit.task.successfully.shipped",
                                                            newVersion.getVersion,
                                                            newPluginVersion.plugin.getName))
          storeResultsLinkInfos(taskContext, newVersion)
          taskBuilder.success.build
        case Left(mpacUploadError) =>
          buildLogger.addErrorLogEntry(i18nResolver.getText(mpacUploadError.i18n, mpacUploadError.reason))
          taskBuilder.failed().build
      }
    }
  }

  private def storeResultsLinkInfos(commonTaskContext: CommonTaskContext, newVersion: PluginVersion) {
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

  private def collectDataForNewPluginVersion(taskContext: CommonTaskContext,
                                             commonContext: CommonContext,
                                             taskDefinition: TaskDefinition,
                                             mpacFacade: MpacFacade) = {
    val runtimeContext = getRuntimeContext(commonContext, taskDefinition)
    val releaseSummary = Option(runtimeContext.get(ShipItVersionDescription)).getOrElse(
      throw new TaskException("Release summary could not be determined")
    )
    val releaseNotes = Option(runtimeContext.get(ShipItReleaseNotes)).getOrElse(
      throw new TaskException("Release notes could not be determined")
    )
    val isPublicVersion = Option(taskContext.getConfigurationMap.get(IsPublicVersionField)).getOrElse(
      throw new TaskException("Public version setting not found")
    ).toBoolean
    val deduceBuildNr = Option(taskContext.getConfigurationMap.get(DeduceBuildNrField)).getOrElse(
      throw new TaskException("Deduce build number setting not found")
    ).toBoolean
    val artifactToDeployId = Option(taskContext.getConfigurationMap.get(ArtifactToDeployKeyField)).getOrElse(
      throw new TaskException("Artifact to deploy setting not configured")
    )
    val artifact = (artifactToDeployId match {
      case ArtifactSubscriptionId(artifactId, artifactName) =>
        subscribedArtifactCollector.findArtifactInSubscriptions(taskContext, artifactId)
      case ArtifactDownloaderTaskId(artifactId, artifactName, downloaderTaskId, transferId) =>
        buildArtifactCollector.findArtifactInDownloaderTask(taskContext, artifactId, downloaderTaskId, transferId)
      case _ => throw new TaskException("Artifact deploy ID format unknown")
    }).getOrElse(throw new TaskException("Artifact to deploy setting not found"))

    val pluginInfo = PluginInfoTool.parsePluginArtifact(artifact)
    val plugin = mpacFacade.findPlugin(pluginInfo.getKey).getOrElse(
      throw new TaskException("Plug-in key not found in artifact")
    )
    val buildNumber = determineBuildNumber(commonContext, deduceBuildNr, pluginInfo)
    val binary = Deployment.deployableFromFile(artifact)
    val lastPluginVersion = determineLastPluginVersion(plugin).orNull
    NewPluginVersion(
      plugin = plugin,
      fromVersion = lastPluginVersion,
      buildNumber = buildNumber,
      versionNumber = pluginInfo.getVersion,
      binary = binary,
      isPublicVersion = isPublicVersion,
      releaseSummary = releaseSummary,
      releaseNotes = releaseNotes
    )
  }

  private def determineBuildNumber(commonContext: CommonContext,
                                   deduceBuildNr: Boolean,
                                   pluginInfo: PluginArtifactDetails) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    Option(vars.get(BambooBuildNrVariableKey)) match {
      case Some(buildNr) if buildNr.getValue.nonEmpty =>
        buildNr.getValue.toInt
      case Some(buildNr) if buildNr.getValue.isEmpty && deduceBuildNr =>
        Utils.toBuildNumber(pluginInfo.getVersion)
      case _ =>
        throw new TaskException("A build number has to be specified with the plan variable 'shipit2mpac.buildnr' " +
                                "if the build number deduction feature is disabled.")
    }
  }

  private def getTaskDefinitionFromBuild(commonContext: CommonContext) =
    commonContext.getTaskDefinitions.asScala.find(_.getPluginKey == FullyQualifiedPluginTaskKey)

  private def determineLastPluginVersion(plugin: Plugin) = plugin.getVersions.asScala match {
    case Nil => None
    case versions => Some(versions.maxBy(_.getBuildNumber))
  }

}
