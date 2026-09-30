package app.skerry.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.DeviceKeyringKeyStore
import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.FileBioArtifactStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopSettingsState
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_experimental_trusted_device
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_lock_minimised
import app.skerry.ui.generated.resources.settings_security_trusted_device
import app.skerry.ui.generated.resources.settings_security_trusted_device_confirm_button
import app.skerry.ui.generated.resources.vault_field_master_password
import app.skerry.ui.generated.resources.vault_password_mismatch_retry
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.vault.VaultGateController
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings side of the trusted device (issue #398): the experimental flag is the way in and the
 * way out, and a trust in place hides the lock settings it makes meaningless.
 */
@OptIn(ExperimentalTestApi::class)
class TrustedDeviceSettingsTest {

    private val dir = Files.createTempDirectory("skerry-settings-trust")
    private val crypto = IonspinVaultCrypto()
    private val secrets = InMemorySecrets()
    private var flag = true
    private var trustDialogOpen by mutableStateOf(false)
    private val vault = FileVault(dir.resolve("vault.json").toString().toPath(), crypto, "device-1", FileSystem.SYSTEM) { TS }
    private val biometrics = VaultBiometrics(
        vault,
        DeviceKeyringKeyStore(secrets, crypto, allowed = { flag }),
        FileBioArtifactStore(dir.resolve("vault.bio").toString().toPath(), FileSystem.SYSTEM),
        deviceId = "device-1",
    )

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `turning the flag off drops a trust already given`() = runComposeUiTest {
        trusted()
        val state = DesktopDesignState(DesktopSettingsState(initialTrustedDeviceOffered = true, onTrustedDeviceOfferedChange = { flag = it }))
        show { controller -> ExperimentalSection(state, controller) }

        onNodeWithContentDescription(string(Res.string.settings_experimental_trusted_device)).performClick()
        waitUntil { !biometrics.isEnabled() }

        assertFalse(state.settings.trustedDeviceOffered)
        assertTrue(secrets.isEmpty(), "the keyring entry goes with it")
    }

    @Test
    fun `a trust in place hides the lock settings, and they return with it gone`() = runComposeUiTest {
        trusted()
        val state = DesktopDesignState(DesktopSettingsState(initialTrustedDeviceOffered = true))
        show { controller -> SecurityPane(state, controller) }

        waitUntil { count(Res.string.settings_security_auto_lock) == 0 }
        assertEquals(0, count(Res.string.settings_security_lock_minimised))

        runOnIdle { biometrics.disable() }
        waitUntil { count(Res.string.settings_security_auto_lock) == 1 }
        assertEquals(1, count(Res.string.settings_security_lock_minimised))
    }

    @Test
    fun `a trust the keyring cannot serve keeps the lock settings`() = runComposeUiTest {
        trusted()
        secrets.available = false // the keyring daemon is gone: the vault waits for the password again
        val state = DesktopDesignState(DesktopSettingsState(initialTrustedDeviceOffered = true))
        show { controller -> SecurityPane(state, controller) }

        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(1, count(Res.string.settings_security_auto_lock))
        assertEquals(1, count(Res.string.settings_security_lock_minimised))
    }

    @Test
    fun `the trust is set only with the master password`() = runComposeUiTest {
        unlocked()
        val state = DesktopDesignState(DesktopSettingsState(initialTrustedDeviceOffered = true))
        show { controller -> SecurityPane(state, controller) }
        waitUntil { count(Res.string.settings_security_trusted_device) == 1 }

        onNodeWithContentDescription(string(Res.string.settings_security_trusted_device)).performClick()
        waitForIdle()
        assertFalse(biometrics.isEnabled(), "the toggle alone sets nothing")

        onField(Res.string.vault_field_master_password).performTextInput("wrong password")
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitUntil { count(Res.string.vault_password_mismatch_retry) == 1 }
        assertFalse(biometrics.isEnabled())
        onNode(
            hasContentDescription(string(Res.string.vault_password_mismatch_retry)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        ).assertExists("a screen reader hears the refusal")

        onField(Res.string.vault_field_master_password).performTextClearance()
        onField(Res.string.vault_field_master_password).performTextInput(PASSWORD)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitUntil { biometrics.isEnabled() }
        waitUntil { count(Res.string.settings_security_trusted_device_confirm_button) == 0 }
    }

    @Test
    fun `security offers the trust only once the flag does`() = runComposeUiTest {
        unlocked()
        val state = DesktopDesignState(DesktopSettingsState(initialTrustedDeviceOffered = false))
        show { controller -> SecurityPane(state, controller) }

        assertEquals(0, count(Res.string.settings_security_trusted_device))
        runOnIdle { state.settings.toggleTrustedDeviceOffered() }
        waitUntil { count(Res.string.settings_security_trusted_device) == 1 }
    }

    private fun ComposeUiTest.show(
        section: @androidx.compose.runtime.Composable (VaultGateController) -> Unit,
    ) {
        val controller = VaultGateController(vault, biometrics)
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalVaultBiometrics provides biometrics,
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    // Laid out like the real settings card: the section scrolls, dialogs sit over it.
                    Box {
                        Column(Modifier.verticalScroll(rememberScrollState())) { section(controller) }
                        if (trustDialogOpen) TrustDeviceDialog(controller, onClose = { trustDialogOpen = false }, onEnabled = {})
                    }
                }
            }
        }
        waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun SecurityPane(state: DesktopDesignState, controller: VaultGateController) = SecuritySection(
        state = state,
        controller = controller,
        reload = 0,
        syncConfigured = false,
        onChangeMasterPassword = {},
        onChangeAccountPassword = {},
        onBiometricToggled = {},
        onTrustDevice = { trustDialogOpen = true },
    )

    private fun ComposeUiTest.count(label: org.jetbrains.compose.resources.StringResource) =
        onAllNodesWithText(string(label)).fetchSemanticsNodes().size

    private fun unlocked() = runBlocking {
        initializeVaultCrypto()
        vault.create(PASSWORD.toCharArray())
    }

    private fun trusted() {
        unlocked()
        runBlocking { assertEquals(BiometricEnableResult.Enabled, biometrics.enable(PROMPT)) }
    }
}

/** The OS keyring, in memory. */
private class InMemorySecrets : DeviceSecretStore {
    private val entries = mutableMapOf<String, ByteArray>()
    var available = true
    fun isEmpty() = entries.isEmpty()
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
private const val PASSWORD = "correct horse battery"
private const val TS = "2026-09-30T00:00:00Z"
