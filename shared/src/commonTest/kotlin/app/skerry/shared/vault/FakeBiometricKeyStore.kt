package app.skerry.shared.vault

/** Scripted outcome of a biometric prompt in [FakeBiometricKeyStore]. */
enum class BiometricOutcome { Success, Cancelled, Failed, Invalidated }

/**
 * In-memory [BiometricKeyStore] for orchestration tests without hardware. Wraps via XOR with a
 * pseudo `bioKey` pad that lives only in the fake, emulating a non-extractable enclave key: disk
 * (`vault.bio`) gets `plaintext XOR pad`, not the raw `dataKey`, so tests can assert the wrapping
 * itself is stored. Prompt outcomes are scripted via [nextWrap]/[nextUnwrap] (default: success).
 * [currentAvailability] is set by the test to exercise degradation. Call tracking
 * ([ensureKeyCalls], [deletedAliases]) supports orchestration assertions.
 */
class FakeBiometricKeyStore(
    var currentAvailability: BiometricAvailability = BiometricAvailability.Available,
    var nextWrap: BiometricOutcome = BiometricOutcome.Success,
    var nextUnwrap: BiometricOutcome = BiometricOutcome.Success,
) : BiometricKeyStore {

    private val pads = mutableMapOf<String, ByteArray>()

    var ensureKeyCalls = 0
        private set
    val deletedAliases = mutableListOf<String>()

    override fun availability(): BiometricAvailability = currentAvailability

    override suspend fun ensureKey(alias: String): Boolean {
        if (currentAvailability != BiometricAvailability.Available) return false
        ensureKeyCalls++
        pads.getOrPut(alias) { padFor(alias) }
        return true
    }

    override suspend fun wrap(
        alias: String,
        plaintext: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = when (nextWrap) {
        BiometricOutcome.Success -> {
            val pad = pads[alias] ?: return BiometricResult.Failed
            BiometricResult.Success(xor(plaintext, pad))
        }
        BiometricOutcome.Cancelled -> BiometricResult.Cancelled
        BiometricOutcome.Failed -> BiometricResult.Failed
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
            BiometricResult.Success(xor(wrapped, pad))
        }
        BiometricOutcome.Cancelled -> BiometricResult.Cancelled
        BiometricOutcome.Failed -> BiometricResult.Failed
        BiometricOutcome.Invalidated -> {
            pads.remove(alias)
            BiometricResult.KeyInvalidated
        }
    }

    override fun deleteKey(alias: String) {
        pads.remove(alias)
        deletedAliases += alias
    }

    /** Deterministic 64-byte pad derived from alias — stable across "runs" within one test. */
    private fun padFor(alias: String): ByteArray =
        ByteArray(64) { (alias[it % alias.length].code + it).toByte() }

    private fun xor(input: ByteArray, pad: ByteArray): ByteArray {
        require(input.size <= pad.size) { "pad shorter than input: ${pad.size} < ${input.size}" }
        return ByteArray(input.size) { (input[it].toInt() xor pad[it].toInt()).toByte() }
    }
}
