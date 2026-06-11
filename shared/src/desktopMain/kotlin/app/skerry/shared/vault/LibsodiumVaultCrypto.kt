package app.skerry.shared.vault

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Реализация [VaultCrypto] на libsodium (lazysodium-java; нативка `libsodium.so` встроена
 * в jar и грузится JNA, системная установка не нужна). Соответствует иерархии ключей из
 * `docs/skerry-sync-design.md`: Argon2id(m=64MiB, t=3) → masterKey; XChaCha20-Poly1305 с
 * 24-байтным nonce в префиксе для обёртки dataKey и для каждой записи.
 *
 * Операции stateless и потокобезопасны. Затирание входного пароля — на стороне вызывающего
 * (см. контракт [VaultCrypto.deriveMasterKey]); промежуточный UTF-8-буфер пароля затирается
 * здесь сразу после деривации.
 */
class LibsodiumVaultCrypto(
    private val sodium: LazySodiumJava = LazySodiumJava(SodiumJava()),
) : VaultCrypto {

    override fun newSalt(): ByteArray = sodium.randomBytesBuf(PwHash.SALTBYTES)

    override fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey {
        require(salt.size == PwHash.SALTBYTES) { "salt must be ${PwHash.SALTBYTES} bytes" }
        val pw = utf8(password)
        val out = ByteArray(KEY_BYTES)
        try {
            val ok = sodium.cryptoPwHash(
                out, out.size, pw, pw.size, salt,
                OPS_LIMIT, NativeLong(MEM_LIMIT), PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            )
            if (!ok) {
                out.fill(0) // не оставлять частично записанный ключевой материал в куче
                error("Argon2id derivation failed")
            }
            return MasterKey(out)
        } finally {
            pw.fill(0)
        }
    }

    override fun newDataKey(): DataKey = DataKey(sodium.randomBytesBuf(KEY_BYTES))

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
        val nonce = sodium.randomBytesBuf(NPUB_BYTES)
        val cipher = ByteArray(plaintext.size + ABYTES)
        val cipherLen = LongArray(1)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            cipher, cipherLen, plaintext, plaintext.size.toLong(),
            ad, ad.size.toLong(), null, nonce, key,
        )
        check(ok) { "XChaCha20-Poly1305 encryption failed" }
        return nonce + cipher.copyOf(cipherLen[0].toInt())
    }

    /** Обратное к [aeadSeal]; `null` ⇒ провал AEAD-тега (неверный ключ, подмена или чужой ad). */
    private fun aeadOpen(key: ByteArray, blob: ByteArray, ad: ByteArray): ByteArray? {
        require(blob.size >= NPUB_BYTES + ABYTES) { "blob too short for nonce and tag" }
        val nonce = blob.copyOfRange(0, NPUB_BYTES)
        val cipher = blob.copyOfRange(NPUB_BYTES, blob.size)
        val message = ByteArray(cipher.size - ABYTES)
        val messageLen = LongArray(1)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            message, messageLen, null, cipher, cipher.size.toLong(),
            ad, ad.size.toLong(), nonce, key,
        )
        // В комбинированном режиме длина открытого текста фиксирована (cipher − тег),
        // поэтому возвращаем message напрямую, не плодя вторую копию расшифрованных данных.
        // При провале тега libsodium сам обнуляет message и возвращает false.
        return if (ok) message else null
    }

    /** UTF-8-байты пароля без промежуточной immutable-строки; backing-буфер затирается. */
    private fun utf8(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) {
            // arrayOffset учитывает срезанные буферы; capacity покрывает хвост за limit
            val backing = buffer.array()
            val offset = buffer.arrayOffset()
            backing.fill(0, offset, offset + buffer.capacity())
        } else {
            // direct-буфер (контрактом encode не исключён) — затираем побайтно
            buffer.position(0)
            while (buffer.hasRemaining()) buffer.put(0)
        }
        return out
    }

    private companion object {
        const val KEY_BYTES = AEAD.XCHACHA20POLY1305_IETF_KEYBYTES   // 32 = размер master/data ключа
        const val NPUB_BYTES = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES // 24
        const val ABYTES = AEAD.XCHACHA20POLY1305_IETF_ABYTES        // 16 (тег Poly1305)

        // Параметры Argon2id из docs/skerry-sync-design.md §1 заданы явными литералами, а не
        // через libsodium-пресеты (INTERACTIVE/MODERATE): пресеты связывают свои t и m в пары
        // (MODERATE = t3 + m256MiB), и подмена литерала именованной константой молча изменила
        // бы стойкость. Параллелизм p из спецификации cryptoPwHash фиксирует в 1 и параметром
        // не принимает — ограничение libsodium, единое для всех клиентов.
        const val OPS_LIMIT = 3L                 // t = 3 итерации
        const val MEM_LIMIT = 64L * 1024 * 1024  // m = 64 MiB

        // Доменный AAD обёртки dataKey: отделяет её от записей (seal с AAD слота), чтобы
        // обёртку нельзя было подставить как запись и наоборот даже при совпадении ключей.
        val WRAP_AAD = "skerry.vault.wrapped-data-key.v1".encodeToByteArray()
    }
}
