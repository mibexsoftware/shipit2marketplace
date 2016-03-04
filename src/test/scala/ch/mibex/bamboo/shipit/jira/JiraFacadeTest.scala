package ch.mibex.bamboo.shipit.jira

import com.atlassian.applinks.api.{ApplicationLinkRequest, ApplicationLinkRequestFactory}
import com.atlassian.sal.api.net.Request.MethodType._
import org.junit.runner.RunWith
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.Scope


@RunWith(classOf[JUnitRunner])
class JiraFacadeTest extends Specification with Mockito {

  "collect release notes" should {

    "separate issues by type" in {
      val bug1 = JiraIssue(key = "TEST-1", summary = "Fixed authentication error", issueType = "Bug")
      val feature = JiraIssue(key = "TEST-2", summary = "New help system", issueType = "Feature")
      val improvement = JiraIssue(key = "TEST-3", summary = "Improved colors in the UI dialogs", issueType = "Improvement")
      val bug2 = JiraIssue(key = "TEST-4", summary = "Database error with Oracle 9.1", issueType = "Bug")
      JiraFacade.toReleaseNotes(List(bug1, feature, improvement, bug2)) must_==
        """Bug fixes:<br>* Fixed authentication error<br>* Database error with Oracle 9.1
          |<br><br>New features:<br>* New help system
          |<br><br>Improvements:<br>* Improved colors in the UI dialogs""".stripMargin.replaceAll("\n", "")
    }

  }

  "collect release notes with one resolved feature and one fixed bug" should {

    "yield release notes with two entries for them" in new JiraReleaseNotesContext {
      val jiraFacade = new JiraFacade(applicationLinkRequestFactory)
      jiraFacade.collectReleaseNotes(projectKey, projectVersion) must_==
        """* Think about caching strategy<br>* Bug fix: A problem which impairs or prevents the functions of the product"""
    }

  }

  "collect release summary with two versions" should {

    "yield the summary for the version we are looking for" in new JiraReleaseSummaryContext {
      val jiraFacade = new JiraFacade(applicationLinkRequestFactory)
      jiraFacade.collectReleaseSummary(projectKey, "1.0.1") must beSome("Datacenter compatibility")
    }

  }

  class JiraReleaseNotesContext extends Scope {
    val applicationLinkRequestFactory = mock[ApplicationLinkRequestFactory]
    val projectKey = "SHIPIT"
    val projectVersion = "1.0.0"
    val jql =  s"project=$projectKey+AND+status+in+(resolved,closed,done)+and+fixVersion=$projectVersion"
    val applicationLinkRequest = mock[ApplicationLinkRequest]
    applicationLinkRequestFactory.createRequest(GET, s"rest/api/2/search?jql=$jql") returns applicationLinkRequest
    applicationLinkRequest.execute() returns
      """{
        |  "expand": "schema,names",
        |  "startAt": 0,
        |  "maxResults": 50,
        |  "total": 4,
        |  "issues": [
        |    {
        |      "id": "11451",
        |      "self": "http://localhost/jira/rest/api/2/issue/11451",
        |      "key": "SHIPIT-1",
        |      "fields": {
        |        "issuetype": {
        |          "self": "http://localhost/jira/rest/api/2/issuetype/3",
        |          "id": "3",
        |          "description": "A task that needs to be done.",
        |          "iconUrl": "https://localhost/jira/secure/viewavatar?size=xsmall&avatarId=10718&avatarType=issuetype",
        |          "name": "Task",
        |          "subtask": false,
        |          "avatarId": 10718
        |        },
        |        "summary": "Think about caching strategy"
        |      }
        |    },
        |    {
        |      "id": "11452",
        |      "self": "http://localhost/jira/rest/api/2/issue/11451",
        |      "key": "SHIPIT-2",
        |      "fields": {
        |        "issuetype": {
        |          "self": "http://localhost/jira/rest/api/2/issuetype/1",
        |          "id": "3",
        |          "description": "A task that needs to be done.",
        |          "iconUrl": "https://localhost/jira/secure/viewavatar?size=xsmall&avatarId=10718&avatarType=issuetype",
        |          "name": "Bug",
        |          "subtask": false,
        |          "avatarId": 10703
        |        },
        |        "summary": "A problem which impairs or prevents the functions of the product"
        |      }
        |    }
        |  ]
        |}""".stripMargin
  }

  class JiraReleaseSummaryContext extends Scope {
    val applicationLinkRequestFactory = mock[ApplicationLinkRequestFactory]
    val projectKey = "SHIPIT"
    val applicationLinkRequest = mock[ApplicationLinkRequest]
    applicationLinkRequestFactory.createRequest(GET, s"rest/api/2/project/$projectKey/versions") returns applicationLinkRequest
    applicationLinkRequest.execute() returns
      """[
        |  {
        |    "self": "http://localhost/jira/rest/api/2/version/10601",
        |    "id": "10601",
        |    "name": "1.0.0",
        |    "archived": false,
        |    "released": true,
        |    "releaseDate": "2014-11-02",
        |    "userReleaseDate": "02/Nov/14",
        |    "projectId": 10401
        |  },
        |  {
        |    "self": "http://localhost/jira/rest/api/2/version/10602",
        |    "id": "10602",
        |    "description": "Datacenter compatibility",
        |    "name": "1.0.1",
        |    "archived": false,
        |    "released": true,
        |    "releaseDate": "2014-11-12",
        |    "userReleaseDate": "12/Nov/14",
        |    "projectId": 10401
        |  }
        |]""".stripMargin
  }

}