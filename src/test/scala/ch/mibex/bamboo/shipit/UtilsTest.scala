package ch.mibex.bamboo.shipit

import java.io.File

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner


@RunWith(classOf[JUnitRunner])
class UtilsTest extends Specification {

  "Utils" should {

    "yield a build number padded with zeroes" in {
      Utils.toBuildNumber("1.2.3") must_== 100200300
      Utils.toBuildNumber("11.0.0") must_== 110000000
      Utils.toBuildNumber("11.1.1") must_== 110100100
      Utils.toBuildNumber("11.1.10") must_== 110101000
      Utils.toBuildNumber("0.0.1") must_== 100
      Utils.toBuildNumber("1.0") must_== 100000000
      Utils.toBuildNumber("2.0") must_== 200000000
      Utils.toBuildNumber("1.2.3-SNAPSHOT") must_== 100200300
      Utils.toBuildNumber("5.2.9") must_== 500200900
      Utils.toBuildNumber("5.2.10") must_== 500201000
      Utils.toBuildNumber("5.200.1") must_== 520000100
      Utils.toBuildNumber("5.20.1") must_== 502000100
      Utils.toBuildNumber("5.20.10") must_== 502001000
      Utils.toBuildNumber("5.200.10") must_== 520001000
      Utils.toBuildNumber("5.200.100") must_== 520010000
      Utils.toBuildNumber("5.2.100") must_== 500210000
      Utils.toBuildNumber("5.2.99") must_== 500209900
      Utils.toBuildNumber("5.20.99") must_== 502009900
    }

    "convert a Scala map to json" in {
      Utils.map2Json(Map("test" -> Map("theAnswer" -> 42, "isTrue" -> true))) must_==
        """{"test":{"theAnswer":42,"isTrue":true}}"""
    }

    "convert json to Scala map" in {
      Utils.mapFromJson("""{"test":{"theAnswer":42,"isTrue":true}}""") must_==
        Map("test" -> Map("theAnswer" -> 42, "isTrue" -> true))
    }

    "find file by ant pattern" in {
      val res = Utils.findMostRecentMatchingFile("**/*.zip", new File(getClass.getResource("/").toURI))
      res match {
        case Some(file) if file.getName == "bamboo-5.2-integration-test-home.zip" => true must_== true
        case _ => true must_== false
      }
    }

  }

}