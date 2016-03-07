package ch.mibex.bamboo.shipit.task.artifacts

import java.io.File
import java.util

import ch.mibex.bamboo.shipit.Utils
import com.atlassian.bamboo.plan.artifact.ArtifactDefinitionManager
import com.atlassian.bamboo.plugin.{ArtifactDownloaderTaskConfigurationHelper, BambooPluginUtils}
import com.atlassian.bamboo.task.{CommonTaskContext, TaskDefinition}
import com.atlassian.bamboo.variable.CustomVariableContext
import com.atlassian.bamboo.webwork.util.WwSelectOption
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._


// there are three ways to define artifacts in Bamboo:
// - inter-plan artifacts by sharing
// - sharing artifacts between build plans through the artifact downloader task
// - sharing artifacts from a build plan to a deployment environment
// see https://confluence.atlassian.com/display/BAMBOO058/Sharing+artifacts
@Component
class DownloaderArtifactCollector @Autowired()(@ComponentImport artifactDefinitionManager: ArtifactDefinitionManager,
                                               @ComponentImport i18nResolver: I18nResolver,
                                               @ComponentImport variableContext: CustomVariableContext) {

  def findArtifactInDownloaderTask(taskContext: CommonTaskContext,
                                   artifactId: Long,
                                   downloaderTaskId: Long,
                                   transferId: Int): Option[File] =
    taskContext.getCommonContext.getTaskDefinitions
      .asScala
      .find(t => t.getId == downloaderTaskId)
      .flatMap(downloaderTask => {
        val downloaderContext = getDownloaderContext(taskContext, downloaderTask)
        getRuntimeArtifactIds(transferId, downloaderContext) find { rai =>
          ArtifactDownloaderTaskConfigurationHelper.getArtifactId(downloaderContext, rai) == artifactId
        } flatMap { ai =>
          val copyPattern = ArtifactDownloaderTaskConfigurationHelper.getCopyPattern(downloaderContext, ai)
          val subst = variableContext.substituteString(copyPattern,
                                                       taskContext.getCommonContext,
                                                       taskContext.getBuildLogger)
          val localPath = ArtifactDownloaderTaskConfigurationHelper.getLocalPath(downloaderContext, ai)
          Utils.findMostRecentMatchingFile(subst, new File(taskContext.getWorkingDirectory, localPath))
        }
      })

  private def getRuntimeArtifactIds(transferId: Int, artifactDownloaderContext: util.Map[String, String]) =
    ArtifactDownloaderTaskConfigurationHelper.getRuntimeArtifactIds(artifactDownloaderContext, transferId).asScala

  private def getDownloaderContext(taskContext: CommonTaskContext, downloaderTask: TaskDefinition) =
    taskContext.getCommonContext.getRuntimeTaskContext.getRuntimeContextForTask(downloaderTask)


  def buildArtifactUiList(taskDefinitions: Seq[TaskDefinition]): Seq[WwSelectOption] = {
    case class ArtifactInfo(id: Long, key: String)

    var artifactsToDeploy = Vector.empty[WwSelectOption]
    filterEnabledDownloaderTasks(taskDefinitions) foreach { t =>
      val sourcePlanKey = ArtifactDownloaderTaskConfigurationHelper.getSourcePlanKey(t.getConfiguration)
      val groupName = i18nResolver.getText("shipit.task.config.individual.artifacts")

      ArtifactDownloaderTaskConfigurationHelper.getArtifactKeys(t.getConfiguration).asScala
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

  private def filterEnabledDownloaderTasks(taskDefinitions: Seq[TaskDefinition])=
    taskDefinitions.filter(t => t.getPluginKey == BambooPluginUtils.ARTIFACT_DOWNLOAD_TASK_MODULE_KEY && t.isEnabled)

}
