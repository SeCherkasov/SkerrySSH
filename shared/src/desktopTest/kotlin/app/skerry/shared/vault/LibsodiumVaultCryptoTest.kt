package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Криптоядро vault проверяется через поведение, а не через внутренние байты ключей:
 * равенство masterKey/dataKey доказывается тем, что обёртка/запись, созданная одним
 * экземпляром ключа, расшифровывается другим. Это держит [MasterKey]/[DataKey]
 * непрозрачными и одновременно проверяет zero-knowledge-инварианты.
 */
class LibsodiumVaultCryptoTest {

    private val crypto: VaultCrypto = LibsodiumVaultCrypto()

    @Test
    fun `seal then open round-trips the plaintext`() {
        val key = crypto.newDataKey()
        val message = "192.168.1.45 root".encodeToByteArray()

        val sealed = crypto.seal(key, message)
        val opened = crypto.open(key, sealed)

        assertContentEquals(message, opened)
    }

    @Test
    fun `seal does not leak the plaintext`() {
        val key = crypto.newDataKey()
        val message = "secret-host-name".encodeToByteArray()

        val sealed = crypto.seal(key, message)

        // шифротекст длиннее открытого текста (nonce + тег) и не содержит его дословно
        assertTrue(sealed.size > message.size)
        assertFalse(sealed.toList().windowed(message.size).any { it == message.toList() })
    }

    @Test
    fun `seal uses a fresh nonce each call`() {
        val key = crypto.newDataKey()
        val message = "same plaintext".encodeToByteArray()

        val a = crypto.seal(key, message)
        val b = crypto.seal(key, message)

        assertFalse(a.contentEquals(b))
        assertContentEquals(message, crypto.open(key, a))
        assertContentEquals(message, crypto.open(key, b))
    }

    @Test
    fun `open returns null when the ciphertext is tampered`() {
        val key = crypto.newDataKey()
        val sealed = crypto.seal(key, "payload".encodeToByteArray())

        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        assertNull(crypto.open(key, sealed))
    }

    @Test
    fun `open returns null with a different data key`() {
        val sealed = crypto.seal(crypto.newDataKey(), "payload".encodeToByteArray())

        assertNull(crypto.open(crypto.newDataKey(), sealed))
    }

    @Test
    fun `open binds ciphertext to its associated data`() {
        val key = crypto.newDataKey()
        val payload = "host password".encodeToByteArray()
        val sealed = crypto.seal(key, payload, associatedData = "host-42".encodeToByteArray())

        // правильный AAD — открывается
        assertContentEquals(payload, crypto.open(key, sealed, "host-42".encodeToByteArray()))
        // чужой AAD (перестановка записи в другой слот) — тег не проходит
        assertNull(crypto.open(key, sealed, "host-99".encodeToByteArray()))
        // отсутствующий AAD — тоже не открывается
        assertNull(crypto.open(key, sealed))
    }

    @Test
    fun `seal and open round-trips empty plaintext`() {
        val key = crypto.newDataKey()

        val opened = crypto.open(key, crypto.seal(key, ByteArray(0)))

        assertNotNull(opened)
        assertEquals(0, opened.size)
    }

    @Test
    fun `open throws on a blob too short to hold a nonce and tag`() {
        // contract VaultCrypto: структурно некорректный вход — программная ошибка, не null
        assertFailsWith<IllegalArgumentException> {
            crypto.open(crypto.newDataKey(), ByteArray(39)) // NPUB(24)+ABYTES(16)-1
        }
    }

    @Test
    fun `wrap then unwrap recovers a working data key`() {
        val salt = crypto.newSalt()
        val masterKey = crypto.deriveMasterKey("correct horse".toCharArray(), salt)
        val dataKey = crypto.newDataKey()
        val record = crypto.seal(dataKey, "host record".encodeToByteArray())

        val wrapped = crypto.wrapDataKey(masterKey, dataKey)
        val unwrapped = crypto.unwrapDataKey(masterKey, wrapped)

        assertNotNull(unwrapped)
        // развёрнутый ключ функционально совпадает с исходным
        assertContentEquals("host record".encodeToByteArray(), crypto.open(unwrapped, record))
    }

    @Test
    fun `unwrapDataKey returns null when the wrapped blob is tampered`() {
        val salt = crypto.newSalt()
        val masterKey = crypto.deriveMasterKey("pass".toCharArray(), salt)
        val wrapped = crypto.wrapDataKey(masterKey, crypto.newDataKey())

        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1].toInt() xor 0xFF).toByte()

        assertNull(crypto.unwrapDataKey(masterKey, wrapped))
    }

    @Test
    fun `unwrapDataKey returns null for a wrong master password`() {
        val salt = crypto.newSalt()
        val right = crypto.deriveMasterKey("right".toCharArray(), salt)
        val wrong = crypto.deriveMasterKey("wrong".toCharArray(), salt)
        val wrapped = crypto.wrapDataKey(right, crypto.newDataKey())

        assertNull(crypto.unwrapDataKey(wrong, wrapped))
    }

    @Test
    fun `deriveMasterKey is deterministic for the same password and salt`() {
        val salt = crypto.newSalt()
        val k1 = crypto.deriveMasterKey("master".toCharArray(), salt)
        val k2 = crypto.deriveMasterKey("master".toCharArray(), salt)
        val dataKey = crypto.newDataKey()

        // обёртка ключом из первой деривации разворачивается ключом из второй
        val wrapped = crypto.wrapDataKey(k1, dataKey)
        assertNotNull(crypto.unwrapDataKey(k2, wrapped))
    }

    @Test
    fun `deriveMasterKey differs for a different salt`() {
        val masterKey1 = crypto.deriveMasterKey("master".toCharArray(), crypto.newSalt())
        val masterKey2 = crypto.deriveMasterKey("master".toCharArray(), crypto.newSalt())
        val wrapped = crypto.wrapDataKey(masterKey1, crypto.newDataKey())

        // другая соль → другой masterKey → обёртка не разворачивается
        assertNull(crypto.unwrapDataKey(masterKey2, wrapped))
    }

    @Test
    fun `newSalt has the libsodium salt length and is random`() {
        val a = crypto.newSalt()
        val b = crypto.newSalt()

        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }
}
