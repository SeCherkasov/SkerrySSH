package app.skerry.ui.vault.keyring

import app.skerry.shared.io.PrivateConfig
import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.DeviceSecretStoreException
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Windows: the secret sealed by DPAPI under the signed-in user's credentials, the blob kept in [dir].
 * The blob is useless off this account on this machine — which is the whole binding; copying the
 * config directory elsewhere carries nothing that opens the vault.
 */
internal class DpapiSecretStore(private val dir: Path) : DeviceSecretStore {

    private val available = ProbeOnce {
        // A seal/unseal round trip, not a class load: a profile without a usable master key fails here.
        val probe = byteArrayOf(1, 2, 3)
        Crypt32Util.cryptUnprotectData(Crypt32Util.cryptProtectData(probe, ENTROPY, FLAGS, "", null), ENTROPY, FLAGS, null)
            .contentEquals(probe)
    }

    override fun isAvailable(): Boolean = available.get()

    override fun read(name: String): ByteArray? {
        val blob = try {
            Files.readAllBytes(file(name))
        } catch (_: NoSuchFileException) {
            return null
        } catch (e: IOException) {
            throw DeviceSecretStoreException("DPAPI blob unreadable", e)
        }
        return native { Crypt32Util.cryptUnprotectData(blob, ENTROPY, FLAGS, null) }
    }

    override fun write(name: String, secret: ByteArray) {
        val blob = native { Crypt32Util.cryptProtectData(secret, ENTROPY, FLAGS, DESCRIPTION, null) }
        try {
            PrivateConfig.atomicWrite(file(name), blob)
        } catch (e: IOException) {
            throw DeviceSecretStoreException("DPAPI blob not written", e)
        }
    }

    override fun delete(name: String) {
        try {
            Files.deleteIfExists(file(name))
        } catch (e: IOException) {
            throw DeviceSecretStoreException("DPAPI blob not deleted", e)
        }
    }

    private fun file(name: String): Path = dir.resolve("${requireEntryName(name)}.dpapi")

    private inline fun <T> native(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        // Win32Exception, LastErrorException: a profile whose keys are gone, a roaming profile offline.
        throw DeviceSecretStoreException("DPAPI refused", e)
    }

    private companion object {
        // Not a secret — anything running as this user can unseal the blob, as it can read the login
        // keyring on the other platforms. It only keeps this blob from matching another DPAPI use.
        val ENTROPY = "skerry.device-keyring.v1".encodeToByteArray()
        const val FLAGS = WinCrypt.CRYPTPROTECT_UI_FORBIDDEN
        const val DESCRIPTION = "Skerry vault key"
    }
}
