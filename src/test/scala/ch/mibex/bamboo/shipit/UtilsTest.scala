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
      Utils.toBuildNumber("0.0.1") must_== 100
      Utils.toBuildNumber("1.0") must_== 100000000
      Utils.toBuildNumber("2.0") must_== 200000000
      Utils.toBuildNumber("1.2.3-SNAPSHOT") must_== 100200300
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