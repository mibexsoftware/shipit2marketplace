package ch.mibex.bamboo.shipit.task.artifact

import ch.mibex.bamboo.shipit.task.artifacts.{ArtifactDownloaderTaskId, ArtifactSubscriptionId}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ArtifactDeploymentIdSpec extends Specification {

  "unapply with an artifact ID and a name" should {

    "yield a artifact subscription ID" in {
      "123:XYZ" match {
        case ArtifactSubscriptionId(artifactId, artifactName) =>
          artifactId must_== 123
          artifactName must_== "XYZ"
        case _ => false must_== true
      }
    }

  }

  "unapply with an artifact ID, a name, a downloader task ID and a transfer ID" should {

    "yield a artifact downloader task ID" in {
      "123:XYZ:456:789" match {
        case ArtifactDownloaderTaskId(artifactId, artifactName, downloaderTaskId, transferId) =>
          artifactId must_== 123
          artifactName must_== "XYZ"
          downloaderTaskId must_== 456
          transferId must_== 789
        case _ => false must_== true
      }
    }

  }

}
