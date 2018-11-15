package ch.mibex.bamboo.shipit.mpac

import java.io.File
import java.net.URL

import ch.mibex.bamboo.shipit.Logging
import ch.mibex.bamboo.shipit.mpac.MpacError.{MpacAuthenticationError, MpacConnectionError, MpacUploadError}
import com.atlassian.fugue
import com.atlassian.marketplace.client.api._
import com.atlassian.marketplace.client.http.HttpConfiguration
import com.atlassian.marketplace.client.http.HttpConfiguration.Credentials
import com.atlassian.marketplace.client.impl.DefaultMarketplaceClient
import com.atlassian.marketplace.client.model._
import com.atlassian.marketplace.client.{MarketplaceClient, MpacException}
import scala.collection.JavaConverters._

case class MpacCredentials(vendorUserName: String, vendorPassword: String)

case class NewPluginVersionDetails(plugin: Addon,
                                   baseVersion: AddonVersion,
                                   serverBuildNumber: Int,
                                   dataCenterBuildNumber: Long,
                                   minServerBuildNumber: Int,
                                   maxServerBuildNumber: Int,
                                   minDataCenterBuildNumber: Int,
                                   maxDataCenterBuildNumber: Int,
                                   versionNumber: String,
                                   baseProduct: String,
                                   isDcBuildNrConfigured: Boolean,
                                   userName: Option[String],
                                   binary: File,
                                   isPublicVersion: Boolean,
                                   releaseSummary: String,
                                   releaseNotes: String) {
  override def toString: String =
    s"""plugin=${plugin.getKey},
        |baseVersion=${baseVersion.getName},
        |minServerBuildNumber=$minServerBuildNumber,
        |maxServerBuildNumber=$maxServerBuildNumber,
        |minDataCenterBuildNumber=$minDataCenterBuildNumber,
        |maxDataCenterBuildNumber=$maxDataCenterBuildNumber,
        |baseProduct=$baseProduct,
        |versionNumber=$versionNumber,
        |serverBuildNumber=$serverBuildNumber,
        |dataCenterBuildNumber=$dataCenterBuildNumber,
        |userName=${userName.getOrElse("")},
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
      .credentials(fugue.Option.some(c))
      .build()
    val client = new DefaultMarketplaceClient(DefaultMarketplaceClient.DEFAULT_SERVER_URI, httpConfig)

    try {
      block(new MpacFacade(client))
    } finally {
      client.close()
    }
  }

}


class MpacFacade(client: MarketplaceClient) extends Logging {

  def getVersion(pluginKey: String, version: Option[String] = None): Either[MpacError, Option[AddonVersion]] = {
    try {
      val criteria = AddonVersionsQuery.any()
      val specifier = version match {
        case Some(v) => AddonVersionSpecifier.versionName(v)
        case None => AddonVersionSpecifier.latest()
      }
      Right(client.addons().getVersion(pluginKey, specifier, criteria))
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacConnectionError())
    }
  }

  def findPlugin(pluginKey: String): Either[MpacError, Option[Addon]] = {
    try {
      val criteria = AddonQuery.any()
      Right(client.addons().getByKey(pluginKey, criteria))
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacConnectionError())
    }
  }

  def publish(newVersionDetails: NewPluginVersionDetails): Either[MpacError, AddonVersion] = {
    // see https://docs.atlassian.com/marketplace-client-java/2.0.0-m4/apidocs/index.html
    val artifactId = client.assets().uploadAddonArtifact(newVersionDetails.binary)
    var addonVersion = ModelBuilders
      .addonVersion(newVersionDetails.baseVersion) // copy everything from the base version
      .releaseSummary(newVersionDetails.releaseSummary)
      .releaseNotes(HtmlString.html(newVersionDetails.releaseNotes))
      .releaseDate(new org.joda.time.LocalDate())
      .buildNumber(newVersionDetails.serverBuildNumber)
      .releasedBy(newVersionDetails.userName)
      .artifact(artifactId)
      .name(newVersionDetails.versionNumber)
      .status(if (newVersionDetails.isPublicVersion) AddonVersionStatus.PUBLIC else AddonVersionStatus.PRIVATE)
      .agreement(new URL("http://www.atlassian.com/licensing/marketplace/publisheragreement").toURI) // see AMKT-19266
    if (newVersionDetails.isDcBuildNrConfigured) {
      addonVersion = addonVersion.compatibilities(List(
          ModelBuilders.versionCompatibilityForServerAndDataCenter(
            ApplicationKey.valueOf(newVersionDetails.baseProduct),
            newVersionDetails.minServerBuildNumber, // Server version min compatibility
            newVersionDetails.maxServerBuildNumber, // Server version max compatibility
            newVersionDetails.minDataCenterBuildNumber, // DC version min compatibility
            newVersionDetails.maxDataCenterBuildNumber) // DC version max compatibility
        ).asJava)
        .dataCenterBuildNumber(newVersionDetails.dataCenterBuildNumber) // Data Center version build number
    }
    try {
      Right(client.addons().createVersion(newVersionDetails.plugin.getKey, addonVersion.build()))
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to publish plug-in due to server error", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to publish plug-in due to connection failure", e)
        Left(MpacConnectionError())
      case e: MpacException =>
        log.error(s"SHIPIT2MARKETPLACE: failed to publish plug-in due to unknown error", e)
        Left(MpacUploadError(e.getMessage))
    }
  }

  def checkCredentials(): Option[MpacError] = {
    try {
      val vendorQuery = VendorQuery.builder().forThisUserOnly(true).build()
      client.vendors().find(vendorQuery)
      None
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to check credentials due to server error", e)
        Some(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to check credentials due to connection failure", e)
        Some(MpacConnectionError())
    }
  }

  implicit def asFugueOption[T](value: T): fugue.Option[T] = fugue.Option.some(value)

  implicit def asFugueOption[T](scalaOpt: Option[T]): fugue.Option[T] =
    if (scalaOpt.isDefined) fugue.Option.some(scalaOpt.get)
    else fugue.Option.none()

  implicit def asScalaOption[T](upmOpt: fugue.Option[T]): Option[T] =
    if (upmOpt.isDefined) Some(upmOpt.get)
    else None

}

