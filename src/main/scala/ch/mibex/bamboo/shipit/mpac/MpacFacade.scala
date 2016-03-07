package ch.mibex.bamboo.shipit.mpac

import ch.mibex.bamboo.shipit.mpac.MpacError.{MpacAuthenticationError, MpacConnectionError, MpacUploadError}
import ch.mibex.bamboo.shipit.{Logging, Utils}
import com.atlassian.marketplace.client.HttpConfiguration.Credentials
import com.atlassian.marketplace.client.api.PluginVersionUpdate.Deployment
import com.atlassian.marketplace.client.api.{ApplicationKey, PluginDetailQuery, PluginVersionUpdate}
import com.atlassian.marketplace.client.impl.{AbstractMarketplaceClient, DefaultMarketplaceClient}
import com.atlassian.marketplace.client.model.{Plugin, PluginVersion}
import com.atlassian.marketplace.client.{HttpConfiguration, MarketplaceClient, MpacException}
import com.atlassian.upm.api.util.{Option => UPMOption}

import scala.collection.JavaConverters._

case class MpacCredentials(vendorUserName: String, vendorPassword: String)

case class NewPluginVersionDetails(plugin: Plugin,
                                   baseVersion: PluginVersion,
                                   buildNumber: Int,
                                   versionNumber: String,
                                   binary: Deployment,
                                   isPublicVersion: Boolean,
                                   releaseSummary: String,
                                   releaseNotes: String) {
  override def toString() =
    s"""plugin=${plugin.getPluginKey},
       |baseVersion=${baseVersion.getVersion},
       |buildNumber=$buildNumber,
       |versionNumber=$versionNumber,
       |isPublicVersion=$isPublicVersion,
       |releaseSummary=$releaseSummary,
       |releaseNotes=$releaseNotes)
     """.stripMargin
}

sealed trait MpacError {
  def i18n: String
}

object MpacError {

  case class MpacAuthenticationError() extends MpacError {
    override def i18n: String = "shipit.task.config.mpac.auth.error"
  }

  case class MpacConnectionError() extends MpacError {
    override def i18n: String = "shipit.mpac.connection.error"
  }

  case class MpacUploadError(reason: String) extends MpacError {
    override def i18n: String = "shipit.mpac.upload.error"
  }

}

object MpacFacade {

  def withMpac[T](credentials: MpacCredentials)(block: MpacFacade => T): T = {
    val c = new Credentials(credentials.vendorUserName, credentials.vendorPassword)
    val httpConfig = HttpConfiguration
      .builder()
      .credentials(UPMOption.some(c))
      .build()
    val client = new DefaultMarketplaceClient(AbstractMarketplaceClient.DEFAULT_SERVER_URI, httpConfig)

    try {
      block(new MpacFacade(client))
    } finally {
      client.destroy()
    }
  }

}


class MpacFacade(client: MarketplaceClient) extends Logging {
  private final val LinksToCopy = List("issue-tracker", "documentation", "license", "eula")

  def findPlugin(pluginKey: String): Either[MpacError, Option[Plugin]] = {
    try {
      val criteria = PluginDetailQuery.builder(pluginKey).build()
      Right(client.plugins().get(criteria))
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacConnectionError())
    }
  }

  def publish(version: NewPluginVersionDetails): Either[MpacError, PluginVersion] = {

    val versionBuilder =
      PluginVersionUpdate
        .copyNewVersion(version.plugin, version.baseVersion, version.buildNumber, version.versionNumber, version.binary)
        .published(version.isPublicVersion)
        .summary(UPMOption.option(version.releaseSummary))
        .releaseNotes(UPMOption.some(version.releaseNotes))

    // we cannot just take all links because some of them are internal Marketplace links
    // and because there are duplicate links which result in an error when uploading a new version
    for (l <- version.baseVersion.getLinks.getItems.asScala if LinksToCopy contains l.getRel) {
      versionBuilder.addLink(l.getRel, l.getHref)
    }

    for (compat <- version.baseVersion.getCompatibilities.asScala) {
      val maxVersion = compat.getMax.getVersion
      val minVersion = compat.getMin.getVersion
      val applicationName = ApplicationKey.valueOf(compat.getApplicationName)
      versionBuilder.compatibility(applicationName, minVersion, maxVersion)
    }

    try {
      Right(client.plugins().putVersion(versionBuilder.build()))
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to publish plug-in", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to publish plug-in", e)
        Left(MpacConnectionError())
      case e: MpacException =>
        log.error(s"SHIPIT2MARKETPLACE: failed to publish plug-in", e)
        val reason = Utils.mapFromJson(e.getMessage)
          .get("errors")
          .map(e => e.asInstanceOf[List[String]].mkString(", "))
          .getOrElse("Unknown reason")
        Left(MpacUploadError(reason))
    }
  }

  def checkCredentials(): Option[MpacError] = {
    try {
      // isReachable does not do authentication, so I just use one of the provided API calls
      client.applications().findAll()
      None
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to check credentials", e)
        Some(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to check credentials", e)
        Some(MpacConnectionError())
    }
  }

  implicit def asScalaOption[T](upmOpt: com.atlassian.upm.api.util.Option[T]): Option[T] =
    if (upmOpt.isDefined) Some(upmOpt.get)
    else None

}

