package ch.mibex.bamboo.shipit.task

import java.io.File
import java.util.concurrent.Callable

import ch.mibex.bamboo.shipit.jira.JiraFacade
import ch.mibex.bamboo.shipit.mpac.{MpacFacade, NewPluginVersionDetails}
import ch.mibex.bamboo.shipit.{Constants, Logging, Utils}
import com.atlassian.applinks.api.CredentialsRequiredException
import com.atlassian.bamboo.applinks.{ImpersonationService, JiraApplinksService}
import com.atlassian.bamboo.task.{CommonTaskContext, TaskDefinition, TaskException}
import com.atlassian.bamboo.user.BambooUserManager
import com.atlassian.bamboo.v2.build.CommonContext
import com.atlassian.bamboo.v2.build.trigger.ManualBuildTriggerReason
import com.atlassian.fugue
import com.atlassian.marketplace.client.model.{Addon, AddonVersion}
import com.atlassian.plugin.marketing.bean.{PluginMarketing, ProductCompatibility}
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.plugin.tool.PluginArtifactDetails
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._

@Component
class NewPluginVersionDataCollector @Autowired()(
    @ComponentImport jiraApplinksService: JiraApplinksService,
    @ComponentImport impersonationService: ImpersonationService,
    @ComponentImport bambooUserManager: BambooUserManager,
    @ComponentImport i18nResolver: I18nResolver)
    extends Logging {

  case class SummaryAndReleaseNotes(summary: String, releaseNotes: String)

  import Constants.BambooVariables._
  import Constants._
  import ShipItTaskConfigurator._

  lazy val FullyQualifiedPluginTaskKey = s"${Utils.findPluginKeyInDescriptor()}:$PluginTaskKey"

  def collectData(
      taskContext: CommonTaskContext,
      context: CommonContext,
      artifact: File,
      pluginInfo: PluginArtifactDetails,
      plugin: Addon,
      pluginMarketing: Option[PluginMarketing])(implicit mpac: MpacFacade): NewPluginVersionDetails = {
    val projectInfos = getParamsForJiraAccess(taskContext, pluginInfo, context)
    val releaseSummaryAndDescription = collectReleaseNotes(projectInfos, context, taskContext)
    val isPublicVersion = Option(taskContext.getConfigurationMap.get(IsPublicVersionField))
      .getOrElse(
        throw new TaskException(i18nResolver.getText("shipit.task.publicversion.missing"))
      )
      .toBoolean
    val deduceBuildNr = Option(taskContext.getConfigurationMap.get(DeduceBuildNrField))
      .getOrElse(
        throw new TaskException(i18nResolver.getText("shipit.task.deducebuildnumber.missing"))
      )
      .toBoolean
    val vars = taskContext.getCommonContext.getVariableContext.getEffectiveVariables
    val isDcBuildNrConfigured = Option(vars.get(BambooDataCenterBuildNrVariableKey)) match {
      case Some(dcBuildNrVariable) =>
        Option(dcBuildNrVariable).map(_.getValue).getOrElse("").trim.nonEmpty
      case None => false
    }
    val createDcDeploymentToo =
      Option(taskContext.getConfigurationMap.getAsBoolean(CreateDcDeploymentField)).getOrElse(false)
    val compatibility = pluginMarketing.map(_.getCompatibility.get(0))
    val baseVersion = findBaseVersionForNewSubmission(plugin.getKey, context)
    NewPluginVersionDetails(
      plugin = plugin,
      baseVersion = baseVersion,
      serverBuildNumber = determineBuildNumber(
        context,
        deduceBuildNr,
        isForDc = false,
        pluginInfo,
        BambooBuildNrVariableKey
      ),
      dataCenterBuildNumber = determineBuildNumber(
        context,
        deduceBuildNr,
        isForDc = true,
        pluginInfo,
        BambooDataCenterBuildNrVariableKey
      ),
      minServerBuildNumber = deduceHostProductCompatibility(compatibility, baseVersion, isMin = true, isDc = false),
      maxServerBuildNumber = deduceHostProductCompatibility(compatibility, baseVersion, isMin = false, isDc = false),
      minDataCenterBuildNumber = deduceHostProductCompatibility(compatibility, baseVersion, isMin = true, isDc = true),
      maxDataCenterBuildNumber = deduceHostProductCompatibility(compatibility, baseVersion, isMin = false, isDc = true),
      userName = getJiraTriggerUser(context),
      baseProduct = compatibility.map(_.getProduct.name()),
      versionNumber = pluginInfo.getVersion,
      isDcBuildNrConfigured = isDcBuildNrConfigured,
      createDcVersionToo = createDcDeploymentToo,
      binary = artifact,
      isPublicVersion = isPublicVersion,
      releaseSummary = releaseSummaryAndDescription.summary,
      releaseNotes = releaseSummaryAndDescription.releaseNotes
    )
  }

  private def findBaseVersionForNewSubmission(pluginKey: String, commonContext: CommonContext)(
      implicit mpac: MpacFacade) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    val result = Option(vars.get(BambooVariables.BambooPluginBaseVersionVariableKey)) match {
      case Some(baseVersion) if Option(baseVersion.getValue).isDefined && baseVersion.getValue.nonEmpty =>
        // Bamboo variable has always precedence
        mpac.getVersion(pluginKey, Option(baseVersion.getValue))
      case _ =>
        mpac.getVersion(pluginKey)
    }
    result match {
      case Left(error) =>
        val msg = i18nResolver.getText("shipit.task.plugin.notfound.error", pluginKey, i18nResolver.getText(error.i18n))
        throw new TaskException(msg)
      case Right(Some(baseVersion)) => baseVersion
      case _ =>
        throw new TaskException(i18nResolver.getText("shipit.task.plugin.notfound.error", pluginKey))
    }
  }

  private def deduceHostProductCompatibility(
      compatibilityOpt: Option[ProductCompatibility],
      baseVersion: AddonVersion,
      isMin: Boolean,
      isDc: Boolean)(implicit mpac: MpacFacade): Option[Int] = {
    compatibilityOpt match {
      case Some(c) => // if we have <compatibility> section in atlassian-plugin-marketing.xml, take it from there
        val version = if (isMin) c.getMin else c.getMax
        mpac.getBuildNumber(c.getProduct, Option(version)) match {
          case Left(e) => throw new TaskException(i18nResolver.getText(e.i18n))
          case Right(Some(buildNumber)) => Option(buildNumber)
          case _ =>
            throw new TaskException(
              i18nResolver.getText("shipit.task.no.build.number.found", c.getProduct.name(), version))
        }
      case None => // otherwise, let's take compatibility from the last app version
        val lastCompat = for {
          lastCompat <- baseVersion.getCompatibilities.asScala.headOption
        } yield lastCompat
        lastCompat match {
          case Some(lc) =>
            val compatVersion: Option[Integer] = if (isDc) {
              if (isMin) lc.getDataCenterMinBuild else lc.getDataCenterMaxBuild
            } else {
              if (isMin) lc.getServerMinBuild else lc.getServerMaxBuild
            }
            compatVersion.map(_.toInt)
          case None =>
            throw new TaskException(i18nResolver.getText("shipit.task.no.marketing.xml.found"))
        }
      case _ => None
    }
  }

  private def collectReleaseNotes(
      projectInfos: JiraProjectData,
      commonContext: CommonContext,
      taskContext: CommonTaskContext): SummaryAndReleaseNotes = {
    val vars = commonContext.getVariableContext.getEffectiveVariables

    val summaryAndReleaseNotes = for {
      releaseSummaryPlanVariable <- Option(vars.get(BambooReleaseSummaryVariableKey))
      releaseNotesPlanVariable <- Option(vars.get(BambooReleaseNotesVariableKey))
    } yield SummaryAndReleaseNotes(releaseSummaryPlanVariable.getValue, releaseNotesPlanVariable.getValue)

    summaryAndReleaseNotes match {
      // overridden by the user with plan variables, no need to get them from JIRA:
      case Some(fromPlanVariables) => fromPlanVariables
      case None => fetchReleaseNotesFromJira(projectInfos, taskContext)
    }
  }

  private def fetchReleaseNotesFromJira(projectInfos: JiraProjectData, taskContext: CommonTaskContext) = {
    val appLink = jiraApplinksService.getJiraApplicationLinks.asScala.headOption.getOrElse(
      throw new TaskException(i18nResolver.getText("shipit.task.jira.appllink.not.found"))
    )
    val jiraJob = impersonationService.runAsUser(
      projectInfos.triggerUserName,
      new Callable[SummaryAndReleaseNotes] {
        override def call(): SummaryAndReleaseNotes = {
          try {
            val requestFactory = appLink.createAuthenticatedRequestFactory()
            val jiraFacade = new JiraFacade(requestFactory)
            val releaseSummary = jiraFacade
              .getVersionDescription(projectInfos.projectKey, projectInfos.version)
              .getOrElse(
                throw new TaskException(
                  i18nResolver.getText("shipit.task.no.version.summary.found", projectInfos.version))
              )
            val releaseNotes = jiraFacade.collectReleaseNotes(
              projectKey = projectInfos.projectKey,
              projectVersion = projectInfos.version,
              jql = getJqlFromTaskConfig(taskContext)
            )
            SummaryAndReleaseNotes(releaseSummary, releaseNotes)
          } catch {
            case e: CredentialsRequiredException =>
              val reauthUrl = e.getAuthorisationURI().toString
              throw new TaskException(i18nResolver.getText("shipit.task.jira.applink.reauth.necessary", reauthUrl), e)
            case e: Exception =>
              log.error("SHIPIT2MARKETPLACE: failed to determine JIRA project info", e)
              throw new TaskException(i18nResolver.getText("shipit.task.jira.unknown.error", e.getMessage), e)
          }
        }
      }
    )
    jiraJob.call()
  }

  private def determineBuildNumber(
      commonContext: CommonContext,
      deduceBuildNr: Boolean,
      isForDc: Boolean,
      pluginInfo: PluginArtifactDetails,
      bambooBuildNrVariableKey: String) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    Option(vars.get(bambooBuildNrVariableKey)) match {
      case Some(buildNr) if Option(buildNr.getValue).isDefined && buildNr.getValue.trim.nonEmpty =>
        // Bamboo variable has always precedence
        buildNr.getValue.toInt
      case _ if deduceBuildNr => // otherwise we deduce the build number if the setting is active
        if (isForDc) Utils.toBuildNumber(pluginInfo.getVersion) + 1
        else Utils.toBuildNumber(pluginInfo.getVersion)
      case _ =>
        throw new TaskException(i18nResolver.getText("shipit.task.no.build.number", bambooBuildNrVariableKey))
    }
  }

  private def getParamsForJiraAccess(
      taskContext: CommonTaskContext,
      pluginInfo: PluginArtifactDetails,
      commonContext: CommonContext) = {
    val taskDefinition = getTaskDefinitionFromBuild(commonContext).getOrElse(
      throw new TaskException(i18nResolver.getText("shipit.task.notaskdef"))
    )
    if (onlyAllowDeployFromJiraReleasePanel(taskContext)) {
      val vars = commonContext.getVariableContext.getEffectiveVariables
      // the jira.* variables are only set when the release was triggered from JIRA
      val jiraData = for {
        projectKey <- Option(vars.get("jira.projectKey")) map { _.getValue }
        projectVersion <- Option(vars.get("jira.version")) map { _.getValue }
        triggerUserName <- getUserToCollectJiraData(getJiraTriggerUser(commonContext), taskDefinition)
      } yield JiraProjectData(projectKey, projectVersion, triggerUserName)
      jiraData.getOrElse(throw new TaskException(i18nResolver.getText("shipit.task.jira.params.not.found")))
    } else {
      val projectKey = Option(taskContext.getConfigurationMap.get(JiraProjectKeyField)).getOrElse(
        throw new TaskException(i18nResolver.getText("shipit.task.project.key.missing"))
      )
      val triggerUserName = taskContext.getCommonContext.getTriggerReason match {
        case m: ManualBuildTriggerReason => Option(m.getUserName)
        case _ => None
      }
      val user = getUserToCollectJiraData(triggerUserName, taskDefinition).getOrElse(
        throw new TaskException(i18nResolver.getText("shipit.task.no.valid.user.found"))
      )
      val projectVersion = pluginInfo.getVersion
      JiraProjectData(projectKey, projectVersion, user)
    }
  }

  private def getTaskDefinitionFromBuild(commonContext: CommonContext) =
    commonContext.getTaskDefinitions.asScala.find(_.getPluginKey == FullyQualifiedPluginTaskKey)

  private def onlyAllowDeployFromJiraReleasePanel(taskContext: CommonTaskContext) =
    Option(taskContext.getConfigurationMap.getAsBoolean(IsJiraReleasePanelModeField))
      .getOrElse(false)

  private def getJqlFromTaskConfig(taskContext: CommonTaskContext) =
    Option(taskContext.getConfigurationMap.get(JqlField)).getOrElse(DefaultJql)

  private def getUserToCollectJiraData(userName: Option[String], taskDefinition: TaskDefinition) = {
    val usersToTry = List(
      Option(taskDefinition.getConfiguration.get(UserNameField)),
      userName
    )
    usersToTry collectFirst {
      case Some(x) if Option(bambooUserManager.getBambooUser(x)).isDefined => x
    }
  }

  private def getJiraTriggerUser(buildContext: CommonContext) = {
    val vars = buildContext.getVariableContext.getEffectiveVariables
    Option(vars.get("jira.username")) map {
      _.getValue
    }
  }

  implicit def asScalaOption[T](upmOpt: fugue.Option[T]): Option[T] =
    if (upmOpt.isDefined) Some(upmOpt.get)
    else None

}
