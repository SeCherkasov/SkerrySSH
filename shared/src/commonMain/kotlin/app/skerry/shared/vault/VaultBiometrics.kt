package app.skerry.shared.vault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // Key-material bookkeeping (secure-storage entries, `vault.bio`, the support verdict) is blocking
    // I/O, and enable/unlock are driven straight from click handlers — keep it off the UI thread.
    private val io: CoroutineDispatcher = Dispatchers.Default,
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
     * Which [BiometricKeyHardening] the current enrollment ended up on, or `null` if unknown. The UI
     * uses it to say so when the device could only take a weaker rung — a downgrade the user never
     * asked for shouldn't be invisible. Meaningful only while [isEnabled] is true.
     */
    fun enrolledHardening(): BiometricKeyHardening? = support.hardening()

    /**
     * Enable biometrics: the vault must be unlocked. Wraps the current `dataKey` under `bioKey` and
     * saves `vault.bio` — but only after unwrapping it right back and comparing byte for byte, so an
     * enclave that accepts encryption and then refuses decryption (#23) is caught here rather than on
     * the next cold start. That verification costs a second prompt ([verifyPrompt]).
     *
     * The rungs of [BiometricKeyHardening] are tried in order; the first one that survives the round
     * trip is kept. If none does, the verdict is recorded and [BiometricEnableResult.Unsupported] is
     * returned. Zeroizes the exported key copy in `finally`.
     */
    suspend fun enable(prompt: BiometricPrompt, verifyPrompt: BiometricPrompt = prompt): BiometricEnableResult {
        if (keyStore.availability() != BiometricAvailability.Available) return BiometricEnableResult.Unavailable
        val dataKey = vault.exportDataKey() ?: return BiometricEnableResult.VaultLocked
        val outcome = try {
            walkLadder(dataKey.bytes, prompt, verifyPrompt)
        } finally {
            dataKey.bytes.fill(0)
        }
        // Descending the ladder destroys the previous key, so a surviving `vault.bio` would point at
        // nothing — turn biometrics fully off. A failure on the first rung touched no key material,
        // and there dismissing a prompt must not cost the user the enrollment they already had.
        if (outcome.result != BiometricEnableResult.Enabled && outcome.keyRecreated) {
            withContext(io) { disable() }
        }
        return outcome.result
    }

    /**
     * [enable] minus the exported-key handling: walk the rungs until one survives the round trip.
     * The first rung reuses an existing `bioKey` when there is one (re-enrollment on a device that
     * already works must not throw its key away before knowing the new attempt succeeds); every
     * further rung needs its own configuration, so it recreates the key — hence
     * [LadderOutcome.keyRecreated].
     */
    private suspend fun walkLadder(
        dataKey: ByteArray,
        prompt: BiometricPrompt,
        verifyPrompt: BiometricPrompt,
    ): LadderOutcome {
        var keyRecreated = false
        keyStore.hardeningLadder().forEachIndexed { index, hardening ->
            if (index > 0) {
                withContext(io) { keyStore.deleteKey(alias) }
                keyRecreated = true
            }
            when (val attempt = attemptEnable(hardening, dataKey, prompt, verifyPrompt)) {
                Attempt.Verified -> {
                    withContext(io) {
                        support.clear() // a device that works now must not stay branded unsupported
                        support.rememberHardening(hardening) // the UI admits a weaker rung (see enrolledHardening)
                    }
                    return LadderOutcome(BiometricEnableResult.Enabled, keyRecreated)
                }
                Attempt.NextRung -> Unit
                is Attempt.Abort -> return LadderOutcome(attempt.result, keyRecreated)
            }
        }
        withContext(io) { support.markUnsupported() }
        return LadderOutcome(BiometricEnableResult.Unsupported, keyRecreated)
    }

    /**
     * One rung of the ladder: create `bioKey` under [hardening], wrap [dataKey], then unwrap and
     * compare. Only evidence about the *key configuration* moves to the next rung — the enclave
     * refusing an authorized operation, a key invalidated on the spot, a configuration the platform
     * won't create, or a round trip that comes back different. Everything else stops the ladder:
     * cancellation and lockout are the user's or the sensor's business, and a plain
     * [BiometricResult.Failed] covers timeouts, a destroyed Activity and hardware hiccups — retrying
     * those on weaker rungs would throw extra prompts at the user and could brand a healthy device
     * unsupported.
     */
    private suspend fun attemptEnable(
        hardening: BiometricKeyHardening,
        dataKey: ByteArray,
        prompt: BiometricPrompt,
        verifyPrompt: BiometricPrompt,
    ): Attempt {
        if (!keyStore.ensureKey(alias, hardening)) return Attempt.NextRung
        val wrapped = when (val result = keyStore.wrap(alias, dataKey, prompt)) {
            is BiometricResult.Success -> result.value
            BiometricResult.Cancelled -> return Attempt.Abort(BiometricEnableResult.Cancelled)
            BiometricResult.Failed, BiometricResult.LockedOut -> return Attempt.Abort(BiometricEnableResult.Failed)
            // TagMismatch has no meaning while encrypting — treat it as the failure it is.
            BiometricResult.TagMismatch -> return Attempt.Abort(BiometricEnableResult.Failed)
            BiometricResult.Unusable, BiometricResult.KeyInvalidated -> return Attempt.NextRung
        }
        return when (val verified = keyStore.unwrap(alias, wrapped, verifyPrompt)) {
            is BiometricResult.Success -> {
                val matches = constantTimeEquals(verified.value, dataKey)
                verified.value.fill(0) // the verification copy of the dataKey must not outlive the check
                if (!matches) return Attempt.NextRung
                withContext(io) { artifacts.write(BioArtifact(FORMAT_VERSION, alias, deviceId, wrapped)) }
                Attempt.Verified
            }
            BiometricResult.Cancelled -> Attempt.Abort(BiometricEnableResult.Cancelled)
            BiometricResult.Failed, BiometricResult.LockedOut -> Attempt.Abort(BiometricEnableResult.Failed)
            // This wrapper was produced by this key seconds ago, so a bad tag can't be a real
            // mismatch — it's the enclave refusing the operation under another name (#23).
            BiometricResult.TagMismatch -> Attempt.NextRung
            BiometricResult.Unusable, BiometricResult.KeyInvalidated -> Attempt.NextRung
        }
    }

    /** Outcome of one [attemptEnable] rung. */
    private sealed interface Attempt {
        /** Round trip passed — `vault.bio` is written. */
        data object Verified : Attempt

        /** This key configuration doesn't work here; try a weaker one. */
        data object NextRung : Attempt

        /** Stop the ladder and report [result] (user cancelled, sensor locked out, hardware failure). */
        class Abort(val result: BiometricEnableResult) : Attempt
    }

    /** [walkLadder]'s result plus whether it destroyed the `bioKey` the device had before. */
    private class LadderOutcome(val result: BiometricEnableResult, val keyRecreated: Boolean)

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
            is BiometricResult.Success -> {
                withContext(io) { support.clearRefusals() } // the enclave is behaving — forget the streak
                BioAuth.Success(unwrapped.value)
            }
            BiometricResult.Cancelled -> BioAuth.Cancelled
            // Away from the round-trip check a bad tag means what it says: a stale or tampered
            // `vault.bio`. Soft fallback to the password, nothing destroyed.
            BiometricResult.Failed, BiometricResult.TagMismatch -> BioAuth.Failed
            BiometricResult.LockedOut -> BioAuth.LockedOut
            BiometricResult.KeyInvalidated -> withContext(io) {
                disable() // biometrics compromised by an enrollment change — disable and require password
                BioAuth.Invalidated
            }
            // The enclave refused a key that passed the round-trip check at enable time. That is what a
            // ROM update looks like — but also what a one-off keystore glitch looks like, and the
            // reaction (delete the enrollment, brand the device) is both destructive and sticky. So a
            // single refusal only reads as an ordinary failure; the streak is what convicts.
            BiometricResult.Unusable -> withContext(io) {
                if (support.recordRefusal() < REFUSALS_BEFORE_UNSUPPORTED) {
                    BioAuth.Failed
                } else {
                    disable()
                    support.markUnsupported()
                    BioAuth.Unsupported
                }
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

        // Consecutive enclave refusals at unlock before biometrics is disabled and the device is
        // branded unsupported. Two: one refusal is as likely to be a keystore hiccup as a broken ROM.
        const val REFUSALS_BEFORE_UNSUPPORTED = 2
    }
}
