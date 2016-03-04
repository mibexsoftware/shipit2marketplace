package ch.mibex.bamboo.shipit.settings

import java.util.{List => JList}

import ch.mibex.bamboo.shipit.Logging
import ch.mibex.bamboo.shipit.mpac.{MpacCredentials, MpacFacade}
import com.atlassian.bamboo.security.{EncryptionException, EncryptionService}
import com.atlassian.bamboo.util.BambooStringUtils
import com.atlassian.struts.ActionSupport
import com.opensymphony.xwork2.Action

import scala.beans.BeanProperty
import scala.collection.JavaConverters._

// Bamboo 5.10 doesn't like dependency injection with Spring annotations and actions:
// [INFO] [talledLocalContainer] org.springframework.beans.factory.BeanDefinitionStoreException: Failed to parse
// configuration class [ch.mibex.bamboo.shipit.settings.AdminSettingsAction]; nested exception is
// java.io.FileNotFoundException: class path resource [com/atlassian/bamboo/ww2/BambooActionSupport.class]
// cannot be opened because it does not exist
// We also cannot extend from BambooActionSupport because it uses @AutoWired and Bamboo does not seem to be able
// to inject these dependencies (see https://answers.atlassian.com/questions/36114574/problem-with-linkeddeploymentprojectcacheservice---autowiring-failed)
//@Component
class AdminSettingsAction /*@Autowired()*/ (encryptionService: EncryptionService,
                                            mpacCredentialsDao: AdminSettingsDao) extends ActionSupport with Logging {

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

  def hasActionWarnings = false

  def getFormattedActionErrors: JList[String] =
    getActionErrors.asScala.map(BambooStringUtils.encodeHtmlWithTagWhiteList).toList.asJava

  def getFormattedActionMessages: JList[String] =
    getActionMessages.asScala.map(BambooStringUtils.encodeHtmlWithTagWhiteList).toList.asJava

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

  def hasAnyErrors = hasErrors

  private def checkMpacConnection() {
    val credentials = MpacCredentials(vendorUserName = vendorName,
                                      vendorPassword = decryptIfNecessary(vendorPassword))
    MpacFacade.withMpac(credentials) { mpac =>
      mpac.checkCredentials() foreach { error =>
        addActionError(getText(error.i18n))
      }
    }
  }

  private def decryptIfNecessary(pw: String) = try {
    encryptionService.decrypt(vendorPassword)
  } catch {
    case e: EncryptionException => pw // not encrypted
  }

}