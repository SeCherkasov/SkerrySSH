package app.skerry.shared.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The OS keyring refused or failed an operation (daemon down, keyring locked and not unlocked, API error). */
class DeviceSecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A secret store the OS keeps for the signed-in account: Secret Service on Linux, DPAPI on Windows,
 * the login Keychain on macOS. Blocking calls; [read]/[write]/[delete] throw
 * [DeviceSecretStoreException] when the backend fails, which is different from [read] returning
 * `null` for an entry that is not there.
 */
interface DeviceSecretStore {
    /** Whether the backend is reachable on this machine at all. May be slow on first call. */
    fun isAvailable(): Boolean

    fun read(name: String): ByteArray?

    fun write(name: String, secret: ByteArray)

    fun delete(name: String)
}

/**
 * Trusted-device unlock (issue #398): a [BiometricKeyStore] whose key lives in the OS keyring of the
 * signed-in account instead of behind a biometric prompt. The keyring holds a random key of its own;
 * the vault key is sealed under it and the box goes to `vault.bio` like the biometric wrapper, so
 * [VaultBiometrics] runs the whole lifecycle unchanged and the vault key never enters the keyring.
 *
 * [allowed] is the experimental setting: while it is off the store reports itself unavailable, which
 * stops the unlock even if a `vault.bio` survived. Keyring calls block (D-Bus, DPAPI, Keychain), so
 * they run on [io].
 */
class DeviceKeyringKeyStore(
    private val secrets: DeviceSecretStore,
    private val crypto: VaultCrypto,
    private val allowed: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : BiometricKeyStore {

    override val factor: UnlockFactor get() = UnlockFactor.DeviceKeyring

    override fun availability(): BiometricAvailability =
        if (allowed() && guarded(false) { secrets.isAvailable() }) BiometricAvailability.Available else BiometricAvailability.NoHardware

    /**
     * A keyring has no weaker configuration to fall back to, so `false` — the next rung, and past the
     * last one a device branded unsupported for good — is never the right answer to a backend that
     * failed. A failure here is left for [wrap] to meet, which reports it as [BiometricResult.Failed].
     */
    override suspend fun ensureKey(alias: String, hardening: BiometricKeyHardening): Boolean = withContext(io) {
        guarded(Unit) {
            val existing = secrets.read(alias)
            val usable = existing?.size == KEY_BYTES
            existing?.fill(0)
            // An entry of any other length opens nothing; enabling again is how the user repairs it.
            if (!usable) {
                val fresh = crypto.newDataKey()
                try {
                    secrets.write(alias, fresh.bytes)
                } finally {
                    fresh.zeroize()
                }
            }
        }
        true
    }

    // The entry was put there by ensureKey a moment ago: if it is gone, the keyring dropped it.
    override suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        withKey(alias, missing = BiometricResult.Failed) { key -> BiometricResult.Success(crypto.seal(key, plaintext, aad(alias))) }

    override suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray> =
        withKey(alias, missing = BiometricResult.KeyInvalidated) { key ->
            crypto.open(key, wrapped, aad(alias))?.let { BiometricResult.Success(it) } ?: BiometricResult.TagMismatch
        }

    /**
     * Best-effort: [VaultBiometrics.disable] has already removed `vault.bio`, and without it the key
     * left in an unreachable keyring wraps nothing. Failing here would only keep a disable from
     * finishing over an entry that can no longer open anything.
     */
    override fun deleteKey(alias: String) {
        guarded(Unit) { secrets.delete(alias) }
    }

    /**
     * Runs [block] with the keyring key for [alias]. A missing entry is [missing]; on unwrap that is
     * [BiometricResult.KeyInvalidated] — the keyring was wiped or the OS account recreated, and the
     * wrapper can never open again. So is an entry of the wrong length: any process of this OS account
     * can write the keyring, and what it wrote must cost the trust, not crash every start. A backend
     * error is a plain [BiometricResult.Failed], so a keyring that is down this session costs a
     * password prompt and nothing more.
     */
    private suspend fun withKey(
        alias: String,
        missing: BiometricResult<ByteArray>,
        block: (DataKey) -> BiometricResult<ByteArray>,
    ): BiometricResult<ByteArray> = withContext(io) {
        val bytes = guarded(null) { secrets.read(alias) ?: return@withContext missing }
            ?: return@withContext BiometricResult.Failed
        if (bytes.size != KEY_BYTES) {
            bytes.fill(0)
            return@withContext missing
        }
        val key = DataKey(bytes)
        try {
            guarded(BiometricResult.Failed) { block(key) }
        } finally {
            key.zeroize()
        }
    }

    /**
     * [block], with any failure of the native backend turned into [fallback]. Wider than
     * [DeviceSecretStoreException] on purpose: the gate calls this on its own at start-up, and a JNA
     * binding that throws something unplanned must cost a password prompt, not the app.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    private fun aad(alias: String): ByteArray = "skerry.device-keyring.v1:$alias".encodeToByteArray()

    private companion object {
        /** An XChaCha20-Poly1305 key, what [VaultCrypto.newDataKey] makes. */
        const val KEY_BYTES = 32
    }
}
