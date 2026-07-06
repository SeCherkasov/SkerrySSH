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
 * Outcome of a biometric operation gated by the system prompt. Parameterized by the payload
 * ([Success.value]) — `ByteArray` for `wrap`/`unwrap`. Failure cases are split so orchestration
 * can distinguish "silently fall back to password" (`Cancelled`/`Failed`) from "key invalidated,
 * biometrics must be recreated" (`KeyInvalidated`, e.g. a new fingerprint was enrolled).
 */
sealed interface BiometricResult<out T> {
    data class Success<T>(val value: T) : BiometricResult<T>

    /** User dismissed the prompt or it timed out — not an error, just no result. */
    data object Cancelled : BiometricResult<Nothing>

    /** Biometric match failed / sensor error — fall back to the master password. */
    data object Failed : BiometricResult<Nothing>

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
     * Idempotently create a non-extractable `bioKey` under [alias] in secure storage. `false` if
     * it can't be created (no hardware/not enrolled) — the caller aborts enabling biometrics.
     */
    suspend fun ensureKey(alias: String): Boolean

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
