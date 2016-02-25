package ch.mibex.bamboo.shipit.task

import java.util.concurrent.Callable
import java.util.{Map => JMap}

import ch.mibex.bamboo.shipit.jira.JiraFacade
import ch.mibex.bamboo.shipit.settings.AdminSettingsDao
import ch.mibex.bamboo.shipit.{Constants, Logging}
import com.atlassian.applinks.api.CredentialsRequiredException
import com.atlassian.bamboo.applinks.{ImpersonationService, JiraApplinksService}
import com.atlassian.bamboo.serialization.WhitelistedSerializable
import com.atlassian.bamboo.task.runtime.RuntimeTaskDefinition
import com.atlassian.bamboo.task.{RuntimeTaskDataProvider, TaskDefinition}
import com.atlassian.bamboo.user.BambooUserManager
import com.atlassian.bamboo.v2.build.CommonContext
import com.atlassian.bamboo.v2.build.trigger.DependencyTriggerReason
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.message.I18nResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success}


object ShipItTaskDataProvider {
  val ShipItVersionDescription = "shipItVersionDescription"
  val ShipItReleaseNotes = "shipItReleaseNotes"
  val MpacVendorName = "shipItVendorName"
  val MpacVendorPassword = "shipItVendorPassword"
}

case class JiraProjectInfo(projectKey: String, version: String, triggerUserName: String)

