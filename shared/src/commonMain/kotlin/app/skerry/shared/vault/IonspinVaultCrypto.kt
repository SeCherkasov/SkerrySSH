package app.skerry.shared.vault

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_ABYTES
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_KEYBYTES
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_SALTBYTES
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Инициализирует нативный libsodium; должна отработать до создания/использования
 * [IonspinVaultCrypto]. Идемпотентна: повторный вызов в уже инициализированном процессе — no-op.
 * Это важно на Android/Native — там `LibsodiumInitializer.initialize()` при повторном `sodium_init`
 * бросает (код возврата 1 = «уже инициализировано»), что роняло пересоздание Activity (поворот/
 * возврат из фона). Держит ionspin внутренней деталью ядра — точка входа зовёт эту функцию, а не
 * ionspin напрямую (см. [IonspinVaultCrypto]).
 */
suspend fun initializeVaultCrypto() {
    if (!LibsodiumInitializer.isInitialized()) {
        LibsodiumInitializer.initialize()
    }
}

/**
 * Единая реализация [VaultCrypto] на ionspin multiplatform-crypto-libsodium-bindings — один
 * и тот же код для desktop (JVM) и Android. Соответствует иерархии ключей
 * из `docs/skerry-sync-design.md`: Argon2id(m=64MiB, t=3) → masterKey; XChaCha20-Poly1305 с
 * 24-байтным nonce в префиксе для обёртки dataKey и для каждой записи.
 *
 * Операции stateless и потокобезопасны. **Важно:** libsodium требует асинхронной инициализации
 * ([LibsodiumInitializer.initialize], suspend) до первого вызова — её выполняет точка входа
 * приложения; каждая операция здесь страхуется быстрым [requireInitialized].
 *
 * Ограничение zero-knowledge относительно desktop-предшественника на lazysodium: ionspin
 * принимает пароль только как `String` ([PasswordHash.pwhash]), поэтому из входного [CharArray]
 * неизбежно создаётся immutable-строка, которую нельзя затереть (живёт до GC). Время её жизни
 * сведено к минимуму (локальная переменная внутри [deriveMasterKey]); затирание самого
 * [CharArray] — на стороне вызывающего, как и прежде (см. контракт [VaultCrypto.deriveMasterKey]).
 */
@OptIn(ExperimentalUnsignedTypes::class) // ionspin отдаёт/принимает ключи и блобы как UByteArray
class IonspinVaultCrypto : VaultCrypto {

    override fun newSalt(): ByteArray {
        requireInitialized()
        return LibsodiumRandom.buf(crypto_pwhash_SALTBYTES).toByteArray()
    }

    override fun deriveSyncSalt(accountId: String): ByteArray {
        requireInitialized()
        // BLAKE2b(accountId) усечённый до длины Argon2id-соли — детерминированно и без коллизий
        // на разумных accountId; одинаков на всех устройствах (design §1: «salt = accountId»).
        return GenericHash.genericHash(
            message = accountId.encodeToByteArray().toUByteArray(),
            requestedHashLength = crypto_pwhash_SALTBYTES,
        ).toByteArray()
    }

    override fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey {
        requireInitialized()
        require(salt.size == crypto_pwhash_SALTBYTES) { "salt must be $crypto_pwhash_SALTBYTES bytes" }
        // Регрессия относительно CharArray-пути: ionspin не даёт варианта pwhash на байтах/CharArray,
        // поэтому строка пароля неизбежна и не затирается (см. KDoc класса).
        val passwordString = password.concatToString()
        val key = PasswordHash.pwhash(
            outputLength = KEY_BYTES,
            password = passwordString,
            salt = salt.toUByteArray(),
            opsLimit = OPS_LIMIT,
            memLimit = MEM_LIMIT,
            algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13,
        )
        // UByteArray-выход pwhash — промежуточная копия ключа: затираем после снятия ByteArray-копии,
        // чтобы в куче оставался один экземпляр masterKey (его затирает владелец MasterKey).
        return MasterKey(key.toByteArray()).also { key.fill(0u) }
    }

    override fun newDataKey(): DataKey {
        requireInitialized()
        return DataKey(AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfKeygen().toByteArray())
    }

    override fun deriveAuthKey(masterKey: MasterKey): ByteArray {
        requireInitialized()
        require(masterKey.bytes.size == KEY_BYTES) { "masterKey must be $KEY_BYTES bytes" }
        // libsodium crypto_kdf (BLAKE2b): субключ из masterKey в домене AUTH_CONTEXT. Контекст
        // отделяет authKey от любых других субключей того же masterKey (доменная изоляция).
        val masterCopy = masterKey.bytes.toUByteArray() // промежуточная копия ключа — затираем ниже
        val subKey = Kdf.deriveFromKey(AUTH_SUBKEY_ID, KEY_BYTES, AUTH_CONTEXT, masterCopy)
        masterCopy.fill(0u)
        return subKey.toByteArray().also { subKey.fill(0u) }
    }

    override fun newTransferKey(): ByteArray {
        requireInitialized()
        return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfKeygen().toByteArray()
    }

    override fun sealDataKeyForTransfer(dataKey: DataKey, transferKey: ByteArray): ByteArray =
        aeadSeal(transferKey, dataKey.bytes, TRANSFER_AAD)

