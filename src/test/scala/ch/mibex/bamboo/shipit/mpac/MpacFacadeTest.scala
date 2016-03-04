package ch.mibex.bamboo.shipit.mpac

import com.atlassian.marketplace.client.MarketplaceClient
import com.atlassian.marketplace.client.api.PluginDetailQuery
import com.atlassian.marketplace.client.model.Plugin
import org.junit.runner.RunWith
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.Scope
import org.mockito.Mockito.withSettings
import org.mockito.Answers._


@RunWith(classOf[JUnitRunner])
class MpacFacadeTest extends Specification with Mockito {

  "find plug-in by key" should {

    "yield none if unknown" in new PluginNotFoundContext {
      client.findPlugin("UNKNOWN PLUGIN") must beRight(None)
    }

  }

  class PluginNotFoundContext extends Scope {
    val mpac = mock[MarketplaceClient](withSettings.defaultAnswer(RETURNS_DEEP_STUBS.get))
    // val plugin = mock[Plugin] // we cannot mock Plugin as it is final
    mpac.plugins().get(any[PluginDetailQuery]) returns com.atlassian.upm.api.util.Option.none()
    val client = new MpacFacade(mpac)
  }

}