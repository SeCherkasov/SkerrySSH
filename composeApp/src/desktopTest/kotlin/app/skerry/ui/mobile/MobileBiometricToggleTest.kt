package app.skerry.ui.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.skerry.shared.vault.BioArtifact
import app.skerry.shared.vault.BioArtifactStore
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricKeyHardening
import app.skerry.shared.vault.BiometricKeyStore
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.BiometricResult
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.VaultRecord
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_security_biometric
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onAllNodesWithContentDescription

/**
 * The phone's "unlock with biometrics" switch, against the artifact it is supposed to write.
 *
 * The enrolment itself is covered on the controller and on [VaultBiometrics]; what is not is whether
 * the switch on this screen reaches either. That link decides whether a user who flipped it can
 * actually open the vault with a fingerprint — and, flipping it back, whether the wrapped key is
 * really destroyed rather than the row merely greyed out.
 *
 * Rendered on its own rather than through [app.skerry.ui.desktop.runMobileShell]: the screen needs a
 * vault and a key store behind it and the shared shell seeds neither. The key material is real (an
 * [IonspinVaultCrypto] data key); only the enclave and the artifact file are stood in for, so no
 * hardware and no disk are involved.
 */
@OptIn(ExperimentalTestApi::class)
class MobileBiometricToggleTest {

    @Test
    fun `the switch enrols the vault and what lands on disk is not the key`() {
        val artifacts = MemoryArtifacts()
        val keyStore = MemoryKeyStore()
        securityScreen(artifacts, keyStore) {
            onSwitch().assertIsOff().performClick()
            // Waited for on the switch, not on the artifact: the controller writes the artifact and
            // only then re-reads its own flag on another dispatcher, so an artifact-keyed wait can
            // return while the row is still off.
            waitUntil { switchIsOn() }
            assertTrue(artifacts.exists())
        }
        assertTrue(keyStore.wrapCalls > 0, "the switch never asked the key store to wrap anything")
        val stored = artifacts.read()!!.wrappedBio
        assertTrue(stored.isNotEmpty())
        // The point of the enclave: the artifact holds the wrapping, never the key it wraps. Compared
        // against what the store was handed, since DataKey's bytes are internal to `shared`.
        assertFalse(stored.contentEquals(keyStore.lastPlaintext!!), "the data key itself was written to the artifact")
    }

    @Test
    fun `switching it off destroys the wrapped key`() {
        val artifacts = MemoryArtifacts()
        val keyStore = MemoryKeyStore()
        securityScreen(artifacts, keyStore) {
            onSwitch().performClick()
            waitUntil { switchIsOn() }

            onSwitch().performClick()
            waitUntil { !artifacts.exists() }
            waitForIdle()
            // The row has to follow the artifact: a switch stuck on over a deleted key tells the user
            // they can unlock with a fingerprint when they no longer can.
            onSwitch().assertIsOff()
        }
        assertTrue(keyStore.deleted.isNotEmpty(), "the key was left in the enclave with nothing pointing at it")
    }

    /** The switch's own state, without scrolling on every poll of a [waitUntil]. */
    private fun ComposeUiTest.switchIsOn(): Boolean =
        onAllNodesWithContentDescription(string(Res.string.settings_security_biometric))
            .filter(isOn())
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun ComposeUiTest.onSwitch() =
        onNodeWithContentDescription(string(Res.string.settings_security_biometric)).performScrollTo()

    private fun securityScreen(
        artifacts: BioArtifactStore,
        keyStore: BiometricKeyStore,
        body: ComposeUiTest.() -> Unit,
    ) {
        val vault = UnlockedVault(runBlocking { initializeVaultCrypto(); IonspinVaultCrypto().newDataKey() })
        val biometrics = VaultBiometrics(vault, keyStore, artifacts, deviceId = DEVICE)
        // Hoisted out of the composable: built inside, a recomposition would hand the screen a new
        // one and quietly drop whatever the last one recorded.
        val state = MobileDesignState()
        runForm({
            CompositionLocalProvider(
                LocalVault provides vault,
                LocalVaultBiometrics provides biometrics,
            ) {
                MobileSecurityScreen(state)
            }
        }) { body() }
    }
}

private const val DEVICE = "test-device"

/** An unlocked vault that holds no records and exports a real data key — all enrolment needs of it. */
private class UnlockedVault(private val dataKey: DataKey) : Vault {
    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
    // The interface says a copy, and enable() zeroes what it is handed — but DataKey's bytes are
    // internal to `shared`, so this fake cannot make one. Each test therefore enrols exactly once;
    // a second enrolment in the same test would wrap a zeroed key.
    override fun exportDataKey(): DataKey = dataKey
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
    override fun lock() = Unit
    override fun reset() = Unit
    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    override fun verifyPassword(password: CharArray): Boolean = true
}

/** The artifact file, in memory. */
private class MemoryArtifacts : BioArtifactStore {
    // Written on the enrolment's dispatcher, read from the test thread.
    @Volatile
    private var held: BioArtifact? = null
    override fun exists(): Boolean = held != null
    override fun read(): BioArtifact? = held
    override fun write(artifact: BioArtifact) { held = artifact }
    override fun clear() { held = null }
}

/**
 * The enclave, in memory: wrapping XORs with a pad the fake keeps to itself, so what reaches the
 * artifact is never the data key — the shape the real store guarantees, without hardware or a prompt.
 */
private class MemoryKeyStore : BiometricKeyStore {
    // All written on the enrolment's dispatcher and read from the test thread.
    @Volatile
    var wrapCalls = 0
        private set

    /** What the store was last asked to wrap — the artifact must never hold this. */
    @Volatile
    var lastPlaintext: ByteArray? = null
        private set
    val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val pads = mutableMapOf<String, ByteArray>()

    override fun availability(): BiometricAvailability = BiometricAvailability.Available

    override suspend fun ensureKey(alias: String, hardening: BiometricKeyHardening): Boolean {
        pads.getOrPut(alias) { ByteArray(PAD_SIZE) { i -> (i * 7 + 13).toByte() } }
        return true
    }

    override suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> {
        wrapCalls++
        lastPlaintext = plaintext.copyOf()
        return BiometricResult.Success(xor(alias, plaintext))
    }

    override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        BiometricResult.Success(xor(alias, wrapped))

    override fun deleteKey(alias: String) {
        deleted += alias
        pads.remove(alias)
    }

    private fun xor(alias: String, bytes: ByteArray): ByteArray {
        val pad = pads.getValue(alias)
        return ByteArray(bytes.size) { (bytes[it].toInt() xor pad[it % pad.size].toInt()).toByte() }
    }
}

private const val PAD_SIZE = 32