    override fun openTransferredDataKey(transferKey: ByteArray, envelope: ByteArray): DataKey? =
        aeadOpen(transferKey, envelope, TRANSFER_AAD)?.let { DataKey(it) }

    override fun wrapDataKey(masterKey: MasterKey, dataKey: DataKey): ByteArray =
        aeadSeal(masterKey.bytes, dataKey.bytes, WRAP_AAD)

    override fun unwrapDataKey(masterKey: MasterKey, wrapped: ByteArray): DataKey? =
        aeadOpen(masterKey.bytes, wrapped, WRAP_AAD)?.let { DataKey(it) }

    override fun seal(dataKey: DataKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aeadSeal(dataKey.bytes, plaintext, associatedData)

    override fun open(dataKey: DataKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray? =
        aeadOpen(dataKey.bytes, ciphertext, associatedData)

    /** nonce‖XChaCha20-Poly1305(key, plaintext; ad). Nonce случайный — повтор key безопасен. */
    private fun aeadSeal(key: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        requireInitialized()
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        val nonce = LibsodiumRandom.buf(NPUB_BYTES)
        val keyCopy = key.toUByteArray() // промежуточная копия ключа — затираем в finally
        try {
            val cipher = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
                message = plaintext.toUByteArray(),
                associatedData = ad.toUByteArray(),
                nonce = nonce,
                key = keyCopy,
            )
            return nonce.toByteArray() + cipher.toByteArray()
        } finally {
            keyCopy.fill(0u)
        }
    }

    /** Обратное к [aeadSeal]; `null` ⇒ провал AEAD-тега (неверный ключ, подмена или чужой ad). */
    private fun aeadOpen(key: ByteArray, blob: ByteArray, ad: ByteArray): ByteArray? {
        requireInitialized()
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        // Слишком короткий blob трактуем как обычный провал AEAD (null), а НЕ как программную ошибку:
        // blob может прийти из недоверенного источника (запись sync-сервера кладётся mergeRemote'ом как
        // есть). Бросок здесь ронял бы весь список при первом же чтении — DoS через вредоносный сервер.
        if (blob.size < NPUB_BYTES + ABYTES) return null
        val nonce = blob.copyOfRange(0, NPUB_BYTES).toUByteArray()
        val cipher = blob.copyOfRange(NPUB_BYTES, blob.size).toUByteArray()
        // ionspin сигнализирует провал тега исключением, а контракт VaultCrypto ждёт null —
        // это ожидаемый, обрабатываемый исход (неверный ключ/пароль, подмена, чужой AAD).
        val keyCopy = key.toUByteArray() // промежуточная копия ключа — затираем в finally
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = cipher,
                associatedData = ad.toUByteArray(),
                nonce = nonce,
                key = keyCopy,
            ).toByteArray()
        } catch (_: AeadCorrupedOrTamperedDataException) {
            null
        } finally {
            keyCopy.fill(0u)
        }
    }

    // check, не require: неинициализированный libsodium — нарушение порядка запуска приложения
    // (IllegalState), а не некорректный аргумент вызова.
    private fun requireInitialized() =
        check(LibsodiumInitializer.isInitialized()) {
            "libsodium is not initialized; call LibsodiumInitializer.initialize() at app startup"
        }

    private companion object {
        val KEY_BYTES = crypto_aead_xchacha20poly1305_ietf_KEYBYTES   // 32 = размер master/data ключа
        val NPUB_BYTES = crypto_aead_xchacha20poly1305_ietf_NPUBBYTES // 24
        val ABYTES = crypto_aead_xchacha20poly1305_ietf_ABYTES        // 16 (тег Poly1305)

        // Параметры Argon2id из docs/skerry-sync-design.md §1 заданы явными литералами, а не
        // через libsodium-пресеты (INTERACTIVE/MODERATE): пресеты связывают свои t и m в пары,
        // и подмена литерала именованной константой молча изменила бы стойкость. Параллелизм p
        // из спецификации cryptoPwHash фиксируется в 1 — ограничение libsodium, единое для всех.
        const val OPS_LIMIT = 3UL                     // t = 3 итерации
        const val MEM_LIMIT: Int = 64 * 1024 * 1024   // m = 64 MiB (явный Int: страховка от переполнения)

        // Доменный AAD обёртки dataKey: отделяет её от записей (seal с AAD слота), чтобы
        // обёртку нельзя было подставить как запись и наоборот даже при совпадении ключей.
        val WRAP_AAD = "skerry.vault.wrapped-data-key.v1".encodeToByteArray()

        // Доменный AAD конверта быстрого паринга (вариант B): отделяет перенос dataKey под
        // transferKey от обёртки masterKey'ем и от записей — конверт нельзя подставить как иной блоб.
        val TRANSFER_AAD = "skerry.pairing.transfer-key.v1".encodeToByteArray()

        // Деривация authKey: контекст ровно 8 байт (crypto_kdf_CONTEXTBYTES), субключ №1.
        const val AUTH_CONTEXT = "skerryau" // 8 ASCII-символов
        const val AUTH_SUBKEY_ID = 1u
    }
}
