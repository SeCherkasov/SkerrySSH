package app.skerry.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricConfirmResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_bio_copy_cancel
import app.skerry.ui.generated.resources.vtail_bio_copy_subtitle
import app.skerry.ui.generated.resources.vtail_bio_copy_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Re-authenticates before copying a sensitive secret (password) to the clipboard: an unlocked
 * vault alone shouldn't let anyone copy a password from an unattended screen. If biometrics are
 * enabled and available, uses the system biometric prompt ([VaultBiometrics.confirm]); otherwise
 * falls back to the master password ([Vault.verifyPassword]). The action runs only after success.
 *
 * Shared by desktop ([app.skerry.ui.vault.VaultView]) and mobile ([app.skerry.ui.mobile.MobileVaultView])
 * keychains; desktop has no biometrics (`biometrics == null`), so it always falls back to password.
 * Password-form state is held here as Compose snapshot state; instantiated via
 * `remember(vault, biometrics, scope)`.
 *
 * [kdfDispatcher] moves the expensive password check (Argon2id, m=64 MiB) off the UI thread;
 * tests substitute a virtual dispatcher.
 */
internal class SecretCopyAuthorizer(
    private val vault: Vault?,
    private val biometrics: VaultBiometrics?,
    private val scope: CoroutineScope,
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Whether the master-password form is shown (biometrics unavailable/failed). */
    var passwordPromptVisible by mutableStateOf(false)
        private set

    /** Entered master password didn't match; form stays open and shows an error. */
    var passwordError by mutableStateOf(false)
        private set

    /** Password check (Argon2id) in progress; the confirm button is disabled meanwhile. */
    var verifying by mutableStateOf(false)
        private set

    // Deferred action (copy) waiting on password confirmation; unused on the biometric path.
    private var pending: (() -> Unit)? = null

    // A biometric prompt is already in flight; suppresses repeat taps (double prompt/double copy).
    private var biometricInFlight = false

    /**
     * Requests authorization before [onAuthorized] (the copy action). Uses biometrics if enabled
     * and available; cancel/failure there aborts without falling back to the password form. Any
     * other biometric outcome (not enabled/unavailable/invalidated/hardware error) falls back to
     * the password form. Repeat calls while a prompt is in flight are ignored.
     */
    fun authorize(onAuthorized: () -> Unit) {
        val bio = biometrics
        if (bio != null && bio.isEnabled() && bio.availability() == BiometricAvailability.Available) {
            if (biometricInFlight) return
            biometricInFlight = true
            scope.launch {
                // Reset in finally: coroutine cancellation (screen left/vault locked mid-prompt) would
                // otherwise leave biometricInFlight stuck true and silently drop all later authorize() calls.
                val result = try {
                    val prompt = BiometricPrompt(
                        title = getString(Res.string.vtail_bio_copy_title),
                        cancelLabel = getString(Res.string.vtail_bio_copy_cancel),
                        subtitle = getString(Res.string.vtail_bio_copy_subtitle),
                    )
                    // confirm() may throw on some devices; fall back to password instead of crashing.
                    // CancellationException is rethrown rather than swallowed, so cancellation doesn't
                    // keep running on a dead scope and pop the password form out of nowhere.
                    try {
                        bio.confirm(prompt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } finally {
                    biometricInFlight = false
                }
                when (result) {
                    BiometricConfirmResult.Confirmed -> onAuthorized()
                    BiometricConfirmResult.Cancelled, BiometricConfirmResult.Failed -> Unit
                    // NotEnabled/Unavailable/Invalidated, and exceptions (null): fall back to password.
                    else -> requirePassword(onAuthorized)
                }
            }
        } else {
            requirePassword(onAuthorized)
        }
    }

    private fun requirePassword(onAuthorized: () -> Unit) {
        pending = onAuthorized
        passwordError = false
        passwordPromptVisible = true
    }

    /** Verifies the master password; on success closes the form and runs the deferred copy, else sets an error. */
    fun submitPassword(password: String) {
        if (verifying) return
        verifying = true
        scope.launch {
            // Argon2id is expensive; offloaded off the UI thread. Reset in finally: cancellation during
            // the check would otherwise leave verifying stuck true and silently drop later submitPassword() calls.
            val ok = try {
                withContext(kdfDispatcher) { vault?.verifyPassword(password.toCharArray()) == true }
            } finally {
                verifying = false
            }
            if (ok) {
                val run = pending
                dismiss()
                run?.invoke()
            } else {
                passwordError = true
            }
        }
    }

    /** Closes the password form and clears the deferred action (Cancel/tap outside). */
    fun dismiss() {
        passwordPromptVisible = false
        passwordError = false
        pending = null
    }
}
