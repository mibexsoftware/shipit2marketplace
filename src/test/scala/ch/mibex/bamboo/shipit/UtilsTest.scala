package ch.mibex.bamboo.shipit

import org.junit.runner.RunWith
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers.mustBe

import java.io.File
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UtilsTest extends AnyWordSpec {

  "Utils" should {

    "yield a build number padded with zeroes" in {
      Utils.toBuildNumber("1.2.3") mustBe 100200300
      Utils.toBuildNumber("11.0.0") mustBe 110000000
      Utils.toBuildNumber("11.1.1") mustBe 110100100
      Utils.toBuildNumber("11.1.10") mustBe 110101000
      Utils.toBuildNumber("0.0.1") mustBe 100
      Utils.toBuildNumber("1.0") mustBe 100000000
      Utils.toBuildNumber("2.0") mustBe 200000000
      Utils.toBuildNumber("1.2.3-SNAPSHOT") mustBe 100200300
      Utils.toBuildNumber("5.2.9") mustBe 500200900
      Utils.toBuildNumber("5.2.10") mustBe 500201000
      Utils.toBuildNumber("5.200.1") mustBe 520000100
      Utils.toBuildNumber("5.20.1") mustBe 502000100
      Utils.toBuildNumber("5.20.10") mustBe 502001000
      Utils.toBuildNumber("5.200.10") mustBe 520001000
      Utils.toBuildNumber("5.200.100") mustBe 520010000
      Utils.toBuildNumber("5.2.100") mustBe 500210000
      Utils.toBuildNumber("5.2.99") mustBe 500209900
      Utils.toBuildNumber("5.20.99") mustBe 502009900
    }

    "yield a short build number padded with zeroes" in {
//      Utils.toBuildNumber("1.2.3", shortVersion = true) mustBe 1200300
      Utils.toBuildNumber("5.0.0", shortVersion = true) mustBe 5000000
      Utils.toBuildNumber("5.15.0", shortVersion = true) mustBe 5015000
    }

    "convert a Scala map to json" in {
      Utils.map2Json(Map("test" -> Map("theAnswer" -> 42, "isTrue" -> true))) mustBe
        """{"test":{"theAnswer":42,"isTrue":true}}"""
    }

    "convert json to Scala map" in {
      Utils.mapFromJson("""{"test":{"theAnswer":42,"isTrue":true}}""") mustBe
        Map("test" -> Map("theAnswer" -> 42, "isTrue" -> true))
    }

  }

}
