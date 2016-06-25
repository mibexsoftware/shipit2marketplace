package ch.mibex.bamboo.shipit.settings

import ch.mibex.bamboo.shipit.Logging
import com.atlassian.activeobjects.external.ActiveObjects
import com.atlassian.bamboo.security.EncryptionService
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport
import com.atlassian.sal.api.transaction.TransactionCallback
import net.java.ao.DBParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class AdminSettingsDao @Autowired()(@ComponentImport ao: ActiveObjects,
                                    @ComponentImport encryptionService: EncryptionService) extends Logging {

  import AoAdminSettings._

  // we cannot use @Transactional annotations but instead have to wrap our DB code into executeInTransaction
  // because this annotation cannot be used on Bamboo remote agents
  def createOrUpdate(vendorName: String, vendorPassword: String): AoAdminSettings =
    executeInTransaction(() => {
      ao.find[AoAdminSettings, Integer](classOf[AoAdminSettings]).headOption match {
        case Some(credentials) =>
          credentials.setVendorUserName(vendorName)
          credentials.setVendorPassword(encryptionService.encrypt(vendorPassword))
          credentials.save()
          credentials
        case None =>
          ao.create[AoAdminSettings, Integer](
            classOf[AoAdminSettings],
            new DBParam(VENDOR_USERNAME_COLUMN, vendorName),
            new DBParam(VENDOR_PASSWORD_COLUMN, encryptionService.encrypt(vendorPassword))
          )
      }
    })

  def find(): Option[AoAdminSettings] =
    executeInTransaction(() => ao.find[AoAdminSettings, Integer](classOf[AoAdminSettings]).headOption)

  private def executeInTransaction[T](fun: () => T) =
    ao.executeInTransaction(new TransactionCallback[T]() {
      override def doInTransaction(): T = fun()
    })

}
