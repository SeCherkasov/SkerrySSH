package app.skerry.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.skerry.shared.vault.UnlockFactor
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.design.HLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_experimental_jump_shell
import app.skerry.ui.generated.resources.settings_experimental_jump_shell_desc
import app.skerry.ui.generated.resources.settings_experimental_trusted_device
import app.skerry.ui.generated.resources.settings_experimental_trusted_device_desc
import app.skerry.ui.vault.VaultGateController
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Settings → Experimental: features under test, each off until this device turns it on. */
@Composable
internal fun ExperimentalSection(state: DesktopDesignState, controller: VaultGateController?) {
    Column(Modifier.fillMaxWidth()) {
        // Offers "type ssh on the jump host" in the connection form, and lets such profiles connect.
        SettingToggleRow(
            stringResource(Res.string.settings_experimental_jump_shell),
            stringResource(Res.string.settings_experimental_jump_shell_desc),
            on = state.settings.jumpViaShellOffered,
            onToggle = state.settings::toggleJumpViaShellOffered,
        )
        // Only where the vault's fast unlock is the OS keyring (desktop); the switch itself turns
        // nothing on — it offers the row in Security. Turning it off drops a trust already given:
        // otherwise the flag would hide the setting while the vault kept opening without a password.
        if (LocalVaultBiometrics.current?.factor == UnlockFactor.DeviceKeyring) {
            val scope = rememberCoroutineScope()
            HLine()
            SettingToggleRow(
                stringResource(Res.string.settings_experimental_trusted_device),
                stringResource(Res.string.settings_experimental_trusted_device_desc),
                on = state.settings.trustedDeviceOffered,
                onToggle = {
                    state.settings.toggleTrustedDeviceOffered()
                    if (!state.settings.trustedDeviceOffered) scope.launch { controller?.disableBiometric() }
                },
            )
        }
    }
}
