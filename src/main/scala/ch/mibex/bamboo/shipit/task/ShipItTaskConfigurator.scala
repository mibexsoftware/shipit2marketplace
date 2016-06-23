package ch.mibex.bamboo.shipit.task

import java.lang.{Boolean => JBoolean}
import java.util.concurrent.Callable
import java.util.{Map => JMap}

import ch.mibex.bamboo.shipit.Logging
import ch.mibex.bamboo.shipit.jira.JiraFacade
import ch.mibex.bamboo.shipit.mpac.MpacError.MpacAuthenticationError
import ch.mibex.bamboo.shipit.mpac.{MpacCredentials, MpacFacade}
import ch.mibex.bamboo.shipit.settings.AdminSettingsDao
import ch.mibex.bamboo.shipit.task.artifacts.{DownloaderArtifactCollector, SubscribedArtifactCollector}
import com.atlassian.applinks.api.{ApplicationLink, ApplicationLinkResponseHandler, CredentialsRequiredException}
import com.atlassian.bamboo.applinks.{ImpersonationService, JiraApplinksService}
import com.atlassian.bamboo.collections.ActionParametersMap
import com.atlassian.bamboo.configuration.AdministrationConfigurationAccessor
import com.atlassian.bamboo.deployments.DeploymentTaskContextHelper
import com.atlassian.bamboo.plan.PlanManager
import com.atlassian.bamboo.plan.cache.ImmutableJob
import com.atlassian.bamboo.security.EncryptionService
import com.atlassian.bamboo.task._
import com.atlassian.bamboo.user.{BambooAuthenticationContext, BambooUserManager}
import com.atlassian.bamboo.util.Narrow
import com.atlassian.bamboo.utils.error.ErrorCollection
import com.atlassian.bamboo.variable.VariableDefinitionManager
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.net.Response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._

object ShipItTaskConfigurator {
  final val IsPublicVersionField = "publicVersion"
  final val UserNameField = "bambooUserId"
  final val DeduceBuildNrField = "deduceBuildNrFromPluginVersion"
  final val ArtifactToDeployKeyField = "artifactToDeployKey"
  final val AllArtifactsToDeployList = "allArtifactsToDeploy"
  final val IsJiraReleasePanelModeField = "jiraReleasePanelDeploymentOnly"
  final val JiraProjectKeyField = "jiraProjectKey"
  final val JiraProjectList = "jiraProjects"
}

