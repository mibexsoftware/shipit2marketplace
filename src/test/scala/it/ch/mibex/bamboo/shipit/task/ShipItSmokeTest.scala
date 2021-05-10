package it.ch.mibex.bamboo.shipit.task

import java.util

import ch.mibex.bamboo.shipit.task.ShipItTaskConfigurator
import com.atlassian.bamboo.pageobjects.BambooTestedProduct
import com.atlassian.bamboo.pageobjects.elements.TextElement
import com.atlassian.bamboo.pageobjects.pages.admin.AbstractBambooAdminPage
import com.atlassian.bamboo.pageobjects.pages.plan.configuration.JobTaskConfigurationPage
import com.atlassian.bamboo.pageobjects.pages.tasks.TaskComponent
import com.atlassian.pageobjects.TestedProductFactory
import com.atlassian.pageobjects.elements.query.Poller
import com.atlassian.pageobjects.elements.{ElementBy, PageElement}
import com.google.common.collect.Maps
import org.junit.runner.RunWith
import org.openqa.selenium.WebElement
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

// see https://ecosystem.atlassian.net/wiki/display/SELENIUM/Building+Page+Objects+with+Atlassian+Selenium
class AdminSettingsComponent extends AbstractBambooAdminPage {

  @ElementBy(name = "save")
  var submit: PageElement = _

  @ElementBy(name = "vendorName")
  var vendorNameText: TextElement = _

  @ElementBy(name = "vendorPassword")
  var vendorPasswordText: TextElement = _

  @ElementBy(id = "updateShip2MpacConfiguration")
  var configPanel: PageElement = _

  @ElementBy(cssSelector = ".aui-message.error")
  var errorWhileSaving: PageElement = _

  override def indicator(): PageElement = configPanel

  override def getUrl: String = "/admin/shipit2mpac/viewShip2MpacConfiguration.action"

  def submitAndExpectValidationError() = {
    submit.click()
    Poller.waitUntilTrue(errorWhileSaving.timed().isVisible)
    this
  }

  def enterValues(vendorName: String, vendorPassword: String): Unit = {
    vendorNameText.setText(vendorName)
    vendorPasswordText.setText(vendorName)
  }

}

class ShipItTaskConfigComponent extends TaskComponent {
  @ElementBy(id = ShipItTaskConfigurator.IsPublicVersionField)
  var isPublicVersionField: WebElement = null

  override def updateTaskDetails(map: util.Map[String, String]): Unit = {
    isPublicVersionField.click()
  }

}

@RunWith(classOf[JUnitRunner])
class ShiptItSmokeTest extends Specification {

  "when plug-in is installed" should {

    "admin settings should be available" in {
      val bamboo = TestedProductFactory.create(classOf[BambooTestedProduct])
      val dashboard = bamboo.gotoLoginPage().loginAsSysAdmin()
      assert(dashboard.isLoggedIn)
      val settings = bamboo.visit(classOf[AdminSettingsComponent])
      settings.enterValues("test", "test")
      settings.submitAndExpectValidationError()
      ok
    }

    "the task configuration screen should be avilable" in {
      val bamboo = TestedProductFactory.create(classOf[BambooTestedProduct])
      val dashboard = bamboo.gotoLoginPage().loginAsSysAdmin()
      assert(dashboard.isLoggedIn)

//      val build = new TestBuildDetails("Smoke Test", "SMOKE", "SAND-JOB1", "Sandbox - Default Job")
//      val job = new TestJobDetails(build, "JOB1", "Default Job")

      val configForm = bamboo.getPageBinder.navigateToAndBind(classOf[JobTaskConfigurationPage], null)
      configForm.addNewTask("ShipIt to Marketplace", classOf[ShipItTaskConfigComponent], "", Maps.newHashMap())
      ok
    }

  }

}
