package app.skerry.shared.vault

/** Scripted outcome of a biometric prompt in [FakeBiometricKeyStore]. */
enum class BiometricOutcome { Success, Cancelled, Failed, LockedOut, Invalidated, Unusable, TagMismatch }

/**
 * In-memory [BiometricKeyStore] for orchestration tests without hardware. Wraps via XOR with a
 * pseudo `bioKey` pad that lives only in the fake, emulating a non-extractable enclave key: disk
 * (`vault.bio`) gets `plaintext XOR pad`, not the raw `dataKey`, so tests can assert the wrapping
 * itself is stored. Prompt outcomes are scripted via [nextWrap]/[nextUnwrap] (default: success).
 * [currentAvailability] is set by the test to exercise degradation. Call tracking
 * ([ensureKeyCalls], [deletedAliases], [hardeningUsed]) supports orchestration assertions.
 *
 * [workingHardenings] models the enclave behind the [hardeningLadder]: a key created under a rung
 * outside that set wraps and unwraps as [BiometricResult.Unusable], the way an OEM keystore accepts
 * an auth-bound key and then refuses to authorize it (#23). Empty — no rung works at all.
 */
open class FakeBiometricKeyStore(
    var currentAvailability: BiometricAvailability = BiometricAvailability.Available,
    var nextWrap: BiometricOutcome = BiometricOutcome.Success,
    var nextUnwrap: BiometricOutcome = BiometricOutcome.Success,
    var workingHardenings: Set<BiometricKeyHardening> = BiometricKeyHardening.entries.toSet(),
    private val ladder: List<BiometricKeyHardening> = BiometricKeyHardening.entries,
) : BiometricKeyStore {

    private val pads = mutableMapOf<String, ByteArray>()
    private val keyHardening = mutableMapOf<String, BiometricKeyHardening>()

    var ensureKeyCalls = 0
        private set
    val deletedAliases = mutableListOf<String>()

    /** Rungs [ensureKey] was asked for, in order — the ladder walk as the orchestrator drove it. */
    val hardeningUsed = mutableListOf<BiometricKeyHardening>()

    override fun availability(): BiometricAvailability = currentAvailability

    override fun hardeningLadder(): List<BiometricKeyHardening> = ladder

    override suspend fun ensureKey(alias: String, hardening: BiometricKeyHardening): Boolean {
        if (currentAvailability != BiometricAvailability.Available) return false
        ensureKeyCalls++
        hardeningUsed += hardening
        pads.getOrPut(alias) { padFor(alias) }
        keyHardening.getOrPut(alias) { hardening }
        return true
    }

    override suspend fun wrap(
        alias: String,
        plaintext: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = when (nextWrap) {
        BiometricOutcome.Success -> {
            val pad = pads[alias] ?: return BiometricResult.Failed
            if (!honours(alias)) BiometricResult.Unusable else BiometricResult.Success(xor(plaintext, pad))
        }
        BiometricOutcome.Cancelled -> BiometricResult.Cancelled
        BiometricOutcome.Failed -> BiometricResult.Failed
        BiometricOutcome.LockedOut -> BiometricResult.LockedOut
        BiometricOutcome.Unusable -> BiometricResult.Unusable
        BiometricOutcome.TagMismatch -> BiometricResult.TagMismatch
        BiometricOutcome.Invalidated -> {
            pads.remove(alias)
            BiometricResult.KeyInvalidated
        }
    }

    override suspend fun unwrap(
        alias: String,
        wrapped: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = when (nextUnwrap) {
        BiometricOutcome.Success -> {
            val pad = pads[alias] ?: return BiometricResult.KeyInvalidated // no pad => key deleted
            if (!honours(alias)) BiometricResult.Unusable else BiometricResult.Success(xor(wrapped, pad))
        }
        BiometricOutcome.Cancelled -> BiometricResult.Cancelled
        BiometricOutcome.Failed -> BiometricResult.Failed
        BiometricOutcome.LockedOut -> BiometricResult.LockedOut
        BiometricOutcome.Unusable -> BiometricResult.Unusable
        BiometricOutcome.TagMismatch -> BiometricResult.TagMismatch
        BiometricOutcome.Invalidated -> {
            pads.remove(alias)
            BiometricResult.KeyInvalidated
        }
    }

    override fun deleteKey(alias: String) {
        pads.remove(alias)
        keyHardening.remove(alias)
        deletedAliases += alias
    }

    /** Whether the enclave authorizes operations on the key as it was created for [alias]. */
    private fun honours(alias: String): Boolean = keyHardening[alias] in workingHardenings

    /** Deterministic 64-byte pad derived from alias — stable across "runs" within one test. */
    private fun padFor(alias: String): ByteArray =
        ByteArray(64) { (alias[it % alias.length].code + it).toByte() }

    private fun xor(input: ByteArray, pad: ByteArray): ByteArray {
        require(input.size <= pad.size) { "pad shorter than input: ${pad.size} < ${input.size}" }
        return ByteArray(input.size) { (input[it].toInt() xor pad[it].toInt()).toByte() }
    }
}
