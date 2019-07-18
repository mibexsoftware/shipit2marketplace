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
  private val MpacVendorPasswordField = "vendorPassword"
  private val EmptyFieldErrorMsg = "shipit.admin.credentials.error.empty"

  @BeanProperty var vendorName: String = _
  @BeanProperty var vendorPassword: String = _

  override def doDefault(): String = {
    require(mpacCredentialsDao != null)
    mpacCredentialsDao.find() foreach { c =>
      vendorName = c.getVendorUserName
      vendorPassword = c.getVendorPassword
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
    mpacCredentialsDao.createOrUpdate(vendorName, vendorPassword)
    // this is necessary because otherwise the pw would be shown in cleartext in the form
    vendorPassword = encryptionService.encrypt(vendorPassword)
  }

  override def validate(): Unit = {
    if (Option(vendorName).getOrElse("").trim.isEmpty) {
      addFieldError(MpacVendorNameField, getText(EmptyFieldErrorMsg))
    }
    if (Option(vendorPassword).getOrElse("").trim.isEmpty) {
      addFieldError(MpacVendorPasswordField, getText(EmptyFieldErrorMsg))
    }
    if (!hasAnyErrors) {
      checkMpacConnection()
    }
  }

  private def checkMpacConnection() {
    val credentials = MpacCredentials(vendorUserName = vendorName, vendorPassword = decryptIfNecessary(vendorPassword))
    MpacFacade.withMpac(credentials) { mpac =>
      mpac.checkCredentials() foreach { error =>
        addActionError(getText(error.i18n))
      }
    }
  }

  private def decryptIfNecessary(pw: String) =
    try {
      encryptionService.decrypt(vendorPassword)
    } catch {
      case e: EncryptionException => pw // not encrypted
    }

}
