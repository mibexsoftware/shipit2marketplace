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

case class NewPluginVersion(plugin: Plugin,
                            fromVersion: PluginVersion,
                            buildNumber: Int,
                            versionNumber: String,
                            binary: Deployment,
                            isPublicVersion: Boolean,
                            releaseSummary: String,
                            releaseNotes: String)

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

  def findPlugin(pluginKey: String): Option[Plugin] = {
    val criteria = PluginDetailQuery.builder(pluginKey).build()
    client.plugins().get(criteria)
  }

  def publish(version: NewPluginVersion): Either[MpacUploadError, PluginVersion] = {
    val versionBuilder =
      PluginVersionUpdate
        .copyNewVersion(version.plugin, version.fromVersion, version.buildNumber, version.versionNumber, version.binary)
        .published(version.isPublicVersion)
        .summary(UPMOption.option(version.releaseSummary))
        .releaseNotes(UPMOption.some(version.releaseNotes))

    for (compat <- version.fromVersion.getCompatibilities.asScala) {
      val maxVersion = compat.getMax.getVersion
      val minVersion = compat.getMin.getVersion
      val applicationName = ApplicationKey.valueOf(compat.getApplicationName)
      versionBuilder.compatibility(applicationName, minVersion, maxVersion)
    }

    try {
      Right(client.plugins().putVersion(versionBuilder.build()))
    } catch {
      case e: MpacException =>
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
      case e: MpacException.ServerError if e.getStatus == 401 => Some(MpacAuthenticationError())
      case e: Exception => Some(MpacConnectionError())
    }
  }

  implicit def asScalaOption[T](upmOpt: com.atlassian.upm.api.util.Option[T]): Option[T] =
    if (upmOpt.isDefined) Some(upmOpt.get)
    else None

}

