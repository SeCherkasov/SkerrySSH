package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.UnlockFactor
import app.skerry.shared.vault.VaultBiometrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the trusted device of issue #398 is in force: set up *and* able to open the vault now. A
 * trust the keyring cannot serve (keyring gone, flag off) leaves the user typing the password, and
 * then every lock has to stay. Availability is a native probe, so it is taken off the UI thread;
 * until it answers the trust is not in force — the locks show a moment longer rather than vanish unearned.
 */
@Composable
internal fun rememberTrustActive(biometrics: VaultBiometrics?): Boolean {
    if (biometrics == null || biometrics.factor != UnlockFactor.DeviceKeyring) return false
    val enabled by biometrics.enabled.collectAsState()
    val available by produceState(false, biometrics, enabled) {
        value = enabled && withContext(Dispatchers.Default) { biometrics.availability() == BiometricAvailability.Available }
    }
    return enabled && available
}
