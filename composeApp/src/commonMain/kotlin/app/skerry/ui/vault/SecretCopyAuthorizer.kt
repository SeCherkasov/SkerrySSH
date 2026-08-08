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
import app.skerry.ui.generated.resources.vtail_bio_export_subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * What the vault is being asked to hand over. It selects the prompt's wording — the check itself is
 * the same — so a biometric sheet or a password dialog never says "copy to the clipboard" over an
 * action that writes a private key to disk.
 */
internal enum class SecretAccess { COPY, EXPORT }

/**
 * Re-authenticates before a sensitive secret leaves the vault — a password to the clipboard, a
 * private key to a file: an unlocked vault alone shouldn't let anyone take either off an unattended
 * screen. If biometrics are enabled and available, uses the system biometric prompt
 * ([VaultBiometrics.confirm]); otherwise falls back to the master password ([Vault.verifyPassword]).
 * The action runs only after success.
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

    /** What the open prompt is asking for; the dialog words itself from it. */
    var access by mutableStateOf(SecretAccess.COPY)
        private set

    // Deferred action (copy) waiting on password confirmation; unused on the biometric path.
    private var pending: (() -> Unit)? = null

    // A biometric prompt is already in flight; suppresses repeat taps (double prompt/double copy).
    private var biometricInFlight = false

    /**
     * Requests authorization before [onAuthorized] (the copy or export action). Uses biometrics if
     * enabled and available; cancel/failure there aborts without falling back to the password form.
     * Any other biometric outcome (not enabled/unavailable/invalidated/hardware error) falls back to
     * the password form. Repeat calls while a prompt is in flight are ignored. [access] only words
     * the prompt.
     */
    fun authorize(access: SecretAccess = SecretAccess.COPY, onAuthorized: () -> Unit) {
        val bio = biometrics
        if (bio != null && bio.isEnabled() && bio.availability() == BiometricAvailability.Available) {
            // Assigned only past this guard: a second tap while a prompt is in flight is dropped, and
            // re-wording the open prompt for a request that was never started would put "Export" over
            // a pending clipboard copy.
            if (biometricInFlight) return
            this.access = access
            biometricInFlight = true
            scope.launch {
                // Reset in finally: coroutine cancellation (screen left/vault locked mid-prompt) would
                // otherwise leave biometricInFlight stuck true and silently drop all later authorize() calls.
                val result = try {
                    val prompt = BiometricPrompt(
                        title = getString(Res.string.vtail_bio_copy_title),
                        cancelLabel = getString(Res.string.vtail_bio_copy_cancel),
                        subtitle = getString(
                            when (access) {
                                SecretAccess.COPY -> Res.string.vtail_bio_copy_subtitle
                                SecretAccess.EXPORT -> Res.string.vtail_bio_export_subtitle
                            },
                        ),
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
                    else -> requirePassword(access, onAuthorized)
                }
            }
        } else {
            requirePassword(access, onAuthorized)
        }
    }

    private fun requirePassword(access: SecretAccess, onAuthorized: () -> Unit) {
        this.access = access
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
