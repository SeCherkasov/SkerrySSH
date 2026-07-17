package app.skerry.shared.vault

/** Outcome of enabling biometrics for a vault. */
enum class BiometricEnableResult {
    /** Biometrics enabled: `vault.bio` written. */
    Enabled,

    /** Vault is locked — nothing to wrap; unlock with password first. */
    VaultLocked,

    /** Biometrics unavailable (no hardware/not enrolled/locked out) — toggle shouldn't reach here. */
    Unavailable,

    /** User dismissed the prompt. */
    Cancelled,

    /** Biometric/hardware failure — not enabled. */
    Failed,
}

/** Outcome of unlocking the vault with biometrics. */
sealed interface BiometricUnlockResult {
    /** Vault unlocked with the same `dataKey`. */
    data object Unlocked : BiometricUnlockResult

    /** Biometrics not enabled for this vault (no `vault.bio`) — show the password form. */
    data object NotEnabled : BiometricUnlockResult

    /** Biometrics unavailable right now (no hardware/locked out) — password form. */
    data object Unavailable : BiometricUnlockResult

    /** User dismissed the prompt — stay on the password form. */
    data object Cancelled : BiometricUnlockResult

    /** Biometric failure — password form. */
    data object Failed : BiometricUnlockResult

    /** Sensor temporarily locked out (too many attempts) — password form, tell the user to wait. */
    data object LockedOut : BiometricUnlockResult

    /**
     * `bioKey` invalidated (new fingerprint/face). Biometrics is disabled (artifact removed) —
     * the user must sign in with the master password and re-enable biometrics if desired.
     */
    data object Invalidated : BiometricUnlockResult

    /** Vault file unreadable — biometrics unwrapped the key but the data is corrupt. */
    data object Corrupted : BiometricUnlockResult
}

/**
 * Outcome of biometrically confirming identity before a sensitive action in an unlocked vault
 * (copying a password). Unlike [BiometricUnlockResult], this does not unlock the vault — it only
 * proves the owner's presence via the same `bioKey`.
 */
sealed interface BiometricConfirmResult {
    /** Biometrics passed — the action may proceed. */
    data object Confirmed : BiometricConfirmResult

    /** Biometrics not enabled for this vault (no `vault.bio`) — caller falls back to the password. */
    data object NotEnabled : BiometricConfirmResult

    /** Biometrics unavailable right now (no hardware/locked out) — fall back to the password. */
    data object Unavailable : BiometricConfirmResult

    /** User dismissed the prompt — action is not performed. */
    data object Cancelled : BiometricConfirmResult

    /** Biometric failure — action is not performed. */
    data object Failed : BiometricConfirmResult

    /**
     * `bioKey` invalidated (new fingerprint/face). Biometrics is disabled (as in [unlock]) —
     * caller falls back to the master password.
     */
    data object Invalidated : BiometricConfirmResult
}

/**
 * Orchestrates biometric unlock on top of [Vault] + [BiometricKeyStore] + [BioArtifactStore].
 * Platform-independent (contract lives in `commonMain`), covered by TDD on fakes without hardware.
 *
 * Zero-knowledge invariant: `dataKey` is obtained from the vault only via [Vault.exportDataKey]
 * (a copy, zeroized here after wrapping) and returned via [Vault.unlockWithDataKey] — it never
 * leaves `shared` in the open. Because `dataKey` itself is wrapped, changing the master password
 * ([Vault.changePassword]) does not touch `vault.bio` — biometrics keeps working without
 * reconfiguration (see design doc section 2).
 *
 * `alias` is deterministic from [deviceId] — one `bioKey` per device. `wrap`/`unwrap` are called
 * outside the vault lock (they are `suspend` prompts); this is fine since the vault synchronizes
 * internally.
 */