// this class is necessary as we do not have access to the DB and JIRA in a task which could be executed on a remote
// agent; therefore, we have to collect all data like release notes, vendor name and password and put it in the runtime
// task data which we can later access in the task
@Component
class ShipItTaskDataProvider @Autowired()(mpacCredentialsDao: AdminSettingsDao,
                                          @ComponentImport impersonationService: ImpersonationService,
                                          @ComponentImport bambooUserManager: BambooUserManager,
                                          @ComponentImport i18nResolver: I18nResolver,
                                          @ComponentImport jiraApplinksService: JiraApplinksService)
    extends RuntimeTaskDataProvider with Logging {

  import ShipItTaskDataProvider._
  import ShipItTaskConfigurator._
  import Constants._

  // we should not throw any exceptions from this method because otherwise the build cannot complete!
  override def populateRuntimeTaskData(taskDefinition: TaskDefinition,
                                       commonContext: CommonContext): JMap[String, String] = {
    val runtimeTaskData = new mutable.HashMap[String, String]()
    // I have to use the trigger reason key instead of pattern matching on the trigger reason type
    // because of https://jira.atlassian.com/browse/BAM-17061
    if (commonContext.getTriggerReason.getKey != Constants.JiraReleaseTriggerReasonKey
        && commonContext.getTriggerReason.getKey != DependencyTriggerReason.KEY) {
      log.info("SHIPIT2MARKETPLACE: Build was not triggered manually from JIRA. Will not run.")
      return runtimeTaskData.asJava
    }
    getJiraInfosFromBuildEnv(commonContext) match {
      case Some(projectInfos) =>
        appendReleaseInfosToRuntime(projectInfos, taskDefinition, commonContext, runtimeTaskData)
        appendMpacCredentialsToRuntime(runtimeTaskData, commonContext)
      case None =>
        addBuildError(commonContext, "shipit.task.jira.data.retrieval.error")
    }
    runtimeTaskData.asJava
  }

  private def appendMpacCredentialsToRuntime(runtimeTaskData: mutable.HashMap[String, String],
                                             commonContext: CommonContext) {
    mpacCredentialsDao.find() match {
      case Some(credentials) =>
        runtimeTaskData.put(MpacVendorName, credentials.getVendorUserName)
        runtimeTaskData.put(MpacVendorPassword, credentials.getVendorPassword)
      case None =>
        addBuildError(commonContext, "shipit.task.marketplace.credentials.missing.error")
    }
  }

  private def getJiraInfosFromBuildEnv(commonContext: CommonContext) = {
    val vars = commonContext.getVariableContext.getEffectiveVariables
    // the jira.* variables are only set when the release was triggered from JIRA
    for {
      projectKey <- Option(vars.get("jira.projectKey")) map { _.getValue }
      projectVersion <- Option(vars.get("jira.version")) map { _.getValue }
      triggerUserName <- Option(vars.get("jira.username")) map { _.getValue }
    } yield JiraProjectInfo(projectKey, projectVersion, triggerUserName)
  }

  private def appendReleaseInfosToRuntime(projectInfos: JiraProjectInfo,
                                          taskDefinition: TaskDefinition,
                                          commonContext: CommonContext,
                                          runtimeTaskData: mutable.HashMap[String, String]) {
    val userToCollectJiraInfo = getUserToCollectJiraInfos(projectInfos, taskDefinition).getOrElse({
      addBuildError(commonContext, "shipit.task.user.error")
      return
    })
    val appLink = jiraApplinksService.getJiraApplicationLinks.asScala.headOption.getOrElse({
      addBuildError(commonContext, "shipit.task.jira.applink.missing.error")
      return
    })
    // this is necessary as otherwise we do not have the permission to make REST calls over the application
    // link as there is no user set when this task provider is run
    val jiraJob = impersonationService.runAsUser(userToCollectJiraInfo, new Callable[Unit] {
      override def call(): Unit = {
        try {
          val requestFactory = appLink.createAuthenticatedRequestFactory()
          val jiraFacade = new JiraFacade(requestFactory)
          collectReleaseNotes(projectInfos, jiraFacade, commonContext, runtimeTaskData)
          collectReleaseSummary(projectInfos, jiraFacade, commonContext, runtimeTaskData)
        } catch {
          case e: CredentialsRequiredException =>
            val reauthUrl = e.getAuthorisationURI().toString
            addBuildError(commonContext, "shipit.task.jira.applink.reauth.necessary", Option(reauthUrl))
          case e: Exception =>
            addBuildError(commonContext, "shipit.task.jira.unknown.error", Option(e.getMessage))
        }
      }
    })
    jiraJob.call()
  }

  private def addBuildError(commonContext: CommonContext, i18nKey: String, param: Option[String] = None) = {
    val text = param.map(p => i18nResolver.getText(i18nKey, p)).getOrElse(i18nResolver.getText(i18nKey))
    commonContext.getCurrentResult.addBuildErrors(List(text).asJava)
  }

  private def getUserToCollectJiraInfos(projectInfos: JiraProjectInfo, taskDefinition: TaskDefinition) = {
    val usersToTry = List(
      Option(taskDefinition.getConfiguration.get(UserNameField)),
      Option(projectInfos.triggerUserName)
    )
    usersToTry collectFirst {
      case Some(x) if Option(bambooUserManager.getBambooUser(x)).isDefined => x
    }
  }

  private def collectReleaseNotes(projectInfos: JiraProjectInfo,
                                  jiraFacade: JiraFacade,
                                  commonContext: CommonContext,
                                  runtimeTaskData: mutable.HashMap[String, String]) {
    jiraFacade.collectReleaseNotes(projectInfos.projectKey, projectInfos.version) match {
      case Success(releaseNotes) if releaseNotes.length <= MaxReleaseNotesLength =>
        runtimeTaskData.put(ShipItReleaseNotes, releaseNotes)
      case Success(releaseNotes) if releaseNotes.length > MaxReleaseNotesLength =>
        val maxLengthParam = Option(MaxReleaseNotesLength.toString)
        addBuildError(commonContext, "shipit.task.jira.releasenotes.too.long", param=maxLengthParam)
      case Failure(e) =>
        addBuildError(commonContext, "shipit.task.jira.releasenotes.failed", param=Option(e.getMessage))
    }
  }

  private def collectReleaseSummary(projectInfos: JiraProjectInfo,
                                    jiraFacade: JiraFacade,
                                    commonContext: CommonContext,
                                    runtimeTaskData: mutable.HashMap[String, String]) {
    jiraFacade.collectReleaseSummary(projectInfos.projectKey, projectInfos.version) match {
      case Success(releaseSummary) if releaseSummary.length <= MaxReleaseSummaryLength =>
        runtimeTaskData.put(ShipItVersionDescription, releaseSummary)
      case Success(releaseSummary) if releaseSummary.length > MaxReleaseSummaryLength =>
        val maxLengthParam = Option(MaxReleaseSummaryLength.toString)
        addBuildError(commonContext, "shipit.task.jira.releasesummary.too.long", param=maxLengthParam)
      case Failure(e) =>
        addBuildError(commonContext, "shipit.task.jira.releasesummary.failed", param=Option(e.getMessage))
    }
  }

  override def processRuntimeTaskData(taskDefinition: TaskDefinition, context: CommonContext): Unit = {}

  override def processRuntimeTaskData(taskDefinition: RuntimeTaskDefinition, commonContext: CommonContext): Unit = {}

  override def createRuntimeTaskData(taskDefinition: RuntimeTaskDefinition, commonContext: CommonContext):
    JMap[String, WhitelistedSerializable] = Map[String, WhitelistedSerializable]().asJava

}
