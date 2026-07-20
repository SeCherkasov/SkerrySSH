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

    /**
     * The device cannot decrypt the vault with a biometrics-protected key: every
     * [BiometricKeyHardening] rung failed the round-trip check (#23). Recorded in
     * [BiometricSupportStore] so the UI stops offering the toggle and points at the master password.
     */
    Unsupported,
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

    /**
     * Auth succeeded but the enclave refused to decrypt with the authorized key — this device can't
     * do biometric unlock (#23). Biometrics is disabled and the verdict recorded, so the user gets
     * the master password and an explanation instead of a button that silently does nothing.
     */
    data object Unsupported : BiometricUnlockResult

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

    /** The device can't use a biometrics-protected key at all (as in [BiometricUnlockResult.Unsupported]). */
    data object Unsupported : BiometricConfirmResult
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
    private val support: BiometricSupportStore = BiometricSupportStore.Volatile(),
) {

    /** Biometric availability on this device — to show/hide the toggle and button. */
    fun availability(): BiometricAvailability = keyStore.availability()

    /** Whether biometrics is enabled for this vault (`vault.bio` exists). */
    fun isEnabled(): Boolean = artifacts.exists()

    /**
     * Whether this device already proved it can't decrypt the vault biometrically — the UI shows the
     * toggle inert with an explanation instead of a flow that always ends on the password form.
     */
    fun isUnsupported(): Boolean = support.isUnsupported()

    /** Forget the "unsupported" verdict so [enable] runs the full ladder again (user-initiated re-check). */
    fun forgetUnsupported() = support.clear()

    /**
     * Enable biometrics: the vault must be unlocked. Wraps the current `dataKey` under `bioKey` and
     * saves `vault.bio` — but only after unwrapping it right back and comparing byte for byte, so an
     * enclave that accepts encryption and then refuses decryption (#23) is caught here rather than on
     * the next cold start. That verification costs a second prompt ([verifyPrompt]).
     *
     * Each [BiometricKeyHardening] rung gets its own attempt with a freshly created key; the first
     * one that survives the round trip is kept. If none does, the verdict is recorded and
     * [BiometricEnableResult.Unsupported] is returned. Zeroizes the exported key copy in `finally`.
     */
    suspend fun enable(prompt: BiometricPrompt, verifyPrompt: BiometricPrompt = prompt): BiometricEnableResult {
        if (keyStore.availability() != BiometricAvailability.Available) return BiometricEnableResult.Unavailable
        val dataKey = vault.exportDataKey() ?: return BiometricEnableResult.VaultLocked
        val result = try {
            walkLadder(dataKey.bytes, prompt, verifyPrompt)
        } finally {
            dataKey.bytes.fill(0)
        }
        if (result != BiometricEnableResult.Enabled) {
            // Every rung deleted the previous key, so a leftover artifact (re-enrollment on a device
            // that used to work) would now point at nothing. Turn biometrics fully off instead of
            // leaving a wrapper that can only fail at the next unlock.
            disable()
        }
        return result
    }

    /** [enable] minus key-material bookkeeping: try the rungs in order until one survives the round trip. */
    private suspend fun walkLadder(
        dataKey: ByteArray,
        prompt: BiometricPrompt,
        verifyPrompt: BiometricPrompt,
    ): BiometricEnableResult {
        for (hardening in keyStore.hardeningLadder()) {
            when (val attempt = attemptEnable(hardening, dataKey, prompt, verifyPrompt)) {
                Attempt.Verified -> {
                    support.clear() // a device that works now must not stay branded unsupported
                    return BiometricEnableResult.Enabled
                }
                Attempt.NextRung -> continue
                is Attempt.Abort -> return attempt.result
            }
        }
        support.markUnsupported()
        return BiometricEnableResult.Unsupported
    }

    /**
     * One rung of the ladder: recreate `bioKey` under [hardening], wrap [dataKey], then unwrap and
     * compare. Anything the enclave botches ([BiometricResult.Failed], [BiometricResult.Unusable],
     * a key invalidated on the spot, or a mismatching round trip) moves on to the next rung; the
     * user cancelling or the sensor locking out aborts the whole thing — those aren't the device's
     * verdict, and burning through the remaining rungs would just mean more prompts.
     */
    private suspend fun attemptEnable(
        hardening: BiometricKeyHardening,
        dataKey: ByteArray,
        prompt: BiometricPrompt,
        verifyPrompt: BiometricPrompt,
    ): Attempt {
        keyStore.deleteKey(alias) // each rung starts from a key created with its own configuration
        if (!keyStore.ensureKey(alias, hardening)) return Attempt.NextRung
        val wrapped = when (val result = keyStore.wrap(alias, dataKey, prompt)) {
            is BiometricResult.Success -> result.value
            BiometricResult.Cancelled -> return Attempt.Abort(BiometricEnableResult.Cancelled)
            BiometricResult.LockedOut -> return Attempt.Abort(BiometricEnableResult.Failed)
            BiometricResult.Failed, BiometricResult.Unusable, BiometricResult.KeyInvalidated ->
                return Attempt.NextRung
        }
        return when (val verified = keyStore.unwrap(alias, wrapped, verifyPrompt)) {
            is BiometricResult.Success -> {
                val matches = constantTimeEquals(verified.value, dataKey)
                verified.value.fill(0) // the verification copy of the dataKey must not outlive the check
                if (!matches) return Attempt.NextRung
                artifacts.write(BioArtifact(FORMAT_VERSION, alias, deviceId, wrapped))
                Attempt.Verified
            }
            BiometricResult.Cancelled -> Attempt.Abort(BiometricEnableResult.Cancelled)
            BiometricResult.LockedOut -> Attempt.Abort(BiometricEnableResult.Failed)
            BiometricResult.Failed, BiometricResult.Unusable, BiometricResult.KeyInvalidated ->
                Attempt.NextRung
        }
    }

    /** Outcome of one [attemptEnable] rung. */
    private sealed interface Attempt {
        /** Round trip passed — `vault.bio` is written. */
        data object Verified : Attempt

        /** This key configuration doesn't work here; try a weaker one. */
        data object NextRung : Attempt

        /** Stop the ladder and report [result] (user cancelled, sensor locked out). */
        class Abort(val result: BiometricEnableResult) : Attempt
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
        BioAuth.Unsupported -> BiometricUnlockResult.Unsupported
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
        BioAuth.Unsupported -> BiometricConfirmResult.Unsupported
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
            // The enclave stopped honouring a key that passed the round-trip check at enable time
            // (a ROM update, or a rung that only fails later). Nothing here can be repaired by
            // retrying, so record the verdict and hand the user back to the master password.
            BiometricResult.Unusable -> {
                disable()
                support.markUnsupported()
                BioAuth.Unsupported
            }
        }
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
        data object Unsupported : BioAuth
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
