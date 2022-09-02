package ch.mibex.bamboo.shipit.task

import ch.mibex.bamboo.shipit.jira.JiraFacade
import ch.mibex.bamboo.shipit.mpac.MpacError.MpacAuthenticationError
import ch.mibex.bamboo.shipit.mpac.{MpacCredentials, MpacFacade}
import ch.mibex.bamboo.shipit.settings.AdminSettingsDao
import ch.mibex.bamboo.shipit.task.artifacts.{DownloaderArtifactCollector, SubscribedArtifactCollector}
import ch.mibex.bamboo.shipit.{Constants, Logging}
import com.atlassian.applinks.api.{ApplicationLink, ApplicationLinkResponseHandler, CredentialsRequiredException}
import com.atlassian.bamboo.applinks.{ImpersonationService, JiraApplinksService}
import com.atlassian.bamboo.collections.ActionParametersMap
import com.atlassian.bamboo.configuration.AdministrationConfigurationAccessor
import com.atlassian.bamboo.deployments.DeploymentTaskContextHelper
import com.atlassian.bamboo.plan.cache.ImmutableJob
import com.atlassian.bamboo.security.EncryptionService
import com.atlassian.bamboo.task._
import com.atlassian.bamboo.user.{BambooAuthenticationContext, BambooUserManager}
import com.atlassian.bamboo.util.Narrow
import com.atlassian.bamboo.utils.error.ErrorCollection
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.net.Response
import com.google.common.collect.Maps
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.lang.{Boolean => JBoolean}
import java.util.concurrent.Callable
import java.util.{Map => JMap}
import scala.collection.JavaConverters._

object ShipItTaskConfigurator {
  final val IsPublicVersionField = "publicVersion"
  final val UserNameField = "bambooUserId"
  final val DeduceBuildNrField = "deduceBuildNrFromPluginVersion"
  final val ArtifactToDeployKeyField = "artifactToDeployKey"
  final val AllArtifactsToDeployList = "allArtifactsToDeploy"
  final val RunOnBranchBuildsField = "runOnBranchBuilds"
  final val ServerDeploymentField = "serverDeployment"
  final val DcDeploymentField = "createDcDeployment"
  final val IsJiraReleasePanelModeField = "jiraReleasePanelDeploymentOnly"
  final val JiraProjectKeyField = "jiraProjectKey"
  final val JiraVersionPrefixField = "jiraVersionPrefix"
  final val JiraProjectList = "jiraProjects"
  final val JqlField = "jql"
}

