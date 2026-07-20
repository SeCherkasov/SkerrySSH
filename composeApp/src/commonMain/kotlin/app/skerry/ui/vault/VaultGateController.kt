package app.skerry.ui.vault

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.BiometricUnlockResult
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimum master-password length. Higher than the typical "8" (NIST for server passwords with an
 * attempt counter): the vault file is attacked offline with no attempt limit, the only barrier is
 * Argon2id. Single source of truth for both validation and the UI error text.
 */
const val MIN_MASTER_PASSWORD_LENGTH: Int = 12

/**
 * Word the user must type on the reset screen (type-to-confirm) to confirm irreversible vault erasure.
 * Single source for both the UI field and the check — like deleting a GitHub repo: a barrier against
 * an accidental click on a destructive action.
 */
const val RESET_CONFIRM_WORD: String = "RESET"

/** Master-password gate screen over [Vault]. */
enum class VaultGateState {
    /** No vault file yet — show the master-password creation form. */
    NeedsCreate,

    /** Vault exists but is locked — show the unlock form. */
    NeedsUnlock,

    /** Vault file can't be read — dead end: entering a password is pointless, show the reset screen. */
    Corrupted,

    /** User confirms an irreversible reset (forgot password / corrupt file). */
    Resetting,

    /**
     * Vault just created and already open — a one-time offer to connect self-hosted sync in onboarding.
     * Done before [OfferBiometric] on purpose: logging into an existing account adopts its dataKey
     * ([Vault.adoptDataKey]), and if biometrics were already wrapped under the local key, adopting the
     * account key would invalidate it. Connecting (or skipping) sync here means the dataKey is final by
     * the time biometrics enroll. Shown only when the platform provided a sync form
     * ([offersSyncOnboarding]); any outcome leads to [OfferBiometric]/[Unlocked].
     */
    OfferSync,

    /**
     * Vault just created and already open, but before entering the app — a one-time offer to enable
     * biometric unlock. Shown only when biometrics are available on the device; any outcome leads to
     * [Unlocked].
     */
    OfferBiometric,

    /** Vault unlocked — pass through to the rest of the UI. */
    Unlocked,
}

/**
 * What to erase on a vault reset. The vault itself is always deleted (the [Vault.reset] contract); this
 * choice controls only external data not part of the vault file. The user decides on the reset screen;
 * the external cleanup is an injected `onReset` callback (the controller doesn't know about hosts: the
 * gate stays over a single [Vault]).
 */
enum class ResetScope {
    /** Erase only secrets (the vault file). Host profiles and known_hosts remain. */
    SecretsOnly,

    /** Factory reset: vault + host profiles + known_hosts + local settings. */
    Everything,
}

/**
 * Cause of the last attempt's failure. A structured type (not a string) so the text is localized in the
 * UI and tests don't depend on wording.
 */
enum class VaultGateError {
    /** Password shorter than [VaultGateController.minPasswordLength]. */
    PasswordTooShort,

    /** Password and confirmation didn't match. */
    PasswordMismatch,

    /** Wrong master password on unlock. */
    WrongPassword,

    /** Vault file unreadable/corrupt. */
    Corrupted,

    /** Biometrics reset (new fingerprint/face) — it's disabled, the master password is needed. */
    BiometricReset,

    /** Biometric unlock didn't succeed (hardware error / OEM quirk) — fall back to the password. */
    BiometricFailed,

    /** Sensor temporarily locked after too many attempts — wait and use the master password meanwhile. */
    BiometricLockedOut,

    /** This device's secure hardware can't decrypt the vault — biometrics is off, password only (#23). */
    BiometricUnsupported,
}

/**
 * Master-password gate: blocks access to the rest of the UI until [Vault] is unlocked. The start state
 * is chosen by [Vault.exists] — create vs unlock.
 *
 * [Vault] is synchronous (Argon2id derivation is in its impl), but derivation is heavy (m=64 MiB) —
 * [create]/[unlock] move it off the UI thread onto [kdfDispatcher] via [scope] (else ANR risk on
 * Android; modeled on [SecretCopyAuthorizer]); [verifying] is set during the check. Passwords arrive as
 * [CharArray] and are wiped here: [Vault.create]/[Vault.unlock] wipe the passed buffer per contract,
 * and the controller wipes the confirmation and buffers that never reached the vault (finally covers
 * the exception/cancellation path too).
 */
