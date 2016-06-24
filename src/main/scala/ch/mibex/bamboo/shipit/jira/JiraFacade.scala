package ch.mibex.bamboo.shipit.jira

import java.net.URLEncoder

import ch.mibex.bamboo.shipit.Constants.MaxReleaseNotesLength
import ch.mibex.bamboo.shipit.{Logging, Utils}
import com.atlassian.applinks.api.{ApplicationLinkRequestFactory, ApplicationLinkResponseHandler}
import com.atlassian.sal.api.net.Request.MethodType.GET
import com.atlassian.sal.api.net._
import spray.json._

import scala.beans.BeanProperty

// bean properties are necessary for accessing content in freemarker templates
case class JiraProject(@BeanProperty key: String, @BeanProperty name: String)

case class JiraIssue(key: String, summary: String, issueType: String)

case class JiraIssueFields(summary: String)

case class JiraProjectVersion(name: String, description: Option[String])

case class ReauthNecessary(url: String)


object JiraFacade {

  val issueTypeRenamings = Map(
    "Bug" -> "Bug fixes",
    "Feature" -> "New features",
    "New Feature" -> "New features",
    "Improvement" -> "Improvements"
  )

  def toReleaseNotes(issues: Seq[JiraIssue]): String = {
    var releaseNotes = issues.groupBy(_.issueType) map { case (issueType, issuesByType) =>
      issueTypeRenamings.getOrElse(issueType, issueType) + ":<p>" +
        issuesByType.map(i => s"* ${i.summary}").mkString("<p>")
    } mkString "<p><p>"
    if (releaseNotes.length > MaxReleaseNotesLength) {
      val abbreviation = "...<p>* ..."
      releaseNotes = releaseNotes.substring(0, MaxReleaseNotesLength - 1 - abbreviation.length) + abbreviation
    }
    releaseNotes
  }

}

class JiraFacade(requestFactory: ApplicationLinkRequestFactory) extends Logging {
  import JiraFacade._

  object ProjectVersionProtocol extends DefaultJsonProtocol {
    implicit val projectVersionFormat = jsonFormat2(JiraProjectVersion)
  }

  object ProjectProtocol extends DefaultJsonProtocol {
    implicit val projectFormat = jsonFormat2(JiraProject)
  }

  def getServerInfo[T](responseHandler: ApplicationLinkResponseHandler[T]): T = {
    val request = requestFactory.createRequest(Request.MethodType.GET, s"rest/api/2/serverInfo")
    request.execute(responseHandler)
  }

  // project=${jira.projectKey}+AND+status+in+(resolved,closed,done)+and+fixVersion=${jira.version}
  def collectReleaseNotes(projectKey: String, projectVersion: String, jql: String): String = {
    val fullJql = s"project=$projectKey AND fixVersion=$projectVersion " + (if (jql.nonEmpty) s" AND $jql" else "")
    val response = doGet(s"rest/api/2/search?jql=${URLEncoder.encode(fullJql, "UTF-8")}")
    val json = Utils.mapFromJson(response)
    val issues = for (issue <- json("issues").asInstanceOf[Seq[Map[String, Any]]])
      yield {
        val key = issue("key").asInstanceOf[String]
        val fields = issue("fields").asInstanceOf[Map[String, Any]]
        val summary = fields("summary").asInstanceOf[String]
        val issueType = fields("issuetype").asInstanceOf[Map[String, Any]]("name").asInstanceOf[String]
        JiraIssue(key = key, summary = summary, issueType = issueType)
      }
    toReleaseNotes(issues)
  }

  def getVersionDescription(projectKey: String, projectVersion: String): Option[String] = {
    val response = doGet(s"rest/api/2/project/$projectKey/versions")
    import ProjectVersionProtocol._
    response
      .parseJson
      .convertTo[List[JiraProjectVersion]]
      .find(_.name == projectVersion)
      .flatMap(_.description)
  }

  def findAllProjects(): List[JiraProject] = {
    val response = doGet("rest/api/2/project")
    import ProjectProtocol._
    response
      .parseJson
      .convertTo[List[JiraProject]]
  }

  private def doGet(url: String) = {
    val request = requestFactory.createRequest(GET, url)
    request.executeAndReturn(new ReturningResponseHandler[Response, String]() {
      override def handle(response: Response): String = {
        val responseBody = response.getResponseBodyAsString
        if (!response.isSuccessful) {
          throw new ResponseStatusException(s"${response.getStatusText}: $responseBody", response)
        }
        debug(s"SHIPIT2MARKETPLACE: response from $url: $responseBody")
        responseBody
      }
    })
  }

}
