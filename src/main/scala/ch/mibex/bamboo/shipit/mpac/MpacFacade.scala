package ch.mibex.bamboo.shipit.mpac

import ch.mibex.bamboo.shipit.Logging
import ch.mibex.bamboo.shipit.Utils.JavaOptionals.toRichOptional
import ch.mibex.bamboo.shipit.Utils._
import ch.mibex.bamboo.shipit.mpac.MpacError.{MpacAuthenticationError, MpacConnectionError, MpacUploadError}
import com.atlassian.marketplace.client.api._
import com.atlassian.marketplace.client.http.HttpConfiguration
import com.atlassian.marketplace.client.http.HttpConfiguration.{Credentials, DEFAULT_READ_TIMEOUT_MILLIS}
import com.atlassian.marketplace.client.impl.DefaultMarketplaceClient
import com.atlassian.marketplace.client.model._
import com.atlassian.marketplace.client.{MarketplaceClient, MpacException}
import com.atlassian.plugin.marketing.bean.ProductEnum
import io.atlassian.fugue.Option.some

import java.io.File
import java.net.URL
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
case class MpacCredentials(vendorUserName: String, vendorPassword: String)

case class NewPluginVersionDetails(
    plugin: Addon,
    baseVersion: AddonVersion,
    serverBuildNumber: Int,
    dataCenterBuildNumber: Long,
    minServerBuildNumber: Option[Int],
    maxServerBuildNumber: Option[Int],
    minDataCenterBuildNumber: Option[Int],
    maxDataCenterBuildNumber: Option[Int],
    versionNumber: String,
    baseProduct: Option[String],
    isDcBuildNrConfigured: Boolean,
    createServerVersion: Boolean,
    createDcVersionToo: Boolean,
    userName: Option[String],
    binary: File,
    isPublicVersion: Boolean,
    releaseSummary: String,
    releaseNotes: String
) {

  override def toString: String =
    s"""plugin=${plugin.getKey},
       |baseVersion=${baseVersion.getName.getOrElse("?")},
       |minServerBuildNumber=${minServerBuildNumber.getOrElse("?")},
       |maxServerBuildNumber=${maxServerBuildNumber.getOrElse("?")},
       |minDataCenterBuildNumber=${minDataCenterBuildNumber.getOrElse("?")},
       |maxDataCenterBuildNumber=${maxDataCenterBuildNumber.getOrElse("?")},
       |baseProduct=${baseProduct.getOrElse("?")},
       |versionNumber=$versionNumber,
       |isDcBuildNrConfigured=$isDcBuildNrConfigured,
       |createServerVersion=$createServerVersion,
       |createDcVersionToo=$createDcVersionToo,
       |serverBuildNumber=$serverBuildNumber,
       |dataCenterBuildNumber=$dataCenterBuildNumber,
       |userName=${userName.getOrElse("?")},
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
      .readTimeoutMillis(6 * DEFAULT_READ_TIMEOUT_MILLIS) // Increase read timeout to work around slow marketplace responses.
      .credentials(some(c))
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
      Right(client.addons().safeGetVersion(pluginKey, specifier, criteria).toOption)
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find plug-in with key $pluginKey", e)
        Left(MpacConnectionError())
    }
  }

  def getBuildNumber(product: ProductEnum, versionName: Option[String]): Either[MpacError, Option[Int]] = {
    try {
      val versionSpec = versionName match {
        case Some(version) if version.trim.nonEmpty =>
          Try(version.toInt) match {
            case Success(buildNr) => ApplicationVersionSpecifier.buildNumber(buildNr)
            case Failure(_) => ApplicationVersionSpecifier.versionName(version)
          }
        case _ => ApplicationVersionSpecifier.latest()
      }
      val applKey = ApplicationKey.valueOf(product.name())
      val version = client.applications().safeGetVersion(applKey, versionSpec).toOption
      Right(version.map(_.getBuildNumber))
    } catch {
      case e: MpacException.ServerError if e.getStatus == 401 || e.getStatus == 403 =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find app version for ${product.name()} / $versionName}", e)
        Left(MpacAuthenticationError())
      case e: MpacException.ConnectionFailure =>
        log.error(s"SHIPIT2MARKETPLACE: failed to find app version for ${product.name()} / $versionName}", e)
        Left(MpacConnectionError())
    }
  }

  def findPlugin(pluginKey: String): Either[MpacError, Option[Addon]] = {
    try {
      val criteria = AddonQuery.any()
      Right(client.addons().safeGetByKey(pluginKey, criteria).toOption)
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
    val addonVersion = prepareAddonVersion(newVersionDetails)
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

  private def prepareAddonVersion(newVersionDetails: NewPluginVersionDetails) = {
    val artifactId = client.assets().uploadAddonArtifact(newVersionDetails.binary)
    var addonVersion = ModelBuilders
      .addonVersion(newVersionDetails.baseVersion) // copy everything from the base version
      .releaseSummary(Option(newVersionDetails.releaseSummary))
      .releaseNotes(HtmlString.html(newVersionDetails.releaseNotes))
      .releaseDate(new org.joda.time.LocalDate())
      .buildNumber(newVersionDetails.serverBuildNumber)
      .releasedBy(newVersionDetails.userName)
      .artifact(artifactId)
      .name(newVersionDetails.versionNumber)
      .status(if (newVersionDetails.isPublicVersion) AddonVersionStatus.PUBLIC else AddonVersionStatus.PRIVATE)
      .agreement(new URL("http://www.atlassian.com/licensing/marketplace/publisheragreement").toURI) // see AMKT-19266

    // for DC, configure both DC and server host compatibility
    val dcVersion = newVersionDetails.createDcVersionToo || newVersionDetails.isDcBuildNrConfigured
    if (newVersionDetails.createServerVersion && dcVersion) {
      (
        newVersionDetails.baseProduct,
        newVersionDetails.minServerBuildNumber,
        newVersionDetails.maxServerBuildNumber,
        newVersionDetails.minDataCenterBuildNumber,
        newVersionDetails.maxDataCenterBuildNumber
      ) match {
        case (
            Some(baseProduct),
            Some(minServerBuildNumber),
            Some(maxServerBuildNumber),
            Some(minDataCenterBuildNumber),
            Some(maxDataCenterBuildNumber)
            ) =>
          addonVersion = addonVersion
            .compatibilities(
              List(
                ModelBuilders.versionCompatibilityForServerAndDataCenter(
                  ApplicationKey.valueOf(baseProduct),
                  minServerBuildNumber, // Server version min compatibility
                  maxServerBuildNumber, // Server version max compatibility
                  minDataCenterBuildNumber, // DC version min compatibility
                  maxDataCenterBuildNumber // DC version max compatibility
                )
              ).asJava
            )
            .dataCenterBuildNumber(newVersionDetails.dataCenterBuildNumber) // Data Center version build number
        case _ =>
          // without the product version compatibility for both server and DC, we get the following error:
          // compatibilities: Must have at least one item.
          throw new IllegalStateException(s"DC version details expected but not found: $newVersionDetails")
      }
    } else if (dcVersion) {
      (
        newVersionDetails.baseProduct,
        newVersionDetails.minDataCenterBuildNumber,
        newVersionDetails.maxDataCenterBuildNumber
      ) match {
        case (
            Some(baseProduct),
            Some(minDataCenterBuildNumber),
            Some(maxDataCenterBuildNumber)
            ) =>
          addonVersion = addonVersion
            .compatibilities(
              List(
                ModelBuilders.versionCompatibilityForDataCenter(
                  ApplicationKey.valueOf(baseProduct),
                  minDataCenterBuildNumber, // DC version min compatibility
                  maxDataCenterBuildNumber // DC version max compatibility
                )
              ).asJava
            )
            .dataCenterBuildNumber(newVersionDetails.dataCenterBuildNumber) // Data Center version build number
        case _ =>
          throw new IllegalStateException(s"DC version details expected but not found: $newVersionDetails")
      }
    } else {
      // Server only
      // if specified in the atlassian-plugin-marketing.xml, also take the server host compatibility
      (newVersionDetails.baseProduct, newVersionDetails.minServerBuildNumber, newVersionDetails.maxServerBuildNumber) match {
        case (Some(baseProduct), Some(minServerBuildNumber), Some(maxServerBuildNumber)) =>
          addonVersion = addonVersion.compatibilities(
            List(
              ModelBuilders.versionCompatibilityForServer(
                ApplicationKey.valueOf(baseProduct),
                minServerBuildNumber, // Server version min compatibility
                maxServerBuildNumber // Server version max compatibility
              )
            ).asJava
          )
        case _ =>
      }
    }
    addonVersion
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
}
