package ch.mibex.bamboo.shipit.task.artifacts

import ch.mibex.bamboo.shipit.Utils
import com.atlassian.bamboo.plan.artifact.ArtifactDefinitionManager
import com.atlassian.bamboo.plugin.{ArtifactDownloaderTaskConfigurationHelper, BambooPluginKeys}
import com.atlassian.bamboo.plugins.artifact.RequestedArtifacts
import com.atlassian.bamboo.task.{CommonTaskContext, TaskDefinition}
import com.atlassian.bamboo.variable.CustomVariableContext
import com.atlassian.bamboo.webwork.util.WwSelectOption
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.io.File
import java.util
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

// there are three ways to define artifacts in Bamboo:
// - inter-plan artifacts by sharing
// - sharing artifacts between build plans through the artifact downloader task
// - sharing artifacts from a build plan to a deployment environment
// see https://confluence.atlassian.com/display/BAMBOO058/Sharing+artifacts
@Component
class DownloaderArtifactCollector @Autowired() (
    @ComponentImport artifactDefinitionManager: ArtifactDefinitionManager,
    @ComponentImport i18nResolver: I18nResolver,
    @ComponentImport variableContext: CustomVariableContext
) {

  def findArtifactInDownloaderTask(
      taskContext: CommonTaskContext,
      artifactId: Long,
      downloaderTaskId: Long,
      transferId: Int
  ): Option[File] =
    taskContext.getCommonContext.getRuntimeTaskDefinitions.asScala
      .find(t => t.getId == downloaderTaskId)
      .flatMap(downloaderTask => {
        val requestedArtifacts: RequestedArtifacts = downloaderTask.getRuntimeData.get(ArtifactDownloaderTaskConfigurationHelper.ARTIFACT_CONTEXTS).asInstanceOf[RequestedArtifacts]
        val downloadRequests: List[RequestedArtifacts.Request] = requestedArtifacts.getRequestsForKeyIndex(transferId).asScala.toList

        var localPath: String = ""
        val pathSpecs: StringBuilder = new StringBuilder()
        for
          request <- downloadRequests
          copyPattern <- request.getContext().getCopyPatterns.asScala
        do {
          if (pathSpecs.length > 0) pathSpecs.append(',')
          pathSpecs.append(copyPattern)
          localPath = request.getLocalPath
        }
        Utils.findMostRecentMatchingFile(pathSpecs.toString(), new File(taskContext.getWorkingDirectory, localPath))
      })

  def buildArtifactUiList(taskDefinitions: Seq[TaskDefinition]): Seq[WwSelectOption] = {
    case class ArtifactInfo(id: Long, key: String)

    var artifactsToDeploy = Vector.empty[WwSelectOption]
    filterEnabledDownloaderTasks(taskDefinitions) foreach { t =>
      val sourcePlanKey = ArtifactDownloaderTaskConfigurationHelper.getSourcePlanKey(t.getConfiguration)
      val groupName = i18nResolver.getText("shipit.task.config.individual.artifacts")

      ArtifactDownloaderTaskConfigurationHelper
        .getArtifactKeys(t.getConfiguration)
        .asScala
        .map(artifactKey => ArtifactInfo(id = t.getConfiguration.get(artifactKey).toLong, key = artifactKey))
        .filter(artifactInfo => artifactInfo.id > -1)
        .foreach(artifactInfo => {
          val artifactDefinition = artifactDefinitionManager.findArtifactDefinition(artifactInfo.id)
          if (Option(artifactDefinition).isDefined) {
            val artifactName = s"$sourcePlanKey: ${artifactDefinition.getName}"
            val transferId = ArtifactDownloaderTaskConfigurationHelper.getIndexFromKey(artifactInfo.key)
            val selectedValue = ArtifactDownloaderTaskId(artifactDefinition, t, transferId)
            artifactsToDeploy :+= new WwSelectOption(artifactName, groupName, selectedValue.toString)
          }
        })
    }
    artifactsToDeploy
  }

  private def filterEnabledDownloaderTasks(taskDefinitions: Seq[TaskDefinition]) =
    taskDefinitions.filter(t => t.getPluginKey == BambooPluginKeys.ARTIFACT_DOWNLOAD_TASK_MODULE_KEY && t.isEnabled)

}
