package app.skerry.shared.vault

/** Программируемый исход биометрического промпта в [FakeBiometricKeyStore]. */
enum class BiometricOutcome { Success, Cancelled, Failed, Invalidated }

/**
 * In-memory [BiometricKeyStore] для тестов оркестрации без железа. «Оборачивает» XOR'ом по
 * псевдо-`bioKey` (паду), который живёт только внутри фейка — эмуляция неизвлекаемого ключа
 * enclave: на диск (`vault.bio`) уходит `plaintext XOR pad`, не сам `dataKey`, что позволяет
 * тесту убедиться, что хранится именно обёртка. Исходы промпта программируются ([nextWrap]/
 * [nextUnwrap]); по умолчанию — успех. [currentAvailability] меняется тестом для проверки
 * деградации. Учёт вызовов ([ensureKeyCalls], [deletedAliases]) — для ассертов оркестрации.
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
            val pad = pads[alias] ?: return BiometricResult.KeyInvalidated // нет пада ⇒ ключ удалён
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

    /** Детерминированный 64-байтный пад из alias — стабилен между «запусками» в одном тесте. */
    private fun padFor(alias: String): ByteArray =
        ByteArray(64) { (alias[it % alias.length].code + it).toByte() }

    private fun xor(input: ByteArray, pad: ByteArray): ByteArray {
        require(input.size <= pad.size) { "пад короче входа: ${pad.size} < ${input.size}" }
        return ByteArray(input.size) { (input[it].toInt() xor pad[it].toInt()).toByte() }
    }
}
