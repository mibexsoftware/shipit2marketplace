package ch.mibex.bamboo.shipit.task.artifacts

import com.atlassian.bamboo.plan.artifact.{ImmutableArtifactDefinition, ImmutableArtifactSubscription}
import com.atlassian.bamboo.task.TaskDefinition

case class ArtifactSubscriptionId(artifactIdParam: Long, artifactNameParam: String) {
  override def toString = s"$artifactIdParam:$artifactNameParam"
}

// there are three ways to define artifacts in Bamboo:
// - inter-plan artifacts by sharing
// - sharing artifacts between build plans through the artifact downloader task
// - sharing artifacts from a build plan to a deployment environment
// see https://confluence.atlassian.com/display/BAMBOO058/Sharing+artifacts
object ArtifactSubscriptionId {

  def apply(artifactSubscription: ImmutableArtifactSubscription): ArtifactSubscriptionId =
    ArtifactSubscriptionId(artifactSubscription.getArtifactDefinition.getId, artifactSubscription.getName)

  def apply(artifactDefinition: ImmutableArtifactDefinition): ArtifactSubscriptionId =
    ArtifactSubscriptionId(artifactDefinition.getId, artifactDefinition.getName)

  def unapply(s: String): Option[(Long, String)] = s.split(":") match {
    case Array(artifactId, artifactName) => Some((artifactId.toLong, artifactName))
    case _ => None
  }

}

case class ArtifactDownloaderTaskId(
    artifactIdParam: Long,
    artifactNameParam: String,
    downloaderTaskId: Long,
    transferId: Int
) {
  override def toString = s"$artifactIdParam:$artifactNameParam:$downloaderTaskId:$transferId"
}

object ArtifactDownloaderTaskId {

  def apply(definition: ImmutableArtifactDefinition, task: TaskDefinition, transferId: Int): ArtifactDownloaderTaskId =
    ArtifactDownloaderTaskId(definition.getId, definition.getName, task.getId, transferId)

  def unapply(s: String): Option[(Long, String, Long, Int)] = s.split(":") match {
    // e.g.: "v2:58392577:2:0:Plan DSL for Bamboo Plugin"
    case Array(_, artifactId, downloaderTaskId, transferId, artifactName) =>
      Some((artifactId.toLong, artifactName, downloaderTaskId.toLong, transferId.toInt))
    case Array(artifactId, artifactName, downloaderTaskId, transferId) =>
      Some((artifactId.toLong, artifactName, downloaderTaskId.toLong, transferId.toInt))
    case _ => None
  }

}
