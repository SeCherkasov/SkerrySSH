package app.skerry.shared.vault

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory [DeviceSecretStore]. [failing] turns every call into the backend error a dead keyring
 * gives, [crashing] into an exception outside the contract, [droppingWrites] into a keyring that
 * accepts a write and keeps nothing; [probeCrashing] breaks only the availability probe, [onRead]
 * runs inside every read.
 */
private class FakeDeviceSecretStore(var available: Boolean = true) : DeviceSecretStore {
    val entries = mutableMapOf<String, ByteArray>()
    var failing = false
    var crashing = false
    var droppingWrites = false
    var onRead: (() -> Unit)? = null

    var probeCrashing = false

    override fun isAvailable(): Boolean {
        if (probeCrashing) error("native probe blew up")
        return available
    }

    override fun read(name: String): ByteArray? {
        failIfAsked()
        onRead?.invoke()
        return entries[name]?.copyOf()
    }

    override fun write(name: String, secret: ByteArray) {
        failIfAsked()
        if (!droppingWrites) entries[name] = secret.copyOf()
    }

    override fun delete(name: String) {
        failIfAsked()
        entries.remove(name)
    }

    private fun failIfAsked() {
        if (failing) throw DeviceSecretStoreException("keyring is gone")
        if (crashing) error("native call blew up")
    }
}

/**
 * [DeviceKeyringKeyStore] under [VaultBiometrics] with a real [FileVault]: the trusted-device unlock
 * of issue #398 end to end, minus the OS keyring itself.
 */
class DeviceKeyringKeyStoreTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val fs = FakeFileSystem()
    private val prompt = BiometricPrompt(title = "Unlock", cancelLabel = "Cancel")
    private val secrets = FakeDeviceSecretStore()
    private var allowed = true

    private fun vault() = FileVault("/vault.json".toPath(), crypto, deviceId = "device-1", fileSystem = fs, now = { "2026-09-30T00:00:00Z" })
    private fun keyStore() = DeviceKeyringKeyStore(secrets, crypto, allowed = { allowed })
    private fun trust(v: Vault) = VaultBiometrics(v, keyStore(), FileBioArtifactStore("/vault.bio".toPath(), fs), deviceId = "device-1")

    private fun keyringTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `a trusted device opens the vault on a cold start without the password`() = keyringTest {
        vault().apply {
            create("master".toCharArray())
            put("h1", RecordType.HOST, "payload".encodeToByteArray())
            assertEquals(BiometricEnableResult.Enabled, trust(this).enable(prompt))
            lock()
        }

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unlocked, trust(fresh).unlock(prompt))
        assertContentEquals("payload".encodeToByteArray(), fresh.openPayload("h1"))
    }

    @Test
    fun `the keyring holds a key of its own, never the vault key`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        trust(v).enable(prompt)
        val dataKey = v.exportDataKey()!!

        val stored = secrets.entries.values.single()
        assertFalse(stored.contentEquals(dataKey.bytes))
    }

    @Test
    fun `the factor is the device keyring, which proves nobody's presence`() = keyringTest {
        assertEquals(UnlockFactor.DeviceKeyring, keyStore().factor)
    }

    @Test
    fun `unavailable while the setting is off or the keyring is missing`() = keyringTest {
        assertEquals(BiometricAvailability.Available, keyStore().availability())
        allowed = false
        assertEquals(BiometricAvailability.NoHardware, keyStore().availability())
        allowed = true
        secrets.available = false
        assertEquals(BiometricAvailability.NoHardware, keyStore().availability())
    }

    @Test
    fun `turning the setting off stops the unlock even with the artifact still on disk`() = keyringTest {
        vault().apply { create("master".toCharArray()); trust(this).enable(prompt); lock() }
        allowed = false

        val fresh = vault()
        assertEquals(BiometricUnlockResult.Unavailable, trust(fresh).unlock(prompt))
        assertFalse(fresh.isUnlocked)
    }

    @Test
    fun `an entry removed from the keyring drops the trust and asks for the password`() = keyringTest {
        vault().apply { create("master".toCharArray()); trust(this).enable(prompt); lock() }
        secrets.entries.clear() // the user wiped the login keyring, or reinstalled the OS account

        val fresh = vault()
        val bio = trust(fresh)
        assertEquals(BiometricUnlockResult.Invalidated, bio.unlock(prompt))
        assertFalse(fresh.isUnlocked)
        assertFalse(bio.isEnabled())
    }

    @Test
    fun `a keyring that errors falls back to the password and keeps the trust for next time`() = keyringTest {
        vault().apply { create("master".toCharArray()); trust(this).enable(prompt); lock() }
        secrets.failing = true // the Secret Service daemon is down for this session

        val fresh = vault()
        val bio = trust(fresh)
        assertEquals(BiometricUnlockResult.Failed, bio.unlock(prompt))
        assertFalse(fresh.isUnlocked)
        assertTrue(bio.isEnabled(), "a transient outage must not cost the user the setting")
    }

    @Test
    fun `enabling on a keyring that refuses the write leaves nothing behind`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        secrets.failing = true

        val bio = trust(v)
        assertTrue(bio.enable(prompt) != BiometricEnableResult.Enabled)
        assertFalse(bio.isEnabled())
    }

    @Test
    fun `disabling removes the keyring entry`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        val bio = trust(v)
        bio.enable(prompt)
        assertTrue(secrets.entries.isNotEmpty())

        bio.disable()
        assertTrue(secrets.entries.isEmpty())
        assertFalse(bio.isEnabled())
    }

    @Test
    fun `disabling still drops the artifact when the keyring cannot be reached`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        val bio = trust(v)
        bio.enable(prompt)
        secrets.failing = true

        bio.disable()
        // The orphaned keyring entry wraps nothing once vault.bio is gone; the artifact is what counts.
        assertFalse(bio.isEnabled())
    }

    @Test
    fun `a keyring that fails while enabling is a failure, not an unsupported device`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        secrets.failing = true

        val bio = trust(v)
        assertEquals(BiometricEnableResult.Failed, bio.enable(prompt))
        assertFalse(bio.isUnsupported(), "a keyring down for a moment must not hide the setting for good")
    }

    @Test
    fun `a keyring that keeps nothing it was given is a failure, not an unsupported device`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        secrets.droppingWrites = true

        val bio = trust(v)
        assertEquals(BiometricEnableResult.Failed, bio.enable(prompt))
        assertFalse(bio.isUnsupported())
    }

    @Test
    fun `an exception outside the contract falls back to the password instead of escaping`() = keyringTest {
        vault().apply { create("master".toCharArray()); trust(this).enable(prompt); lock() }
        secrets.crashing = true

        val fresh = vault()
        val bio = trust(fresh)
        assertEquals(BiometricUnlockResult.Failed, bio.unlock(prompt))
        assertTrue(bio.isEnabled())
        bio.disable()
        assertFalse(bio.isEnabled())
    }

    @Test
    fun `an exception outside the contract while enabling is a failure`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        secrets.crashing = true

        val bio = trust(v)
        assertEquals(BiometricEnableResult.Failed, bio.enable(prompt))
        assertFalse(bio.isEnabled())
        assertFalse(bio.isUnsupported())
    }

    @Test
    fun `a keyring that cannot even be asked is no keyring`() = keyringTest {
        secrets.probeCrashing = true
        assertEquals(BiometricAvailability.NoHardware, keyStore().availability())
    }

    @Test
    fun `a keyring entry of the wrong length drops the trust instead of crashing`() = keyringTest {
        vault().apply { create("master".toCharArray()); trust(this).enable(prompt); lock() }
        secrets.entries.keys.forEach { secrets.entries[it] = ByteArray(16) { 7 } }

        val fresh = vault()
        val bio = trust(fresh)
        assertEquals(BiometricUnlockResult.Invalidated, bio.unlock(prompt))
        assertFalse(bio.isEnabled())

        fresh.unlock("master".toCharArray())
        assertEquals(BiometricEnableResult.Enabled, bio.enable(prompt), "enabling again replaces the bad entry")
        fresh.lock()
        assertEquals(BiometricUnlockResult.Unlocked, trust(vault()).unlock(prompt))
    }

    @Test
    fun `enabling again keeps the keyring key already there`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        trust(v).enable(prompt)
        val before = secrets.entries.values.single().copyOf()

        assertEquals(BiometricEnableResult.Enabled, trust(v).enable(prompt))
        assertContentEquals(before, secrets.entries.values.single())
    }

    @Test
    fun `a disable the disk refuses keeps the keyring key the trust still needs`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        val disk = FileBioArtifactStore("/vault.bio".toPath(), fs)
        val stuck = object : BioArtifactStore by disk {
            override fun clear(): Unit = error("read-only config directory")
        }
        val bio = VaultBiometrics(v, keyStore(), stuck, deviceId = "device-1")
        bio.enable(prompt)

        assertFailsWith<IllegalStateException> { bio.disable() }
        assertTrue(bio.isEnabled())
        v.lock()
        assertEquals(BiometricUnlockResult.Unlocked, trust(vault()).unlock(prompt), "the trust shown on still works")
    }

    @Test
    fun `a reseal does nothing where no trust is set`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        val bio = trust(v)
        assertNull(bio.reseal(prompt))
        assertFalse(bio.isEnabled())
        assertTrue(secrets.entries.isEmpty())
    }

    @Test
    fun `a trust turned off while it is resealed stays off`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        val bio = trust(v)
        bio.enable(prompt)
        secrets.onRead = {
            secrets.onRead = null
            bio.disable() // the user's toggle, landing between the re-seal's check and its write
        }

        assertNull(bio.reseal(prompt))
        assertFalse(bio.isEnabled())
        assertTrue(secrets.entries.isEmpty())
    }

    @Test
    fun `a reseal seals the trust over the current vault key`() = keyringTest {
        val v = vault().apply { create("master".toCharArray()) }
        val bio = trust(v)
        bio.enable(prompt)

        assertEquals(BiometricEnableResult.Enabled, bio.reseal(prompt))
        v.lock()
        assertEquals(BiometricUnlockResult.Unlocked, trust(vault()).unlock(prompt))
    }
}
