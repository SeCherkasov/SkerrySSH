package app.skerry.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_biometric_prompt_cancel
import app.skerry.ui.generated.resources.settings_security_biometric_recheck
import app.skerry.ui.generated.resources.settings_security_trusted_device
import app.skerry.ui.generated.resources.settings_security_trusted_device_confirm
import app.skerry.ui.generated.resources.settings_security_trusted_device_confirm_button
import app.skerry.ui.generated.resources.settings_security_trusted_device_desc
import app.skerry.ui.generated.resources.settings_security_trusted_device_missing
import app.skerry.ui.generated.resources.settings_security_trusted_device_refused
import app.skerry.ui.generated.resources.vault_password_mismatch_retry
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.PasswordConfirmDialog
import app.skerry.ui.vault.TrustEnableResult
import app.skerry.ui.vault.VaultGateController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The trusted-device row of issue #398, in place of the biometrics one on desktop. Offered only once
 * Settings → Experimental turns it on, and kept while a trust is in place whatever the flag says, so
 * the way out is always on screen. Turning it on goes through [onRequestTrust] — the master-password
 * dialog, drawn by the settings card over its scroll ([TrustDeviceDialog]); no keyring on this machine
 * leaves the row inert, saying so.
 */
@Composable
internal fun TrustedDeviceRow(
    state: DesktopDesignState,
    controller: VaultGateController?,
    on: Boolean,
    onRequestTrust: () -> Unit,
    onToggled: () -> Unit,
) {
    if (controller == null || (!on && !state.settings.trustedDeviceOffered)) return
    val scope = rememberCoroutineScope()
    val title = stringResource(Res.string.settings_security_trusted_device)
    // A native probe (D-Bus on Linux): asked off the UI thread, and the row waits for the answer.
    val canEnable by produceState<Boolean?>(null, controller, controller.biometricUnsupported) {
        value = withContext(Dispatchers.Default) { controller.canEnableBiometric() }
    }
    when {
        on || canEnable == true -> SettingToggleRow(
            title,
            stringResource(Res.string.settings_security_trusted_device_desc),
            on = on,
            onToggle = {
                if (controller.biometricInFlight) return@SettingToggleRow
                if (on) {
                    scope.launch {
                        controller.disableBiometric()
                        onToggled()
                    }
                } else {
                    onRequestTrust()
                }
            },
        )
        canEnable == false -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Txt(title, color = Skerry.colors.faint, size = 13.sp, weight = FontWeight.Medium)
                Txt(
                    stringResource(Res.string.settings_security_trusted_device_missing),
                    color = Skerry.colors.dim,
                    size = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (controller.biometricUnsupported) {
                GhostButton(
                    stringResource(Res.string.settings_security_biometric_recheck),
                    onClick = { controller.recheckBiometricSupport() },
                )
            }
        }
        else -> return
    }
    HLine()
}

/**
 * The master password before a trust is set. A full-screen overlay: it belongs to the settings card,
 * outside its scroll, like the change-password dialog.
 */
@Composable
internal fun TrustDeviceDialog(controller: VaultGateController, onClose: () -> Unit, onEnabled: () -> Unit) {
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<TrustEnableResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The keyring shows no dialog; the prompt only satisfies the shared enable() signature.
    val prompt = BiometricPrompt(
        title = stringResource(Res.string.settings_security_trusted_device),
        cancelLabel = stringResource(Res.string.more_biometric_prompt_cancel),
    )
    PasswordConfirmDialog(
        subtitle = stringResource(Res.string.settings_security_trusted_device_confirm),
        confirmLabel = stringResource(Res.string.settings_security_trusted_device_confirm_button),
        errorText = when (outcome) {
            TrustEnableResult.WrongPassword -> stringResource(Res.string.vault_password_mismatch_retry)
            TrustEnableResult.Failed -> stringResource(Res.string.settings_security_trusted_device_refused)
            TrustEnableResult.Enabled, null -> null
        },
        busy = busy,
        onDismiss = onClose,
        onConfirm = { password ->
            busy = true
            scope.launch {
                val result = try {
                    controller.enableTrustedDevice(password.toCharArray(), prompt)
                } finally {
                    busy = false
                }
                outcome = result
                if (result == TrustEnableResult.Enabled) {
                    onEnabled()
                    onClose()
                }
            }
        },
    )
}
