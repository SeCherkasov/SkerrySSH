package app.skerry.ui.vault.keyring

import app.skerry.shared.vault.BioArtifact
import app.skerry.shared.vault.BioArtifactStore
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.BiometricUnlockResult
import app.skerry.shared.vault.DeviceKeyringKeyStore
import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.FileBioArtifactStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trust's upkeep outside the gate (issue #398): a sync that replaces the vault key re-seals it,
 * and a flag that went off takes it away — each recorded only when it actually happened.
 */
class TrustedDeviceUpkeepTest {

    private val dir = Files.createTempDirectory("skerry-trust-upkeep")
    private val crypto = IonspinVaultCrypto()
    private val secrets = Secrets()
    private val log = Log()
    private var flag = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a replaced vault key is sealed again, so the next start still opens by itself`() = runBlocking {
        val vault = trustedVault()
        adoptForeignKey(vault)

        upkeep(biometrics(vault)).resealAfterKeyAdoption().join()
        vault.lock()

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unlocked, biometrics(fresh).unlock(PROMPT))
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `a replaced key with the flag gone off drops the trust and says so`() = runBlocking {
        val vault = trustedVault()
        adoptForeignKey(vault)
        flag = false

        val bio = biometrics(vault)
        upkeep(bio).resealAfterKeyAdoption().join()

        assertFalse(bio.isEnabled())
        assertEquals(listOf(SecurityEventType.TrustedDeviceDisabled), log.events)
    }

    @Test
    fun `a replaced key on a device without the trust leaves it that way`() = runBlocking {
        val vault = vault().apply { initializeVaultCrypto(); create(PASSWORD.toCharArray()) }
        adoptForeignKey(vault)

        val bio = biometrics(vault)
        upkeep(bio).resealAfterKeyAdoption().join()

        assertFalse(bio.isEnabled())
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `a start with the flag off drops a trust left on disk`() = runBlocking {
        val vault = trustedVault()
        flag = false

        val bio = biometrics(vault)
        upkeep(bio).dropIfDisallowed().join()

        assertFalse(bio.isEnabled())
        assertTrue(secrets.entries.isEmpty())
        assertEquals(listOf(SecurityEventType.TrustedDeviceDisabled), log.events)
    }

    @Test
    fun `a start with the flag on keeps the trust`() = runBlocking {
        val vault = trustedVault()

        val bio = biometrics(vault)
        upkeep(bio).dropIfDisallowed().join()

        assertTrue(bio.isEnabled())
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `a drop that could not remove the trust is not recorded as one`() = runBlocking {
        trustedVault()
        flag = false
        val stuck = object : BioArtifactStore {
            override fun exists() = true
            override fun read(): BioArtifact? = null
            override fun write(artifact: BioArtifact) = Unit
            override fun clear() = throw IOException("read-only config directory")
        }

        upkeep(VaultBiometrics(vault(), keyStore(), stuck, deviceId = DEVICE)).dropIfDisallowed().join()

        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `a replaced key the keyring cannot take drops the trust and says so`() = runBlocking {
        val vault = trustedVault()
        adoptForeignKey(vault)
        secrets.available = false

        val bio = biometrics(vault)
        upkeep(bio).resealAfterKeyAdoption().join()

        assertFalse(bio.isEnabled(), "a wrapper of the old key opens nothing")
        assertEquals(listOf(SecurityEventType.TrustedDeviceDisabled), log.events)
    }

    @Test
    fun `a re-seal that blows up still drops the stale trust`() = runBlocking {
        val vault = trustedVault()
        adoptForeignKey(vault)
        val disk = FileBioArtifactStore(dir.resolve("vault.bio").toString().toPath(), FileSystem.SYSTEM)
        val refusing = object : BioArtifactStore by disk {
            override fun write(artifact: BioArtifact) = throw IOException("disk full")
        }

        val bio = VaultBiometrics(vault, keyStore(), refusing, deviceId = DEVICE)
        upkeep(bio).resealAfterKeyAdoption().join()

        assertFalse(bio.isEnabled())
        assertEquals(listOf(SecurityEventType.TrustedDeviceDisabled), log.events)
    }

    private fun upkeep(bio: VaultBiometrics) = TrustedDeviceUpkeep(bio, allowed = { flag }, securityLog = log, scope = scope)

    private fun vault() = FileVault(dir.resolve("vault.json").toString().toPath(), crypto, DEVICE, FileSystem.SYSTEM) { TS }

    private fun keyStore() = DeviceKeyringKeyStore(secrets, crypto, allowed = { flag })

    private fun biometrics(vault: FileVault) = VaultBiometrics(
        vault,
        keyStore(),
        FileBioArtifactStore(dir.resolve("vault.bio").toString().toPath(), FileSystem.SYSTEM),
        deviceId = DEVICE,
    )

    private suspend fun trustedVault(): FileVault {
        initializeVaultCrypto()
        return vault().apply {
            create(PASSWORD.toCharArray())
            assertEquals(BiometricEnableResult.Enabled, biometrics(this).enable(PROMPT))
        }
    }

    /** What pairing does: the account's key from another device replaces this one's. */
    private fun adoptForeignKey(vault: FileVault) {
        val other = Files.createTempDirectory(dir, "other")
        val source = FileVault(other.resolve("vault.json").toString().toPath(), crypto, "device-2", FileSystem.SYSTEM) { TS }
        source.create(PASSWORD.toCharArray())
        assertTrue(vault.adoptDataKey(source.exportDataKey()!!, PASSWORD.toCharArray()))
    }
}

private class Secrets : DeviceSecretStore {
    val entries = mutableMapOf<String, ByteArray>()
    var available = true
    override fun isAvailable() = available
    override fun read(name: String) = entries[name]?.copyOf()
    override fun write(name: String, secret: ByteArray) {
        entries[name] = secret.copyOf()
    }
    override fun delete(name: String) {
        entries.remove(name)
    }
}

private class Log : SecurityLog {
    val events = mutableListOf<SecurityEventType>()
    override fun record(type: SecurityEventType, detail: String?) {
        events += type
    }
    override fun recent(limit: Int): List<SecurityEvent> = emptyList()
    override fun lastPasswordChangeAt(): String? = null
    override fun clear() = Unit
}

private val PROMPT = BiometricPrompt(title = "Trust", cancelLabel = "Cancel")
private const val PASSWORD = "correct horse battery"
private const val DEVICE = "device-1"
private const val TS = "2026-09-30T00:00:00Z"