class VaultBiometrics(
    private val vault: Vault,
    private val keyStore: BiometricKeyStore,
    private val artifacts: BioArtifactStore,
    private val deviceId: String,
    private val alias: String = "skerry.vault.bio.$deviceId",
) {

    /** Biometric availability on this device — to show/hide the toggle and button. */
    fun availability(): BiometricAvailability = keyStore.availability()

    /** Whether biometrics is enabled for this vault (`vault.bio` exists). */
    fun isEnabled(): Boolean = artifacts.exists()

    /**
     * Enable biometrics: the vault must be unlocked. Wraps the current `dataKey` under `bioKey`
     * and saves `vault.bio`. Zeroizes the exported key copy in `finally`.
     */
    suspend fun enable(prompt: BiometricPrompt): BiometricEnableResult {
        if (keyStore.availability() != BiometricAvailability.Available) return BiometricEnableResult.Unavailable
        val dataKey = vault.exportDataKey() ?: return BiometricEnableResult.VaultLocked
        try {
            if (!keyStore.ensureKey(alias)) return BiometricEnableResult.Unavailable
            return when (val wrapped = keyStore.wrap(alias, dataKey.bytes, prompt)) {
                is BiometricResult.Success -> {
                    artifacts.write(BioArtifact(FORMAT_VERSION, alias, deviceId, wrapped.value))
                    BiometricEnableResult.Enabled
                }
                BiometricResult.Cancelled -> BiometricEnableResult.Cancelled
                // Lockout during enable isn't worth a dedicated outcome — retry once the sensor frees up.
                BiometricResult.Failed, BiometricResult.LockedOut -> BiometricEnableResult.Failed
                BiometricResult.KeyInvalidated -> {
                    keyStore.deleteKey(alias) // freshly created key already invalidated — don't leave it
                    BiometricEnableResult.Failed
                }
            }
        } finally {
            dataKey.bytes.fill(0)
        }
    }

    /** Disable biometrics: remove `bioKey` and `vault.bio`. Idempotent. */
    fun disable() {
        keyStore.deleteKey(alias)
        artifacts.clear()
    }

    /**
     * Unlock the vault via biometrics (cold start). Any failure falls back softly to the
     * password form; key invalidation disables biometrics. The `dataKey` from [unwrap] is handed
     * to [Vault.unlockWithDataKey], which takes ownership (and zeroizes it on `Corrupted`).
     */
    suspend fun unlock(prompt: BiometricPrompt): BiometricUnlockResult = when (val auth = authenticate(prompt)) {
        is BioAuth.Success -> {
            val dataKey = DataKey(auth.key) // ownership passes to the vault (it zeroizes on Corrupted)
            try {
                when (vault.unlockWithDataKey(dataKey)) {
                    UnlockResult.Success -> BiometricUnlockResult.Unlocked
                    UnlockResult.Corrupted -> BiometricUnlockResult.Corrupted
                    // unlockWithDataKey never checks a password and by contract never returns
                    // WrongPassword; explicit branch instead of else so a new UnlockResult case fails loudly.
                    UnlockResult.WrongPassword -> error("unlockWithDataKey does not check a password — WrongPassword is unreachable")
                }
            } catch (e: Throwable) {
                dataKey.bytes.fill(0) // exceptional path: don't leave the unwrapped key in memory
                throw e
            }
        }
        BioAuth.NotEnabled -> BiometricUnlockResult.NotEnabled
        BioAuth.Unavailable -> BiometricUnlockResult.Unavailable
        BioAuth.Cancelled -> BiometricUnlockResult.Cancelled
        BioAuth.Failed -> BiometricUnlockResult.Failed
        BioAuth.LockedOut -> BiometricUnlockResult.LockedOut
        BioAuth.Invalidated -> BiometricUnlockResult.Invalidated
    }

    /**
     * Confirm the owner's identity via biometrics without unlocking the vault — for
     * re-authentication before a sensitive action in an already-open session (copying a
     * password). Same path as [unlock] (reads `vault.bio`, checks alias/deviceId, unwraps via
     * [BiometricKeyStore.unwrap] with a system prompt), but the unwrapped key is not assigned to
     * the vault and is zeroized immediately — only the fact of successful authentication
     * matters. Key invalidation disables biometrics (as in [unlock]) — caller falls back to the
     * master password. The vault itself is untouched.
     */
    suspend fun confirm(prompt: BiometricPrompt): BiometricConfirmResult = when (val auth = authenticate(prompt)) {
        is BioAuth.Success -> {
            auth.key.fill(0) // key itself is not needed — only the successful authentication matters
            BiometricConfirmResult.Confirmed
        }
        BioAuth.NotEnabled -> BiometricConfirmResult.NotEnabled
        BioAuth.Unavailable -> BiometricConfirmResult.Unavailable
        BioAuth.Cancelled -> BiometricConfirmResult.Cancelled
        // The dedicated lockout message is only for the unlock screen; here a plain failure is enough.
        BioAuth.Failed, BioAuth.LockedOut -> BiometricConfirmResult.Failed
        BioAuth.Invalidated -> BiometricConfirmResult.Invalidated
    }

    /**
     * Read and validate `vault.bio`. The on-disk artifact is untrusted: format/alias/deviceId
     * must match expectations. Otherwise it's another device's file, tampering, or a different
     * format — `null` (soft fallback to password), the artifact is not deleted (this isn't a key
     * invalidation). Checking alias also keeps this symmetric with [disable].
     */
    private fun readValidArtifact(): BioArtifact? {
        val artifact = artifacts.read() ?: return null
        if (artifact.formatVersion != FORMAT_VERSION || artifact.alias != alias || artifact.deviceId != deviceId) {
            return null
        }
        return artifact
    }

    /**
     * Shared step for [unlock]/[confirm]: valid artifact + availability + system prompt that
     * unwraps `bioKey`. [BioAuth.Success] carries the unwrapped dataKey — ownership passes to the
     * caller (unlock hands it to the vault, confirm zeroizes it immediately). Key invalidation
     * (new fingerprint/face) disables biometrics right here — caller falls back to the master
     * password.
     */
    private suspend fun authenticate(prompt: BiometricPrompt): BioAuth {
        // A migrated (superseded-scheme) enrollment is reported as Invalidated, not NotEnabled: it
        // maps to the "biometrics were reset — use your password" message, so an upgrading user gets
        // feedback instead of a silent no-op (which would look exactly like the #23 bug).
        if (clearObsoleteEnrollment()) return BioAuth.Invalidated
        val artifact = readValidArtifact() ?: return BioAuth.NotEnabled
        if (keyStore.availability() != BiometricAvailability.Available) return BioAuth.Unavailable
        return when (val unwrapped = keyStore.unwrap(alias, artifact.wrappedBio, prompt)) {
            is BiometricResult.Success -> BioAuth.Success(unwrapped.value)
            BiometricResult.Cancelled -> BioAuth.Cancelled
            BiometricResult.Failed -> BioAuth.Failed
            BiometricResult.LockedOut -> BioAuth.LockedOut
            BiometricResult.KeyInvalidated -> {
                disable() // biometrics compromised by an enrollment change — disable and require password
                BioAuth.Invalidated
            }
        }
    }

    /**
     * A `vault.bio` written by a superseded key scheme (older [BioArtifact.formatVersion]) can't be
     * unwrapped by the current `bioKey`, so clear it — delete the stale key and artifact — and return
     * `true` so the caller reports it as a reset. The user re-enables biometrics on the current
     * scheme. Only this device's own obsolete artifact is migrated: a foreign or future-version file
     * is left untouched (that's [readValidArtifact]'s job). See #23 — the pre-time-bound per-operation
     * key never authorized on some OEM ROMs, so old enrollments must be recreated.
     */
    private fun clearObsoleteEnrollment(): Boolean {
        val artifact = artifacts.read() ?: return false
        if (artifact.deviceId != deviceId || artifact.alias != alias || artifact.formatVersion >= FORMAT_VERSION) {
            return false
        }
        disable()
        return true
    }

    /** Internal outcome of [authenticate]; mapped 1:1 to Unlock/Confirm results. */
    private sealed interface BioAuth {
        class Success(val key: ByteArray) : BioAuth
        data object NotEnabled : BioAuth
        data object Unavailable : BioAuth
        data object Cancelled : BioAuth
        data object Failed : BioAuth
        data object LockedOut : BioAuth
        data object Invalidated : BioAuth
    }

    private companion object {
        // v2: bioKey moved from per-operation (CryptoObject-bound) to time-bound auth so it works on
        // OEM ROMs that drop CryptoObject (#23). A v1 artifact was wrapped by the old key scheme and
        // is unwrappable now — clearObsoleteEnrollment() migrates it away.
        const val FORMAT_VERSION = 2
    }
}
