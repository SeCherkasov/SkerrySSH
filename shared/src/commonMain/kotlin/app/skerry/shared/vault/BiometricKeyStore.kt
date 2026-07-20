package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/**
 * Biometric availability on the device at poll time. Polled before every operation: the user may
 * have added/removed a fingerprint or temporarily locked the sensor between runs. `NoHardware` is
 * the normal desktop state (no unified biometric API), so the toggle is hidden there.
 */
enum class BiometricAvailability {
    /** Hardware present and at least one biometric factor enrolled — can enable/unlock. */
    Available,

    /** No sensor (or a platform without biometrics — desktop). */
    NoHardware,

    /** Hardware present but no fingerprint/face enrolled — prompt to set it up in the system. */
    NotEnrolled,

    /** Temporarily locked out after failed attempts — password only until the system unlocks it. */
    LockedOut,
}

/**
 * How hard the `bioKey` is tied to the device's secure hardware. Tried in ladder order (see
 * [BiometricKeyStore.hardeningLadder]): the strongest configuration the device actually honours
 * wins. Some OEM keystores accept a key at generation time and then never authorize operations on
 * it (see #23) — the ladder is what turns that dead end into a working, if slightly weaker, key.
 */
enum class BiometricKeyHardening {
    /** Strongest available: StrongBox when present, key unusable while the device is locked. */
    Strongest,

    /** No StrongBox — the key lives in the TEE. Still unusable while the device is locked. */
    NoStrongBox,

    /** TEE without the "device must be unlocked" requirement — last rung before giving up. */
    Relaxed,
}

/**
 * Outcome of a biometric operation gated by the system prompt. Parameterized by the payload
 * ([Success.value]) — `ByteArray` for `wrap`/`unwrap`. Failure cases are split so orchestration
 * can distinguish "silently fall back to password" (`Cancelled`/`Failed`) from "key invalidated,
 * biometrics must be recreated" (`KeyInvalidated`, e.g. a new fingerprint was enrolled) and from
 * "this device can never do it" ([Unusable]).
 */
sealed interface BiometricResult<out T> {
    data class Success<T>(val value: T) : BiometricResult<T>

    /** User dismissed the prompt or it timed out — not an error, just no result. */
    data object Cancelled : BiometricResult<Nothing>

    /** Biometric match failed / sensor error — fall back to the master password. */
    data object Failed : BiometricResult<Nothing>

    /** Sensor locked out after too many attempts — tell the user to wait, fall back to the password. */
    data object LockedOut : BiometricResult<Nothing>

    /**
     * The biometric auth itself succeeded, but the enclave refused to run the operation with the
     * authorized key (no `CryptoObject` handed back, or the cipher threw afterwards). Reported by
     * OEM keystores that accept an auth-bound key and then never authorize it (#23) — biometrics
     * cannot decrypt the vault under this key configuration, so the caller either drops to the next
     * [BiometricKeyHardening] rung or disables biometrics for good.
     */
    data object Unusable : BiometricResult<Nothing>

    /**
     * Authenticated decryption came back with a bad tag: the wrapper doesn't match the key. Normally
     * that means a stale or tampered artifact — but an enclave that answers this for a wrapper the
     * same key produced seconds ago is describing itself, not the data (#23: HyperOS StrongBox
     * returns KeyMint `VERIFICATION_FAILED` on `finish`, which the platform surfaces as a bad tag).
     * Only the caller knows which of the two it is looking at, so the classification lives there.
     */
    data object TagMismatch : BiometricResult<Nothing>

    /**
     * `bioKey` was irreversibly invalidated by the platform (biometric enrollment changed).
     * Orchestration must delete the `vault.bio` artifact and require the master password.
     */
    data object KeyInvalidated : BiometricResult<Nothing>
}

/**
 * Text for the system biometric prompt. UI strings (localized) are supplied from above —
 * `commonMain` does not hardcode them. `cancelLabel` is required: on Android it's the prompt's
 * negative button.
 */
data class BiometricPrompt(
    val title: String,
    val cancelLabel: String,
    val subtitle: String? = null,
)

/**
 * Platform-backed, biometrics-protected store for the `bioKey`. Implementation is
 * platform-specific (Android Keystore + `androidx.biometric`; desktop — `NoHardware` stub), so
 * the contract lives in the core with hardware behind the interface. `bioKey` is non-extractable:
 * only the wrapped `dataKey` leaves it.
 *
 * `wrap`/`unwrap` are `suspend` because they show a system prompt and wait for the user; they
 * must not be called under [Vault]'s `synchronized` lock. The caller is responsible for wiping
 * the `plaintext` passed to [wrap] afterward; the implementation does not retain it.
 */
interface BiometricKeyStore {

    /** Current biometric availability; poll before every operation. */
    fun availability(): BiometricAvailability

    /**
     * Configurations to try when creating the `bioKey`, strongest first. A platform with a single
     * configuration returns one element; the caller walks the list until a rung survives the
     * round-trip check in [VaultBiometrics.enable].
     */
    fun hardeningLadder(): List<BiometricKeyHardening> = listOf(BiometricKeyHardening.Strongest)

    /**
     * Idempotently create a non-extractable `bioKey` under [alias] in secure storage, configured
     * per [hardening]. `false` if it can't be created (no hardware/not enrolled, or the platform
     * rejects this rung) — the caller moves on to the next rung or aborts enabling biometrics.
     * An existing key is kept as is: to switch rungs, [deleteKey] first.
     */
    suspend fun ensureKey(alias: String, hardening: BiometricKeyHardening = BiometricKeyHardening.Strongest): Boolean

    /** Show the prompt and, on success, wrap [plaintext] with the [alias] key. */
    suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray>

    /** Show the prompt and, on success, unwrap [wrapped] with the [alias] key. */
    suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray>

    /** Delete the `bioKey` (disabling biometrics, panic wipe, device change). Unknown alias is a no-op. */
    fun deleteKey(alias: String)
}

/**
 * The plaintext `vault.bio` artifact: `dataKey` wrapped under `bioKey` plus metadata for
 * unwrapping. Stored next to `vault.json`; `dataKey` stays the same, so a master password change
 * does not touch this artifact. Useless on its own without the device's `bioKey` secure enclave.
 */
@Serializable
data class BioArtifact(
    val formatVersion: Int,
    val alias: String,
    val deviceId: String,
    val wrappedBio: ByteArray,
) {
    // ByteArray breaks structural equals/hashCode autogeneration — implemented manually. Adding a
    // field requires updating both functions AND toString (the compiler won't warn about this).
    override fun toString(): String =
        "BioArtifact(formatVersion=$formatVersion, alias=$alias, deviceId=$deviceId, wrappedBio=<redacted>)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BioArtifact) return false
        return formatVersion == other.formatVersion &&
            alias == other.alias &&
            deviceId == other.deviceId &&
            wrappedBio.contentEquals(other.wrappedBio)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + alias.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + wrappedBio.contentHashCode()
        return result
    }
}

/**
 * Persistence for the `vault.bio` artifact. A separate contract (like [Vault] over a file) so
 * [VaultBiometrics] orchestration can be tested on `FakeFileSystem` without real hardware.
 */
interface BioArtifactStore {
    /** Whether a saved artifact exists (biometrics enabled for this vault). */
    fun exists(): Boolean

    /** Read the artifact; `null` if the file is missing or unparsable. */
    fun read(): BioArtifact?

    /** Write/overwrite the artifact atomically. */
    fun write(artifact: BioArtifact)

    /** Delete the artifact (disabling biometrics). Missing file is a no-op. */
    fun clear()
}
