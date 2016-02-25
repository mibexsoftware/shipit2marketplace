package ch.mibex.bamboo.shipit.settings

import net.java.ao._
import net.java.ao.schema.{Table, NotNull}

object AoAdminSettings {
  final val VENDOR_USERNAME_COLUMN = "VENDOR_NAME"
  final val VENDOR_PASSWORD_COLUMN = "VENDOR_PW"
}

@Table("ShipItSettings")
@Preload
trait AoAdminSettings extends Entity {
  import AoAdminSettings._

  @NotNull
  @Accessor(VENDOR_USERNAME_COLUMN)
  def getVendorUserName: String

  @Mutator(VENDOR_USERNAME_COLUMN)
  def setVendorUserName(login: String): Unit

  @NotNull
  @Accessor(VENDOR_PASSWORD_COLUMN)
  def getVendorPassword: String

  @Mutator(VENDOR_PASSWORD_COLUMN)
  def setVendorPassword(password: String): Unit

}