@Component
class ShipItTaskConfigurator @Autowired()(
    @ComponentImport jiraApplinksService: JiraApplinksService,
    @ComponentImport impersonationService: ImpersonationService,
    @ComponentImport configAccessor: AdministrationConfigurationAccessor,
    @ComponentImport bambooUserManager: BambooUserManager,
    @ComponentImport bambooAuthContext: BambooAuthenticationContext,
    mpacCredentialsDao: AdminSettingsDao,
    downloaderArtifactCollector: DownloaderArtifactCollector,
    subscribedArtifactCollector: SubscribedArtifactCollector
) extends AbstractTaskConfigurator
    with Logging {

  import Constants._
  import ShipItTaskConfigurator._

  override def populateContextForEdit(context: JMap[String, Object], taskDefinition: TaskDefinition): Unit = {
    fillContextFromConfig(context, taskDefinition)
    context.put(JiraProjectList, getJiraProjects.asJava)
    context.put(AllArtifactsToDeployList, collectArtifactsForUiList(context).asJava)
    // this is for old task configurations where it was not possible to choose the following values
    if (Option(context.get(IsJiraReleasePanelModeField)).isEmpty) {
      context.put(IsJiraReleasePanelModeField, JBoolean.TRUE)
    }
    if (Option(context.get(JqlField)).isEmpty) {
      context.put(JqlField, DefaultJql)
    }
    if (Option(context.get(ServerDeploymentField)).isEmpty) {
      context.put(ServerDeploymentField, JBoolean.TRUE)
    }
  }

  override def populateContextForCreate(context: JMap[String, AnyRef]): Unit = {
    context.put(AllArtifactsToDeployList, collectArtifactsForUiList(context).asJava)
    context.put(UserNameField, "")
    context.put(IsJiraReleasePanelModeField, JBoolean.TRUE)
    context.put(IsPublicVersionField, JBoolean.TRUE)
    context.put(DeduceBuildNrField, JBoolean.TRUE)
    context.put(RunOnBranchBuildsField, JBoolean.FALSE)
    context.put(ServerDeploymentField, JBoolean.TRUE)
    context.put(DcDeploymentField, JBoolean.FALSE)
    context.put(ArtifactToDeployKeyField, "")
    context.put(JiraProjectKeyField, "")
    context.put(JiraVersionPrefixField, "")
    context.put(JqlField, DefaultJql)
    context.put(JiraProjectList, getJiraProjects.asJava)
  }

  private def collectArtifactsForUiList(taskContext: JMap[String, Object]) =
    Option(Narrow.reinterpret(TaskContextHelper.getPlan(taskContext), classOf[ImmutableJob])) match {
      case Some(job) =>
        val taskDefinitions = job.getBuildDefinition.getTaskDefinitions
        val subscriptions = subscribedArtifactCollector.buildArtifactUiList(job)
        subscriptions ++ downloaderArtifactCollector.buildArtifactUiList(taskDefinitions.asScala.toList)
      case None => // it is a deployment project
        val env = DeploymentTaskContextHelper.getEnvironment(taskContext)
        downloaderArtifactCollector.buildArtifactUiList(env.getTaskDefinitions.asScala.toList)
    }

  override def generateTaskConfigMap(
      actionParams: ActionParametersMap,
      taskDefinition: TaskDefinition
  ): JMap[String, String] = {
    val config = Maps.newHashMap[String, String]()
    config.put(UserNameField, actionParams.getString(UserNameField))
    config.put(IsJiraReleasePanelModeField, actionParams.getBoolean(IsJiraReleasePanelModeField).toString)
    config.put(JqlField, actionParams.getString(JqlField))
    config.put(IsPublicVersionField, actionParams.getBoolean(IsPublicVersionField).toString)
    config.put(RunOnBranchBuildsField, actionParams.getBoolean(RunOnBranchBuildsField).toString)
    config.put(ServerDeploymentField, actionParams.getBoolean(ServerDeploymentField).toString)
    config.put(DcDeploymentField, actionParams.getBoolean(DcDeploymentField).toString)
    config.put(DeduceBuildNrField, actionParams.getBoolean(DeduceBuildNrField).toString)
    config.put(ArtifactToDeployKeyField, actionParams.getString(ArtifactToDeployKeyField))
    config.put(JiraProjectKeyField, actionParams.getString(JiraProjectKeyField))
    config.put(JiraVersionPrefixField, actionParams.getString(JiraVersionPrefixField))
    config
  }

  override def populateContextForView(context: JMap[String, AnyRef], taskDefinition: TaskDefinition): Unit = {
    fillContextFromConfig(context, taskDefinition)
  }

  private def fillContextFromConfig(context: JMap[String, Object], taskDefinition: TaskDefinition): Object = {
    context.put(UserNameField, taskDefinition.getConfiguration.get(UserNameField))
    context.put(IsPublicVersionField, taskDefinition.getConfiguration.get(IsPublicVersionField))
    context.put(JqlField, taskDefinition.getConfiguration.get(JqlField))
    context.put(DeduceBuildNrField, taskDefinition.getConfiguration.get(DeduceBuildNrField))
    context.put(RunOnBranchBuildsField, taskDefinition.getConfiguration.get(RunOnBranchBuildsField))
    context.put(ServerDeploymentField, taskDefinition.getConfiguration.get(ServerDeploymentField))
    context.put(DcDeploymentField, taskDefinition.getConfiguration.get(DcDeploymentField))
    context.put(ArtifactToDeployKeyField, taskDefinition.getConfiguration.get(ArtifactToDeployKeyField))
    context.put(IsJiraReleasePanelModeField, taskDefinition.getConfiguration.get(IsJiraReleasePanelModeField))
    context.put(JiraProjectKeyField, taskDefinition.getConfiguration.get(JiraProjectKeyField))
    context.put(JiraVersionPrefixField, taskDefinition.getConfiguration.get(JiraVersionPrefixField))

  }

  private def checkMpacCredentials(errors: ErrorCollection) = {
    def getSettingsUrl = s"$getBambooBaseUrl/admin/shipit2mpac/viewShip2MpacConfiguration.action"

    mpacCredentialsDao.findCredentialsDecrypted() match {
      case Some(vendorCredentials) =>
        MpacFacade.withMpac(vendorCredentials) { mpac =>
          mpac.checkCredentials() foreach {
            case error @ MpacAuthenticationError() => errors.addErrorMessage(getText(error.i18n, getSettingsUrl))
            case error => errors.addErrorMessage(getText(error.i18n))
          }
        }
      case None => errors.addErrorMessage(getText("shipit.task.config.vendor.credentials.missing", getSettingsUrl))
    }
  }

  override def validate(actionParams: ActionParametersMap, errors: ErrorCollection): Unit = {
    def isDeploymentPlan = Option(actionParams.getString("planKey")).isEmpty

    def isUserNameGiven = Option(actionParams.getString(UserNameField)).getOrElse("").trim.nonEmpty

    if (Option(actionParams.getString(ArtifactToDeployKeyField)).getOrElse("").trim.isEmpty) {
      errors.addError(ArtifactToDeployKeyField, "Artifact must not be empty.")
    }
    if (!actionParams.getBoolean(IsJiraReleasePanelModeField)
      && Option(actionParams.getString(JiraProjectKeyField)).getOrElse("").trim.isEmpty) {
      errors.addError(JiraProjectKeyField, "JIRA project must not be empty when not using JIRA release panel mode.")
    }
    checkMpacCredentials(errors)

    if (!(Option(actionParams.getBoolean(ServerDeploymentField))
        .getOrElse(false) || Option(actionParams.getBoolean(DcDeploymentField)).getOrElse(false))) {
      errors.addError(ServerDeploymentField, "At least one of Server or DC deployment must be selected.")
    }

    if (isDeploymentPlan && !isUserNameGiven) {
      errors.addError(UserNameField, "A Bamboo user must be chosen if this task is part of a deployment project.")
    }

    getJiraApplicationLink match {
      case Some(appLink) =>
        checkJiraConnectionWhenUserGiven(actionParams, appLink, errors)
      case None =>
        val url = getBambooBaseUrl + "/plugins/servlet/applinks/listApplicationLinks"
        val text = getText("shipit.task.config.jira.applink.missing", url)
        errors.addErrorMessage(text)
    }
  }

  private def getText(i18NKey: String, params: Object*) =
    // we have to use the i18nBean instead of TextProvider because the latter is not able to use i18n args
    bambooAuthContext.getI18NBean.getText(i18NKey, Array(params: _*))

  private def checkJiraConnectionWhenUserGiven(
      actionParams: ActionParametersMap,
      applLink: ApplicationLink,
      errors: ErrorCollection
  ) = {
    Option(actionParams.getString(UserNameField)) match {
      case Some(userName) if userName.trim.nonEmpty => // user can be an empty string when passed from the task
        if (Option(bambooUserManager.getBambooUser(userName)).isEmpty) {
          errors.addErrorMessage(getText("shipit.task.config.user.unknown", userName))
        } else {
          checkJiraApplicationLink(applLink, userName, errors)
        }
      case _ => checkJiraApplicationLink(applLink, bambooAuthContext.getUserName, errors)
    }
  }

  private def checkJiraApplicationLink(
      jiraApplicationLink: ApplicationLink,
      userName: String,
      errors: ErrorCollection
  ) = {
    val jiraApplLinkCheck = impersonationService.runAsUser(
      userName,
      new Callable[Unit] {
        override def call(): Unit = {
          try {
            val requestFactory = jiraApplicationLink.createAuthenticatedRequestFactory()
            val jiraFacade = new JiraFacade(requestFactory)
            jiraFacade.getServerInfo(new ApplicationLinkResponseHandler[Unit]() {

              override def credentialsRequired(response: Response): Unit = {
                // FIXME we could provide a callback URL to getAuthorisationURI, but I don't know how to get the
                // URL of the current screen in Bamboo
                val reauthUrl = requestFactory.getAuthorisationURI().toString
                errors.addErrorMessage(getText("shipit.task.config.jira.credentials.error", reauthUrl))
              }

              override def handle(response: Response): Unit = {
                if (!response.isSuccessful) {
                  errors.addErrorMessage(getText("shipit.task.config.jira.connection.error", response.getStatusText))
                }
              }
            })
          } catch {
            case e: CredentialsRequiredException =>
              val reauthUrl = e.getAuthorisationURI().toString
              errors.addErrorMessage(getText("shipit.task.config.jira.credentials.error", reauthUrl))
          }
        }
      }
    )
    jiraApplLinkCheck.call()
  }

  private def getJiraProjects = getJiraApplicationLink match {
    case Some(applLink) =>
      try {
        val requestFactory = applLink.createAuthenticatedRequestFactory()
        val jiraFacade = new JiraFacade(requestFactory)
        jiraFacade.findAllProjects()
      } catch {
        case e: CredentialsRequiredException =>
          log.error("SHIPIT2MARKETPLACE: credentials are required for JIRA application link", e)
          List() // we show an error in the configuration dialog
      }
    case None => List() // we show an error in the configuration dialog
  }

  private def getJiraApplicationLink = jiraApplinksService.getJiraApplicationLinks.asScala.headOption

  private def getBambooBaseUrl = configAccessor.getAdministrationConfiguration.getBaseUrl

}
