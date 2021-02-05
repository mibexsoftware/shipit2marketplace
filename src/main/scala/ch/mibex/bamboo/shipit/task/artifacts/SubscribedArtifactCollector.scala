package ch.mibex.bamboo.shipit.task.artifacts

import java.io.File

import ch.mibex.bamboo.shipit.Utils
import com.atlassian.bamboo.plan.cache.ImmutableJob
import com.atlassian.bamboo.task.{CommonTaskContext, TaskContext}
import com.atlassian.bamboo.util.Narrow
import com.atlassian.bamboo.webwork.util.WwSelectOption
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._

@Component
class SubscribedArtifactCollector @Autowired() (@ComponentImport i18nResolver: I18nResolver) {

  def buildArtifactUiList(job: ImmutableJob): Seq[WwSelectOption] =
    (job.getArtifactSubscriptions.asScala map { as =>
      val selectedValue = ArtifactSubscriptionId(as.getArtifactDefinition)
      val groupName = i18nResolver.getText("shipit.task.config.subscribed.artifacts")
      new WwSelectOption(as.getName, groupName, selectedValue.toString)
    }).toSeq

  def findArtifactInSubscriptions(taskContext: CommonTaskContext, artifactId: Long): Option[File] = {
    val rootDir = taskContext.getWorkingDirectory
    val buildTaskContext = Narrow.downTo(taskContext, classOf[TaskContext])
    Option(buildTaskContext) flatMap { btc =>
      (btc.getBuildContext.getArtifactContext.getSubscriptionContexts.asScala collectFirst {
        case asc if asc.getArtifactDefinitionContext.getId == artifactId =>
          Utils.findMostRecentMatchingFile(asc.getArtifactDefinitionContext.getCopyPattern, rootDir)
      }).flatten
    }
  }

}
