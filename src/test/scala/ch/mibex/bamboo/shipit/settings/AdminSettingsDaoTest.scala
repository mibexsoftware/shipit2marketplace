package ch.mibex.bamboo.shipit.settings

import com.atlassian.activeobjects.test.TestActiveObjects
import com.atlassian.bamboo.security.EncryptionService
import net.java.ao.EntityManager
import net.java.ao.test.converters.NameConverters
import net.java.ao.test.jdbc._
import net.java.ao.test.junit.ActiveObjectsJUnitRunner
import org.junit.runner.RunWith
import org.junit.{Before, Test}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

object AdminSettingsDaoTest {
  class TestDatabaseUpdater extends DatabaseUpdater {
    override def update(entityManager: EntityManager): Unit = {
      entityManager.migrate(classOf[AoAdminSettings])
    }
  }
}
@RunWith(classOf[ActiveObjectsJUnitRunner])
@Data(classOf[AdminSettingsDaoTest.TestDatabaseUpdater])
@Jdbc(classOf[Hsql])
@NameConverters
class AdminSettingsDaoTest {
  var entityManager: EntityManager = _ // will be set by reflection from ActiveObjectsJUnitRunner
  var adminConfigDao: AdminSettingsDao = _

  private class EncryptedWithStarsAnswer extends Answer[String] {
    override def answer(i: InvocationOnMock): String = {
      val arguments = i.getArguments
      if (arguments.nonEmpty && arguments(0).isInstanceOf[String]) {
        return "*" * arguments(0).asInstanceOf[String].length
      }
      throw new IllegalArgumentException("String expected as first argument")
    }
  }

  @Before
  def setUp(): Unit = {
    val ao = new TestActiveObjects(entityManager)
    val encryptionService = mock(classOf[EncryptionService])
    Mockito.doAnswer(new EncryptedWithStarsAnswer).when(encryptionService).encrypt(anyString())
    adminConfigDao = new AdminSettingsDao(ao, encryptionService)
  }

  @Test
  @NonTransactional // our DAO already opens a transaction, so we do not want another transaction being opened
  def createOrUpdateWithNoExistingEntities(): Unit = {
    assert(adminConfigDao.find().isEmpty)
    adminConfigDao.createOrUpdate("myOtherVendor", "4567")

    val vendorCredentials = adminConfigDao.find().get

    assert(vendorCredentials.getVendorUserName == "myOtherVendor")
    assert(vendorCredentials.getVendorPassword == "****")
  }

  @Test
  @NonTransactional
  def createOrUpdateWithExistingEntity(): Unit = {
    assert(adminConfigDao.find().isEmpty)
    val vendorCredentials = adminConfigDao.createOrUpdate("vendorName1", "vendorPw")
    assert(vendorCredentials.getVendorUserName == "vendorName1")
    assert(vendorCredentials.getVendorPassword == "********")

    adminConfigDao.createOrUpdate("vendorName2", "vendorPw2")
    assert(entityManager.count(classOf[AoAdminSettings]) == 1)
    val updatedCredentials = adminConfigDao.find().get

    assert(updatedCredentials.getVendorUserName == "vendorName2", updatedCredentials.getVendorUserName)
    assert(updatedCredentials.getVendorPassword == "*********")
  }

}
