package app.skerry.ui.vault.keyring

import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.DeviceSecretStoreException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A JNA symbol missing from the OS library is a keyring that failed, not an `Error` that ends the app. */
class LinkageGuardTest {

    private val guarded = LinkageGuard(Unlinked())

    @Test
    fun `every call turns a linkage error into a keyring failure`() {
        assertFailsWith<DeviceSecretStoreException> { guarded.isAvailable() }
        assertFailsWith<DeviceSecretStoreException> { guarded.read("skerry.alias") }
        assertFailsWith<DeviceSecretStoreException> { guarded.write("skerry.alias", ByteArray(1)) }
        assertFailsWith<DeviceSecretStoreException> { guarded.delete("skerry.alias") }
    }
}

private class Unlinked : DeviceSecretStore {
    override fun isAvailable(): Boolean = throw UnsatisfiedLinkError("secret_password_lookup_sync")
    override fun read(name: String): ByteArray? = throw NoClassDefFoundError("com/sun/jna/Native")
    override fun write(name: String, secret: ByteArray) = throw UnsatisfiedLinkError("secret_password_store_sync")
    override fun delete(name: String) = throw UnsatisfiedLinkError("secret_password_clear_sync")
}
