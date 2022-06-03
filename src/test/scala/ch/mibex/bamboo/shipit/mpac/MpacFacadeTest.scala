package ch.mibex.bamboo.shipit.mpac

import com.atlassian.marketplace.client.MarketplaceClient
import com.atlassian.marketplace.client.api.AddonQuery
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.Mockito.{when, withSettings}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers.mustBe

import java.util.Optional
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class MpacFacadeTest extends AnyWordSpec {

  "find plug-in by key" should {

    "yield none if unknown" in new PluginNotFoundContext {
      client.findPlugin("UNKNOWN PLUGIN") mustBe (Right(None))
    }

  }

  trait PluginNotFoundContext {
    val mpac = mock(classOf[MarketplaceClient], RETURNS_DEEP_STUBS)
    // val plugin = mock[Plugin] // we cannot mock Plugin as it is final
    when(mpac.addons().safeGetByKey(anyString, any[AddonQuery])).thenReturn(Optional.empty())
    val client = new MpacFacade(mpac)
  }

}
