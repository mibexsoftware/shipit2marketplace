package ch.mibex.bamboo.shipit.jira

import ch.mibex.bamboo.shipit.{Logging, Utils}
import com.atlassian.applinks.api.{ApplicationLinkRequestFactory, ApplicationLinkResponseHandler}
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Request.MethodType.GET
import spray.json._

case class JiraIssue(key: String, summary: String, issueType: String)

case class JiraIssueFields(summary: String)

case class JiraProjectVersion(name: String, description: Option[String])

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
    val response = request.execute()
    log.debug(s"SHIPIT2MARKETPLACE: response from rest/api/2/project/$projectKey/versions: $response")
    val json = Utils.mapFromJson(response)
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

  def collectReleaseSummary(projectKey: String, projectVersion: String): Option[String] = {
    val request = requestFactory.createRequest(GET, s"rest/api/2/project/$projectKey/versions")
    val response = request.execute()
    log.debug(s"SHIPIT2MARKETPLACE: response from rest/api/2/project/$projectKey/versions: $response")
    import ProjectVersionProtocol._
    response
      .parseJson
      .convertTo[List[JiraProjectVersion]]
      .find(_.name == projectVersion)
      .flatMap(_.description)
  }

}
