package ch.mibex.bamboo.shipit.settings

import ch.mibex.bamboo.shipit.Logging
import ch.mibex.bamboo.shipit.mpac.{MpacCredentials, MpacFacade}
import com.atlassian.bamboo.security.{EncryptionException, EncryptionService}
import com.atlassian.bamboo.ww2.BambooActionSupport
import com.opensymphony.xwork2.Action

import scala.beans.BeanProperty

class AdminSettingsAction(encryptionService: EncryptionService, mpacCredentialsDao: AdminSettingsDao)
    extends BambooActionSupport
    with Logging {

  private val MpacVendorNameField = "vendorName"
  private val MpacVendorApiTokenField = "vendorApiToken"
  private val EmptyFieldErrorMsg = "shipit.admin.credentials.error.empty"

  @BeanProperty var vendorName: String = _
  @BeanProperty var vendorApiToken: String = _

  override def doDefault(): String = {
    require(mpacCredentialsDao != null)
    mpacCredentialsDao.find() foreach { c =>
      vendorName = c.getVendorUserName
      vendorApiToken = c.getVendorApiToken
    }
    Action.INPUT
  }

  def doEdit(): String = {
    validate()

    if (!getActionErrors.isEmpty) {
      Action.ERROR
    } else {
      createOrUpdateVendorCredentials()
      addActionMessage(getText("shipit.admin.save.success"))
      Action.SUCCESS
    }
  }

  private def createOrUpdateVendorCredentials() {
    mpacCredentialsDao.createOrUpdate(vendorName, vendorApiToken)
    // this is necessary because otherwise the pw would be shown in cleartext in the form
    vendorApiToken = encryptionService.encrypt(vendorApiToken)
  }

  override def validate(): Unit = {
    if (Option(vendorName).getOrElse("").trim.isEmpty) {
      addFieldError(MpacVendorNameField, getText(EmptyFieldErrorMsg))
    }
    if (Option(vendorApiToken).getOrElse("").trim.isEmpty) {
      addFieldError(MpacVendorApiTokenField, getText(EmptyFieldErrorMsg))
    }
    if (!hasAnyErrors) {
      checkMpacConnection()
    }
  }

  private def checkMpacConnection() {
    val credentials = MpacCredentials(vendorUserName = vendorName, vendorApiToken = decryptIfNecessary(vendorApiToken))
    MpacFacade.withMpac(credentials) { mpac =>
      mpac.checkCredentials() foreach { error =>
        addActionError(getText(error.i18n))
      }
    }
  }

  private def decryptIfNecessary(pw: String) =
    try {
      encryptionService.decrypt(vendorApiToken)
    } catch {
      case e: EncryptionException => pw // not encrypted
    }

}
