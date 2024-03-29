package ch.mibex.bamboo.shipit.jira

import ch.mibex.bamboo.shipit.Constants.DefaultJql
import com.atlassian.applinks.api.{ApplicationLinkRequest, ApplicationLinkRequestFactory}
import com.atlassian.sal.api.net.Request.MethodType
import com.atlassian.sal.api.net.{Response, ReturningResponseHandler}
import org.junit.runner.RunWith
import org.scalatest.matchers.must.Matchers.{must, mustBe}
import org.scalatest.wordspec.AnyWordSpec
import org.mockito.Answers.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.mock
import org.mockito.Mockito.when
import org.scalatest.matchers.dsl.MatcherWords.include

import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class JiraFacadeTest extends AnyWordSpec {

  "collect release notes" should {

    "separate issues by type" in {
      val bug1 = JiraIssue(key = "TEST-1", summary = "Fixed authentication error", issueType = "Bug")
      val feature = JiraIssue(key = "TEST-2", summary = "New help system", issueType = "Feature")
      val improvement =
        JiraIssue(key = "TEST-3", summary = "Improved colors in the UI dialogs", issueType = "Improvement")
      val bug2 = JiraIssue(key = "TEST-4", summary = "Database error with Oracle 9.1", issueType = "Bug")
      JiraFacade.toReleaseNotes(List(bug1, feature, improvement, bug2)) must (
        include("Bug fixes:<p>* Fixed authentication error<p>* Database error with Oracle 9.1<p>") and
        include ("New features:<p>* New help system")      and
        include ("Improvements:<p>* Improved colors in the UI dialogs"))
    }

  }

  "collect release notes with one resolved feature and one fixed bug" should {

    //"Don't know how to mock generic type of ReturningResponseHandler with specs2/ScalaTest")
    "yield release notes with two entries for them" ignore new JiraReleaseNotesContext {
      val jiraFacade = new JiraFacade(applicationLinkRequestFactory)
      jiraFacade.collectReleaseNotes(projectKey, projectVersion, DefaultJql) mustBe
        """Bug fixes:<p>
          |* A problem which impairs or prevents the functions of the product<p>
          |<p>
          |Task:<p>
          |* Think about caching strategy""".stripMargin.replace("\n", "")
    }

  }

  "release notes with more than 1000 characters" should {

    "be shortened" in new JiraReleaseSummaryContext {
      val bug1 = JiraIssue(key = "TEST-1", summary = "Fixed authentication error", issueType = "Bug")
      val bug2 = JiraIssue(
        key = "TEST-4",
        summary = """ORA-00001: unique constraint (string.string) violated:
                    |An UPDATE or INSERT statement attempted to insert a duplicate key. For Trusted Oracle configured in
                    |DBMS MAC mode, you may see this message if a duplicate entry exists at a different level.""".stripMargin
          .replace("\n", ""),
        issueType = "Bug"
      )
      val bug3 = JiraIssue(
        key = "TEST-5",
        summary = """ORA-00017: session requested to set trace event:
                    |The current session was requested to set a trace event by another session.""".stripMargin
          .replace("\n", ""),
        issueType = "Bug"
      )
      val bug4 = JiraIssue(
        key = "TEST-6",
        summary =
          """ORA-00023: session references process private memory; cannot detach session:
            |A session may contain references to process memory (PGA) if it has an open network connection,
            |a very large context area, or operating system privileges. To allow the detach, it may be necessary
            |to close the session's database links and/or cursors. Detaching a session with operating system privileges
            |is always disallowed.""".stripMargin.replace("\n", ""),
        issueType = "Bug"
      )
      val bug5 = JiraIssue(
        key = "TEST-7",
        summary =
          """ORA-00031: session marked for kill:
            |The session specified in an ALTER SYSTEM KILL SESSION command cannot be killed immediately
            |(because it is rolling back or blocked on a network operation), but it has been marked for kill.
            |This means it will be killed as soon as possible after its current uninterruptable operation is done.""".stripMargin
            .replace("\n", ""),
        issueType = "Bug"
      )
      JiraFacade.toReleaseNotes(List(bug1, bug2, bug3, bug4, bug5)) mustBe
        """Bug fixes:<p>
          |* Fixed authentication error<p>
          |* ORA-00001: unique constraint (string.string) violated:
          |An UPDATE or INSERT statement attempted to insert a duplicate key. For Trusted Oracle configured in
          |DBMS MAC mode, you may see this message if a duplicate entry exists at a different level.<p>
          |* ORA-00017: session requested to set trace event:
          |The current session was requested to set a trace event by another session.<p>
          |* ORA-00023: session references process private memory; cannot detach session:
          |A session may contain references to process memory (PGA) if it has an open network connection,
          |a very large context area, or operating system privileges. To allow the detach, it may be necessary
          |to close the session's database links and/or cursors. Detaching a session with operating system privileges
          |is always disallowed.<p>
          |* ORA-00031: session marked for kill:
          |The session specified in an ALTER SYSTEM KILL SESSION command cannot be killed immediately
          |(because it is rolling back or blocked on ...<p>
          |* ...""".stripMargin.replace("\n", "")
    }

  }

  "collect release summary with two versions" should {

    //Don't know how to mock generic type of ReturningResponseHandler with specs2/ScalaTest
    "yield the summary for the version we are looking for" ignore new JiraReleaseSummaryContext {
      val jiraFacade = new JiraFacade(applicationLinkRequestFactory)
      jiraFacade.getVersionDescription(projectKey, "1.0.1") mustBe Some("Datacenter compatibility")
    }
  }

  trait JiraReleaseNotesContext {
    val applicationLinkRequestFactory = mock(classOf[ApplicationLinkRequestFactory])
    val projectKey = "SHIPIT"
    val projectVersion = "1.0.0"
    val url =
      "rest/api/2/search?jql=project%3DSHIPIT+AND+fixVersion%3D1.0.0++AND+status+in+%28resolved%2Cclosed%2Cdone%29"
    val applicationLinkRequest = mock(classOf[ApplicationLinkRequest])
    when(applicationLinkRequestFactory.createRequest(MethodType.GET, url)).thenReturn(applicationLinkRequest)
    val responseHandler = mock(classOf[ReturningResponseHandler[Response, String]])
    when(applicationLinkRequest.executeAndReturn(responseHandler)).thenReturn(
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
        |}""".stripMargin)
  }

  trait JiraReleaseSummaryContext {
    val applicationLinkRequestFactory = mock(classOf[ApplicationLinkRequestFactory])
    val projectKey = "SHIPIT"
    val applicationLinkRequest = mock(classOf[ApplicationLinkRequest])
    val responseHandler = mock(classOf[ReturningResponseHandler[Response, String]])
    val url = "rest/api/2/project/SHIPIT/versions"
    when(applicationLinkRequestFactory.createRequest(MethodType.GET, url)).thenReturn(applicationLinkRequest)
    when(applicationLinkRequest.executeAndReturn(responseHandler)).thenReturn(
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
        |]""".stripMargin)
  }

}