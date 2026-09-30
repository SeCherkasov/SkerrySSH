package app.skerry.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.DeviceKeyringKeyStore
import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.FileBioArtifactStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.app.LocalManualLockOffered
import app.skerry.ui.desktop.WithTestLifecycle
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate on a trusted device (issue #398), over a real vault and the real keyring key store — only
 * the OS keyring is in memory. The user chose "always automatic": the vault opens with nobody asked,
 * so every lock that would follow is gone too, and the manual one is not offered.
 */
@OptIn(ExperimentalTestApi::class)
class VaultGateTrustedDeviceTest {

    private val dir = Files.createTempDirectory("skerry-gate-trust")
    private val crypto = IonspinVaultCrypto()
    private val secrets = MemorySecrets()

    // The vault reopens itself right after a lock, so the screen alone can't tell one happened.
    private var locks = 0

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a trusted device opens the gate without the password`() = runComposeUiTest {
        trustedGate()

        onNodeWithText(UNLOCKED).assertIsDisplayed()
        onNodeWithText(NO_MANUAL_LOCK).assertIsDisplayed()
    }

    @Test
    fun `without the trust the gate waits for the password`() = runComposeUiTest {
        val vault = lockedVault(trusted = false)
        setContent { WithTestLifecycle { Gate(vault, biometrics(vault)) } }

        waitUntil { onAllNodesWithText(LOCKED).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodesWithText(UNLOCKED).fetchSemanticsNodes().size)
    }

    @Test
    fun `a trusted device keeps the vault open through silence`() = runComposeUiTest {
        trustedGate()

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 3)

        onNodeWithText(UNLOCKED).assertIsDisplayed()
        assertEquals(0, locks)
    }

    @Test
    fun `a trusted device keeps the vault open when the window is minimised`() = runComposeUiTest {
        val lifecycle = trustedGate()

        runOnIdle { lifecycle.currentState = Lifecycle.State.CREATED } // ON_STOP
        waitForIdle()

        onNodeWithText(UNLOCKED).assertIsDisplayed()
        assertEquals(0, locks)
    }

    @Test
    fun `a trust the keyring cannot serve keeps every lock`() = runComposeUiTest {
        val vault = lockedVault(trusted = true)
        secrets.available = false // the keyring daemon is gone: the password opens the vault instead
        setContent { WithTestLifecycle { Gate(vault, biometrics(vault), typePassword = true) } }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(UNLOCKED).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText(MANUAL_LOCK).assertIsDisplayed()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(IDLE_MS * 3)
        waitForIdle()
        // The stub form types the password again after each lock, so silence can lock more than once.
        assertTrue(locks > 0, "the idle lock still fires")
    }

    /** Mounts the gate over a trusted, locked vault and waits for it to open by itself. */
    private fun ComposeUiTest.trustedGate(): LifecycleRegistry {
        val owner = object : LifecycleOwner {
            override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        }.also { it.lifecycle.currentState = Lifecycle.State.STARTED }
        val vault = lockedVault(trusted = true)
        val biometrics = biometrics(vault)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { Gate(vault, biometrics) }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(UNLOCKED).fetchSemanticsNodes().isNotEmpty() }
        return owner.lifecycle
    }

    @Composable
    private fun Gate(vault: FileVault, biometrics: VaultBiometrics, typePassword: Boolean = false) {
        VaultGate(
            vault = vault,
            biometrics = biometrics,
            autoLockIdleMs = IDLE_MS,
            lockOnBackground = true,
            onBeforeLock = { locks++ },
            createForm = { _, _, _ -> Text("create") },
            unlockForm = { _, _, onUnlock, _, _ ->
                if (typePassword) LaunchedEffect(Unit) { onUnlock(PASSWORD.toCharArray()) }
                Text(LOCKED)
            },
        ) {
            Column {
                Text(UNLOCKED)
                Text(if (LocalManualLockOffered.current) MANUAL_LOCK else NO_MANUAL_LOCK)
            }
        }
    }

    private fun vault() = FileVault(dir.resolve("vault.json").toString().toPath(), crypto, "device-1", FileSystem.SYSTEM) { TS }

    private fun biometrics(vault: FileVault) = VaultBiometrics(
        vault,
        DeviceKeyringKeyStore(secrets, crypto),
        FileBioArtifactStore(dir.resolve("vault.bio").toString().toPath(), FileSystem.SYSTEM),
        deviceId = "device-1",
    )

    /** A vault created, trusted when [trusted], and locked — what a cold start finds on disk. */
    private fun lockedVault(trusted: Boolean): FileVault = runBlocking {
        initializeVaultCrypto()
        vault().apply {
            create(PASSWORD.toCharArray())
            if (trusted) assertEquals(BiometricEnableResult.Enabled, biometrics(this).enable(PROMPT))
            lock()
        }
        vault()
    }
}

/** The OS keyring, in memory. */
private class MemorySecrets : DeviceSecretStore {
    private val entries = mutableMapOf<String, ByteArray>()
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

private val PROMPT = BiometricPrompt(title = "Trust", cancelLabel = "Cancel")
private const val IDLE_MS = 60_000L
private const val PASSWORD = "correct horse battery"
private const val TS = "2026-09-30T00:00:00Z"
private const val UNLOCKED = "session"
private const val LOCKED = "locked"
private const val MANUAL_LOCK = "manual lock offered"
private const val NO_MANUAL_LOCK = "no manual lock"
