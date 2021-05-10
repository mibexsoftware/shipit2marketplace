package ch.mibex.bamboo.shipit.task

import java.util
import java.util.{Map => JMap}

import ch.mibex.bamboo.shipit.{Constants, Logging}
import com.atlassian.bamboo.chains.ChainResultsSummary
import com.atlassian.plugin.PluginAccessor
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.plugin.web.model.AbstractWebPanel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._
@Component
class ShipItResultsLinkPanel @Autowired() (@ComponentImport pluginAccessor: PluginAccessor)
    extends AbstractWebPanel(pluginAccessor)
    with Logging {

  private val MpacLogoBase64Encoded =
    """<img src="data:image/jpeg;base64,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"/>"""

  // currently, there is no way to show a web panel on the environment of the deployment project,
  // see https://jira.atlassian.com/browse/BAM-13280
  override def getHtml(context: util.Map[String, AnyRef]): String = createMpacSubmissionLink(context)

  private def createMpacSubmissionLink(context: JMap[String, AnyRef]): String =
    context.get("resultSummary") match {
      case crs: ChainResultsSummary =>
        val pluginInfos = for {
          jobSummaries <- crs.getOrderedJobResultSummaries.asScala
          (key, pluginBinaryUrl) <- jobSummaries.getCustomBuildData.asScala
          if key == Constants.ResultLinkPluginBinaryUrl
          (key, pluginVersion) <- jobSummaries.getCustomBuildData.asScala if key == Constants.ResultLinkPluginVersion
        } yield (pluginBinaryUrl, pluginVersion)
        pluginInfos.toList match {
          case Nil => ""
          case (binaryUrl, version) :: _ =>
            s"""<p>$MpacLogoBase64Encoded <a href="$binaryUrl">Atlassian Marketplace version $version</a></p>"""
        }
      case _ => ""
    }

}
