package ch.mibex.bamboo.shipit.task.artifact

import ch.mibex.bamboo.shipit.task.artifacts.{ArtifactDownloaderTaskId, ArtifactSubscriptionId}
import org.junit.runner.RunWith
import org.scalatest.matchers.must.Matchers.mustBe
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ArtifactDeploymentIdTest extends AnyWordSpec {

  "unapply with an artifact ID and a name" should {

    "yield a artifact subscription ID" in {
      "123:XYZ" match {
        case ArtifactSubscriptionId(artifactId, artifactName) =>
          artifactId mustBe 123
          artifactName mustBe "XYZ"
        case _ => false mustBe true
      }
    }

  }

  "unapply with an artifact ID, a name, a downloader task ID and a transfer ID" should {

    "yield a artifact downloader task ID" in {
      "123:XYZ:456:789" match {
        case ArtifactDownloaderTaskId(artifactId, artifactName, downloaderTaskId, transferId) =>
          artifactId mustBe 123
          artifactName mustBe "XYZ"
          downloaderTaskId mustBe 456
          transferId mustBe 789
        case _ => false mustBe true
      }
    }

  }

}