@Stable
class VaultGateController(
    private val vault: Vault,
    private val biometrics: VaultBiometrics? = null,
    private val minPasswordLength: Int = MIN_MASTER_PASSWORD_LENGTH,
    /**
     * External cleanup on reset (hosts/known_hosts/settings per [ResetScope]). Called after
     * [Vault.reset], when the vault is already erased. The controller doesn't know about this data — the
     * platform wiring (desktop `main`) provides it. Defaults to no-op (mock/preview).
     */
    private val onReset: (ResetScope) -> Unit = {},
    /**
     * Whether to offer sync as an onboarding step ([VaultGateState.OfferSync]) right after vault
     * creation. The platform sets `true` when it has a ready sync form (a `SyncCoordinator`). The
     * controller knows nothing about sync — it only decides whether to show the step.
     */
    private val offersSyncOnboarding: Boolean = false,
    /**
     * Local security event log (Settings → Security). `null` — not logging (mock/preview). The
     * controller writes events it owns: master-password create/change, biometrics enable/disable,
     * biometric unlock. It reads the same for the "last password change" caption and recent events.
     */
    private val securityLog: SecurityLog? = null,
    /**
     * Scope for async [create]/[unlock] (Argon2id off the UI thread). Defaults to its own on
     * Main.immediate (the result is Compose snapshot-state, the controller lives with the composition;
     * the dispatcher is touched only on the first create/unlock, so construction is safe without Main).
     * [app.skerry.ui.vault.VaultGate] passes its `rememberCoroutineScope`; tests pass a TestScope.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Dispatcher for heavy Argon2id derivation (m=64 MiB); tests substitute a virtual one. */
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var state: VaultGateState by mutableStateOf(
        if (vault.exists()) VaultGateState.NeedsUnlock else VaultGateState.NeedsCreate,
    )
        private set

    var error: VaultGateError? by mutableStateOf(null)
        private set

    /**
     * Heavy password derivation/check (Argon2id) in [create]/[unlock] is in progress — repeat submits
     * are ignored meanwhile (guard), the UI may disable the button.
     */
    var verifying: Boolean by mutableStateOf(false)
        private set

    /** Where to return if the user cancels the reset screen (unlock form or Corrupted screen). */
    private var resetReturnState: VaultGateState = VaultGateState.NeedsUnlock

    /** Whether biometrics is enabled for this vault (reactive — the toggle updates the UI). */
    var biometricEnabled: Boolean by mutableStateOf(biometrics?.isEnabled() == true)
        private set

    /**
     * The device proved it can't decrypt the vault with a biometrics-protected key (#23). The toggle
     * stays visible but inert, with an explanation and a re-check ([recheckBiometricSupport]) — a
     * silently missing row would read as "Skerry has no biometrics", which isn't what happened.
     */
    var biometricUnsupported: Boolean by mutableStateOf(biometrics?.isUnsupported() == true)
        private set

    /** User activity counter — idle auto-lock restarts when it changes. */
    var activityTick: Int by mutableStateOf(0)
        private set

    /**
     * Whether a biometric prompt is currently in flight. Background auto-lock must skip it: the system
     * prompt may send `ON_STOP`, and locking mid-authentication would leave the user having successfully
     * touched the sensor while the vault stayed locked (nothing left to accept the result).
     */
    var biometricInFlight: Boolean by mutableStateOf(false)
        private set

    /**
     * Create the vault if the password passes validation and matches [confirm]. Both buffers are wiped
     * in any outcome. On a validation error the vault is untouched and the state stays
     * [VaultGateState.NeedsCreate]. Validation is synchronous (error shows immediately); the derivation
     * (Argon2id) is async on [kdfDispatcher] under [verifying] (repeat submit ignored).
     */
    fun create(password: CharArray, confirm: CharArray) {
        if (verifying) {
            password.fill(' ')
            confirm.fill(' ')
            return
        }
        error = null
        when {
            password.size < minPasswordLength -> {
                error = VaultGateError.PasswordTooShort
                password.fill(' ')
                confirm.fill(' ')
            }
            !password.contentEquals(confirm) -> {
                error = VaultGateError.PasswordMismatch
                password.fill(' ')
                confirm.fill(' ')
            }
            else -> {
                verifying = true
                scope.launch {
                    try {
                        withContext(kdfDispatcher) { vault.create(password) }
                        // Baseline for the "last password change" caption in the Security section.
                        securityLog?.record(SecurityEventType.VaultCreated)
                        // New vault is open. First (if the platform provided a form) offer to connect
                        // sync — it may adopt the account dataKey, and biometrics must be wrapped under
                        // the final key. Otherwise go straight to biometrics / into the app.
                        state = when {
                            offersSyncOnboarding -> VaultGateState.OfferSync
                            canEnableBiometric() -> VaultGateState.OfferBiometric
                            else -> VaultGateState.Unlocked
                        }
                    } finally {
                        // finally covers a vault.create exception and coroutine cancellation: verifying
                        // must not stay stuck set, and buffers must not stay un-wiped.
                        verifying = false
                        password.fill(' ')
                        confirm.fill(' ')
                    }
                }
            }
        }
    }

    /**
     * Unlock an existing vault; on error stay on the form with [error]. Argon2id derivation is async on
     * [kdfDispatcher] under [verifying] (repeat submit ignored). The password buffer is wiped in any
     * outcome: [Vault.unlock] wipes it per contract only on a normal return, so the controller also
     * covers the exception/cancellation path (finally).
     */
    fun unlock(password: CharArray) {
        if (verifying) {
            password.fill(' ')
            return
        }
        error = null
        verifying = true
        scope.launch {
            try {
                when (withContext(kdfDispatcher) { vault.unlock(password) }) {
                    UnlockResult.Success -> state = VaultGateState.Unlocked
                    UnlockResult.WrongPassword -> error = VaultGateError.WrongPassword
                    // A corrupt file isn't a form error but a dead end: go to the separate reset screen.
                    UnlockResult.Corrupted -> state = VaultGateState.Corrupted
                }
            } finally {
                verifying = false
                password.fill(' ')
            }
        }
    }

    /** Lock the vault and return to the unlock form. */
    fun lock() {
        vault.lock()
        error = null
        state = VaultGateState.NeedsUnlock
    }

    /**
     * Change the master password (vault already unlocked). Returns `true` if the old password is correct
     * and the password was changed; on success it records [SecurityEventType.MasterPasswordChanged].
     * Both buffers are wiped in any outcome (as in [create]/[unlock]). Min-length/match validation of
     * the new password is done by the calling UI (button enabled only on valid input); the only rejection
     * at this level is a wrong current password.
     *
     * Stays synchronous (unlike [create]/[unlock]): the sole caller — the settings change-password dialog
     * — moves it off the UI thread itself (`withContext(Dispatchers.Default)` in SettingsPanel) and needs
     * a direct Boolean result.
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        try {
            val changed = vault.changePassword(oldPassword, newPassword)
            if (changed) securityLog?.record(SecurityEventType.MasterPasswordChanged)
            return changed
        } finally {
            oldPassword.fill(' ')
            newPassword.fill(' ')
        }
    }

    /** Recent security events (newest first) for the Settings → Security section. */
    fun recentSecurityEvents(limit: Int = 20): List<SecurityEvent> = securityLog?.recent(limit) ?: emptyList()

    /** Time of the last master-password change (or `null` if unknown — show neutral text). */
    fun lastPasswordChangeAt(): String? = securityLog?.lastPasswordChangeAt()

    /**
     * Open the reset confirmation screen (from the unlock form — "forgot password" — or from [Corrupted]).
     * Remembers the current state so [cancelReset] returns exactly to it.
     */
    fun beginReset() {
        resetReturnState = state
        error = null
        state = VaultGateState.Resetting
    }

    /** Cancel the reset — return to the unlock form or Corrupted screen we came from. */
    fun cancelReset() {
        error = null
        state = resetReturnState
    }

    /**
     * Irreversibly reset the vault and start over. Erases the vault file ([Vault.reset]), disables
     * biometrics (`vault.bio` is useless without the vault), then cleans external data on [scope] via
     * [onReset]. Ends at the new-master-password creation form ([VaultGateState.NeedsCreate]).
     */
    fun confirmReset(scope: ResetScope) {
        // vault.reset() already deleted the file — whatever fails next, we must not get stuck in
        // Resetting: the transition to the creation form is guaranteed in finally (on a cold start
        // vault.exists()==false would give NeedsCreate anyway, but this session's screen mustn't hang).
        try {
            vault.reset()
            // disable() is idempotent; its failure must not derail external cleanup and the transition.
            runCatching { biometrics?.disable() }
            // External cleanup is best-effort: its failure (I/O writing hosts.json etc.) must not crash
            // the click handler. The vault is already erased; worst case hosts keep dangling references
            // to secrets (connect just asks for a password), but the app neither crashes nor hangs.
            runCatching { onReset(scope) }
        } finally {
            biometricEnabled = false
            error = null
            state = VaultGateState.NeedsCreate
        }
    }

    /** Record user activity — restarts the idle auto-lock timer. */
    fun touch() {
        activityTick++
    }

    /** Whether biometric unlock can be offered on the unlock form (available and enabled). */
    fun canUnlockWithBiometric(): Boolean =
        biometrics?.let { it.availability() == BiometricAvailability.Available && it.isEnabled() } == true

    /**
     * Whether enabling biometrics can be offered (hardware present, a factor enrolled, and the device
     * not already known to be incapable of it). Onboarding skips the offer when this is false; the
     * settings row also renders on [biometricUnsupported], to explain rather than vanish.
     */
    fun canEnableBiometric(): Boolean =
        biometrics?.let { it.availability() == BiometricAvailability.Available } == true && !biometricUnsupported

    /**
     * Unlock with biometrics. Success → [VaultGateState.Unlocked]. Key invalidation disables biometrics
     * and asks for the password ([VaultGateError.BiometricReset]). Failure/unavailability surface a
     * "use your password" hint; cancellation stays silent. [prompt] (localized strings) comes from the UI.
     */
    suspend fun unlockWithBiometric(prompt: BiometricPrompt) {
        val bio = biometrics ?: return
        error = null
        biometricInFlight = true
        try {
            when (bio.unlock(prompt)) {
                BiometricUnlockResult.Unlocked -> {
                    securityLog?.record(SecurityEventType.UnlockedBiometric)
                    state = VaultGateState.Unlocked
                }
                BiometricUnlockResult.Invalidated -> {
                    biometricEnabled = false
                    error = VaultGateError.BiometricReset
                }
                BiometricUnlockResult.Corrupted -> state = VaultGateState.Corrupted
                // The enclave stopped honouring the key — biometrics is off for good on this device
                // until the user re-checks it (see biometricUnsupported).
                BiometricUnlockResult.Unsupported -> {
                    biometricEnabled = false
                    biometricUnsupported = true
                    error = VaultGateError.BiometricUnsupported
                }
                // A silent failure looks like the tap did nothing — surface a "use your password" hint.
                BiometricUnlockResult.Failed,
                BiometricUnlockResult.Unavailable,
                -> error = VaultGateError.BiometricFailed
                BiometricUnlockResult.LockedOut -> error = VaultGateError.BiometricLockedOut
                // Deliberate dismissal stays silent — a message there would just be noise.
                BiometricUnlockResult.Cancelled,
                BiometricUnlockResult.NotEnabled,
                -> Unit
            }
        } finally {
            biometricInFlight = false
        }
    }

    /**
     * Enable biometrics (vault already unlocked). `true` if enabled. [verifyPrompt] labels the second
     * prompt of the round-trip check ([VaultBiometrics.enable]) — the one that proves this device can
     * actually decrypt the vault; a device that fails it lands in [biometricUnsupported].
     */
    suspend fun enableBiometric(prompt: BiometricPrompt, verifyPrompt: BiometricPrompt = prompt): Boolean {
        val bio = biometrics ?: return false
        biometricInFlight = true
        return try {
            val result = bio.enable(prompt, verifyPrompt)
            // Both answers come off disk; enable() is called straight from a click handler.
            withContext(kdfDispatcher) {
                biometricEnabled = bio.isEnabled()
                biometricUnsupported = bio.isUnsupported()
            }
            if (result == BiometricEnableResult.Enabled) securityLog?.record(SecurityEventType.BiometricEnabled)
            result == BiometricEnableResult.Enabled
        } finally {
            biometricInFlight = false
        }
    }

    /**
     * Drop the "this device can't do biometrics" verdict so the next [enableBiometric] walks the whole
     * hardening ladder again — a ROM update or a reboot can fix an enclave that used to refuse the key.
     * The verdict lives in a file, so the erase runs on [kdfDispatcher], not in the click handler;
     * the row flips immediately (Compose state is safe to write from any thread).
     */
    fun recheckBiometricSupport() {
        val bio = biometrics ?: return
        biometricUnsupported = false
        if (error == VaultGateError.BiometricUnsupported) error = null
        scope.launch {
            biometricUnsupported = withContext(kdfDispatcher) {
                bio.forgetUnsupported()
                bio.isUnsupported()
            }
        }
    }

    /** Disable biometrics (remove the key and `vault.bio`). */
    fun disableBiometric() {
        val bio = biometrics ?: return
        val wasEnabled = bio.isEnabled()
        bio.disable()
        biometricEnabled = bio.isEnabled()
        // Record the event only if biometrics was actually enabled (disable is idempotent).
        if (wasEnabled && !biometricEnabled) securityLog?.record(SecurityEventType.BiometricDisabled)
    }

    /**
     * Finish the sync connect step ([VaultGateState.OfferSync]) — called by the sync form both when the
     * user connected and when they skipped. The dataKey is now final, so move to the biometrics offer
     * (if the device supports it) or straight into the app.
     */
    fun completeSyncOnboarding() {
        if (state != VaultGateState.OfferSync) return
        state = if (canEnableBiometric()) VaultGateState.OfferBiometric else VaultGateState.Unlocked
    }

    /**
     * Finish device pairing by code, started right on the vault creation screen
     * ([VaultGateState.NeedsCreate]). By now the pairing coordinator
     * ([app.skerry.ui.sync.SyncCoordinator.claimPairing]) has created and unlocked the local vault under
     * the chosen password and adopted the account key, so the dataKey is final — biometrics can be
     * wrapped right away. Route through the biometrics offer (if the device supports it) or straight into
     * the app. Deliberately not directly to [VaultGateState.Unlocked] — else the one-time biometrics
     * offer under the final key would be lost. No-op outside [VaultGateState.NeedsCreate].
     */
    fun completePairing() {
        if (state != VaultGateState.NeedsCreate) return
        // The only moment the gate reliably knows the device is linked to an account by code (the pairing
        // coordinator already created/unlocked the vault and adopted the account key, and the form waited
        // for the Online transition). Record the event here, not in the coordinator: all join paths
        // (desktop and mobile, via the shared gate) converge here, exactly where pairing succeeded.
        securityLog?.record(SecurityEventType.DevicePaired)
        state = if (canEnableBiometric()) VaultGateState.OfferBiometric else VaultGateState.Unlocked
    }

    /**
     * Dismiss the one-time biometrics offer after vault creation ([VaultGateState.OfferBiometric]) — let
     * the user into the app whether they enabled biometrics or declined.
     */
    fun dismissBiometricOffer() {
        if (state == VaultGateState.OfferBiometric) state = VaultGateState.Unlocked
    }
}
