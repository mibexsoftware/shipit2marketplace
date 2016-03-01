package ch.mibex.bamboo.shipit.jira

import ch.mibex.bamboo.shipit.{Logging, Utils}
import com.atlassian.applinks.api.{ApplicationLinkRequestFactory, ApplicationLinkResponseHandler}
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Request.MethodType.GET
import spray.json._

case class JiraIssue(key: String, summary: String, issueType: String)

case class JiraIssueFields(summary: String)

case class JiraProjectVersion(name: String, description: String)

case class ReauthNecessary(url: String)


class JiraFacade(requestFactory: ApplicationLinkRequestFactory) extends Logging {

  object ProjectVersionProtocol extends DefaultJsonProtocol {
    implicit val projectVersionFormat = jsonFormat2(JiraProjectVersion)
  }

  def getServerInfo[T](responseHandler: ApplicationLinkResponseHandler[T]): T = {
    val request = requestFactory.createRequest(Request.MethodType.GET, s"rest/api/2/serverInfo")
    request.execute(responseHandler)
  }

  def collectReleaseNotes(projectKey: String, projectVersion: String): String = {
    val jql = s"project=$projectKey+AND+status+in+(resolved,closed,done)+and+fixVersion=$projectVersion"
    val request = requestFactory.createRequest(GET, s"rest/api/2/search?jql=$jql")
    val r = request.execute()
    val json = Utils.mapFromJson(r)
    val issues = for (issue <- json("issues").asInstanceOf[Seq[Map[String, Any]]])
      yield {
        val key = issue("key").asInstanceOf[String]
        val fields = issue("fields").asInstanceOf[Map[String, Any]]
        val summary = fields("summary").asInstanceOf[String]
        val issueType = fields("issuetype").asInstanceOf[Map[String, Any]]("name").asInstanceOf[String]
        JiraIssue(key = key, summary = summary, issueType = issueType)
      }
    val releaseNotes = issues map { i =>
      i.issueType match {
        case "Bug" => s"* Bug fix: ${i.summary}"
        case _ => s"* ${i.summary}"
      }
    } mkString "<br>"
    releaseNotes
  }

  def collectReleaseSummary(projectKey: String, projectVersion: String): String = {
    val request = requestFactory.createRequest(GET, s"rest/api/2/project/$projectKey/versions")
    val response = request.execute()
    import ProjectVersionProtocol._
    val jiraVersion = response
      .parseJson
      .convertTo[List[JiraProjectVersion]]
      .find(_.name == projectVersion)
      .getOrElse(
        throw new IllegalArgumentException(s"No version $projectVersion found in project with key $projectKey")
      )
    jiraVersion.description
  }

}