@Component
class ShipItTaskConfigurator @Autowired()(@ComponentImport encryptionService: EncryptionService,
                                          @ComponentImport jiraApplinksService: JiraApplinksService,
                                          @ComponentImport impersonationService: ImpersonationService,
                                          @ComponentImport configAccessor: AdministrationConfigurationAccessor,
                                          @ComponentImport bambooUserManager: BambooUserManager,
                                          @ComponentImport variableDefinitionManager: VariableDefinitionManager,
                                          @ComponentImport planManager: PlanManager,
                                          @ComponentImport bambooAuthContext: BambooAuthenticationContext,
                                          mpacCredentialsDao: AdminSettingsDao,
                                          downloaderArtifactCollector: DownloaderArtifactCollector,
                                          subscribedArtifactCollector: SubscribedArtifactCollector)
  extends AbstractTaskConfigurator with Logging {

  import ShipItTaskConfigurator._

  private final val TaskFields = Set(
    IsPublicVersionField,
    UserNameField,
    DeduceBuildNrField,
    ArtifactToDeployKeyField,
    IsJiraReleasePanelModeField,
    JiraProjectKeyField
  )

  override def populateContextForEdit(context: JMap[String, Object], taskDefinition: TaskDefinition): Unit = {
    context.put(AllArtifactsToDeployList, collectArtifactsForUiList(context))
    context.put(UserNameField, taskDefinition.getConfiguration.get(UserNameField))
    context.put(IsPublicVersionField, taskDefinition.getConfiguration.get(IsPublicVersionField))
    context.put(DeduceBuildNrField, taskDefinition.getConfiguration.get(DeduceBuildNrField))
    context.put(ArtifactToDeployKeyField, taskDefinition.getConfiguration.get(ArtifactToDeployKeyField))
//    val d = taskDefinition.getConfiguration.get(IsJiraReleasePanelModeField)
//    log.error(">>>> CURRENT VALUE : " + d)
    context.put(JiraProjectKeyField, taskDefinition.getConfiguration.get(JiraProjectKeyField))
    context.put(JiraProjectList, getJiraProjects)
//    taskConfiguratorHelper.populateContextWithConfiguration(context, taskDefinition, TaskFields)
    // this is for old task configurations where it was not possible to choose a sonar server configuration
    if (Option(context.get(IsJiraReleasePanelModeField)).isEmpty) {
      context.put(IsJiraReleasePanelModeField, JBoolean.TRUE)
    }
  }

  override def populateContextForCreate(context: JMap[String, AnyRef]): Unit = {
    context.put(AllArtifactsToDeployList, collectArtifactsForUiList(context))
    context.put(UserNameField, "")
    context.put(IsJiraReleasePanelModeField, JBoolean.TRUE)
    context.put(IsPublicVersionField, JBoolean.TRUE)
    context.put(DeduceBuildNrField, JBoolean.TRUE)
    context.put(ArtifactToDeployKeyField, "")
    context.put(JiraProjectKeyField, "")
    context.put(JiraProjectList, getJiraProjects)
  }

  private def collectArtifactsForUiList(taskContext: JMap[String, Object]) =
    Option(Narrow.reinterpret(TaskContextHelper.getPlan(taskContext), classOf[ImmutableJob])) match {
      case Some(job) =>
        val taskDefinitions = job.getBuildDefinition.getTaskDefinitions
        val subscriptions = subscribedArtifactCollector.buildArtifactUiList(job)
        subscriptions ++ downloaderArtifactCollector.buildArtifactUiList(taskDefinitions.asScala)
      case None => // it is a deployment project
        val env = DeploymentTaskContextHelper.getEnvironment(taskContext)
        downloaderArtifactCollector.buildArtifactUiList(env.getTaskDefinitions.asScala)
    }

  override def generateTaskConfigMap(actionParams: ActionParametersMap,
                                     taskDefinition: TaskDefinition): JMap[String, String] = {
    val config = super.generateTaskConfigMap(actionParams, taskDefinition)
    config.put(UserNameField, actionParams.getString(UserNameField))
    config.put(IsJiraReleasePanelModeField, actionParams.getBoolean(IsJiraReleasePanelModeField).toString)
    config.put(IsPublicVersionField, actionParams.getBoolean(IsPublicVersionField).toString)
    config.put(DeduceBuildNrField, actionParams.getBoolean(DeduceBuildNrField).toString)
    config.put(ArtifactToDeployKeyField, actionParams.getString(ArtifactToDeployKeyField))
    config.put(JiraProjectKeyField, actionParams.getString(JiraProjectKeyField))
    config
  }

  // we cannot create plan variables in populateContextForEdit or populateContextForCreate because the XSRF checks
  // do not allow us to do this (mutative operation in GET request error!):
  //     taskDefinition.getConfiguration.get("plan") match {
  //      case job: Job => createPlanVariablesIfNecessary(job.getParent)
  //      case _ => // deployment project
  //        actionParams.get("relatedPlan") match {
  //          case plan: ImmutableChain =>
  //            createPlanVariablesIfNecessary(planManager.getPlanByKey(plan.getPlanKey, classOf[Chain]))
  //          case _ =>
  //        }
  //    }
  // and in generateTaskConfigMap we do not know the plan key; so the user has to create these plan variables
  // manually at the moment
  //  private def createPlanVariablesIfNecessary(chain: Chain) {
  //    val variableFactory = new VariableDefinitionFactoryImpl()
  //    val emptyValue = null
  //    if (Option(variableDefinitionManager.getPlanVariableByKey(chain, BambooBuildNrVariableKey)).isEmpty) {
  //      val buildNrGlobalVar = variableFactory.createPlanVariable(chain, BambooBuildNrVariableKey, emptyValue)
  //      variableDefinitionManager.saveVariableDefinition(buildNrGlobalVar)
  //    }
  //    if (Option(variableDefinitionManager.getPlanVariableByKey(chain, BambooReleaseSummaryVariableKey)).isEmpty) {
  //      val releaseSummaryVar = variableFactory.createPlanVariable(chain, BambooReleaseSummaryVariableKey, emptyValue)
  //      variableDefinitionManager.saveVariableDefinition(releaseSummaryVar)
  //    }
  //    if (Option(variableDefinitionManager.getPlanVariableByKey(chain, BambooReleaseNotesVariableKey)).isEmpty) {
  //      val releaseNotesVar = variableFactory.createPlanVariable(chain, BambooReleaseNotesVariableKey, emptyValue)
  //      variableDefinitionManager.saveVariableDefinition(releaseNotesVar)
  //    }
  //  }

  override def populateContextForView(context: JMap[String, AnyRef], taskDefinition: TaskDefinition): Unit = {
    context.put(UserNameField, taskDefinition.getConfiguration.get(UserNameField))
    context.put(DeduceBuildNrField, taskDefinition.getConfiguration.get(DeduceBuildNrField))
    context.put(IsJiraReleasePanelModeField, taskDefinition.getConfiguration.get(IsPublicVersionField))
    context.put(IsJiraReleasePanelModeField, taskDefinition.getConfiguration.get(IsPublicVersionField))
    context.put(ArtifactToDeployKeyField, taskDefinition.getConfiguration.get(IsPublicVersionField))
    context.put(ArtifactToDeployKeyField, taskDefinition.getConfiguration.get(ArtifactToDeployKeyField))
  }
  private def checkMpacCredentials(actionParams: ActionParametersMap, errors: ErrorCollection) {
    mpacCredentialsDao.find() match {
      case Some(credentials) =>
        val password = encryptionService.decrypt(credentials.getVendorPassword)
        val vendorCredentials = new MpacCredentials(credentials.getVendorUserName, password)
        MpacFacade.withMpac(vendorCredentials) { mpac =>
          mpac.checkCredentials() foreach {
            case error@MpacAuthenticationError() =>
              errors.addErrorMessage(getText(error.i18n, getSettingsUrl))
            case error =>
              errors.addErrorMessage(getText(error.i18n))
          }
        }
      case None =>
        errors.addErrorMessage(getText("shipit.task.config.vendor.credentials.missing", getSettingsUrl))
    }
  }

  override def validate(actionParams: ActionParametersMap, errors: ErrorCollection): Unit = {
    if (Option(actionParams.getString(ArtifactToDeployKeyField)).getOrElse("").trim.isEmpty) {
      errors.addError(ArtifactToDeployKeyField, "Artifact must not be empty.")
    }
    if (!actionParams.getBoolean(IsJiraReleasePanelModeField)
      && Option(actionParams.getString(JiraProjectKeyField)).getOrElse("").trim.isEmpty) {
      errors.addError(JiraProjectKeyField, "JIRA project must not be empty when not using JIRA release panel mode.")
    }
    checkMpacCredentials(actionParams, errors)

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

  private def checkJiraConnectionWhenUserGiven(actionParams: ActionParametersMap,
                                               applLink: ApplicationLink,
                                               errors: ErrorCollection) {
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

  private def getSettingsUrl = s"$getBambooBaseUrl/admin/shipit2mpac/viewShip2MpacConfiguration.action"

  private def checkJiraApplicationLink(jiraApplicationLink: ApplicationLink,
                                       userName: String,
                                       errors: ErrorCollection) {
    val jiraApplLinkCheck = impersonationService.runAsUser(userName, new Callable[Unit] {
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
    })
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
