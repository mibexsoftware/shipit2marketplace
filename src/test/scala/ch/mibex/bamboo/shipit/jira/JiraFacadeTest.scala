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
        """Bug fixes:
          |* Fixed authentication error
          |* Database error with Oracle 9.1
          |
          |New features:
          |* New help system
          |
          |Improvements:
          |* Improved colors in the UI dialogs""".stripMargin
    }

  }

  "collect release notes with one resolved feature and one fixed bug" should {

    "yield release notes with two entries for them" in new JiraReleaseNotesContext {
      val jiraFacade = new JiraFacade(applicationLinkRequestFactory)
      jiraFacade.collectReleaseNotes(projectKey, projectVersion) must_==
        """Bug fixes:
          |* A problem which impairs or prevents the functions of the product
          |
          |Task:
          |* Think about caching strategy""".stripMargin
    }

  }

  "release notes with more than 1000 characters" should {

    "be shortened" in new JiraReleaseSummaryContext {
      val bug1 = JiraIssue(key = "TEST-1", summary = "Fixed authentication error", issueType = "Bug")
      val bug2 = JiraIssue(
        key = "TEST-4",
        summary = """ORA-00001: unique constraint (string.string) violated:
                    |An UPDATE or INSERT statement attempted to insert a duplicate key. For Trusted Oracle configured in
                    |DBMS MAC mode, you may see this message if a duplicate entry exists at a different level.""".stripMargin,
        issueType = "Bug"
      )
      val bug3 = JiraIssue(
        key = "TEST-5",
        summary = """ORA-00017: session requested to set trace event:
                    |The current session was requested to set a trace event by another session.""".stripMargin,
        issueType = "Bug"
      )
      val bug4 = JiraIssue(
        key = "TEST-6",
        summary =
          """ORA-00023: session references process private memory; cannot detach session:
            |A session may contain references to process memory (PGA) if it has an open network connection,
            |a very large context area, or operating system privileges. To allow the detach, it may be necessary
            |to close the session's database links and/or cursors. Detaching a session with operating system privileges
            |is always disallowed.""".stripMargin,
        issueType = "Bug"
      )
      val bug5 = JiraIssue(
        key = "TEST-7",
        summary =
          """ORA-00031: session marked for kill:
            |The session specified in an ALTER SYSTEM KILL SESSION command cannot be killed immediately
            |(because it is rolling back or blocked on a network operation), but it has been marked for kill.
            |This means it will be killed as soon as possible after its current uninterruptable operation is done.""".stripMargin,
        issueType = "Bug"
      )
      JiraFacade.toReleaseNotes(List(bug1, bug2, bug3, bug4, bug5)) must_==
        """Bug fixes:
          |* Fixed authentication error
          |* ORA-00001: unique constraint (string.string) violated:
          |An UPDATE or INSERT statement attempted to insert a duplicate key. For Trusted Oracle configured in
          |DBMS MAC mode, you may see this message if a duplicate entry exists at a different level.
          |* ORA-00017: session requested to set trace event:
          |The current session was requested to set a trace event by another session.
          |* ORA-00023: session references process private memory; cannot detach session:
          |A session may contain references to process memory (PGA) if it has an open network connection,
          |a very large context area, or operating system privileges. To allow the detach, it may be necessary
          |to close the session's database links and/or cursors. Detaching a session with operating system privileges
          |is always disallowed.
          |* ORA-00031: session marked for kill:
          |The session specified in an ALTER SYSTEM KILL SESSION command cannot be killed immediately
          |(because it is rolling back or blocked on a n...
          |* ...""".stripMargin
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