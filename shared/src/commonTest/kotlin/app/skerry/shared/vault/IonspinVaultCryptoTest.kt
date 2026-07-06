package app.skerry.shared.vault

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vault crypto core is verified through behavior, not internal key bytes: masterKey/dataKey
 * equality is proven by a wrapping/record created with one key instance decrypting with another.
 * This keeps [MasterKey]/[DataKey] opaque while still verifying zero-knowledge invariants.
 *
 * Tests are shared across all targets (commonTest): JUnit5 on desktop, the native runner on
 * Android. libsodium needs async init before first use, so each test is wrapped in [cryptoTest]
 * (runTest + idempotent [initializeVaultCrypto]).
 */
class IonspinVaultCryptoTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()

    /** Ensures libsodium is initialized before the test body; init is idempotent. */
    private fun cryptoTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `deriveAuthKey is deterministic per masterKey and 32 bytes`() = cryptoTest {
        val salt = crypto.newSalt()
        val mk1 = crypto.deriveMasterKey("pw".toCharArray(), salt)
        val mk2 = crypto.deriveMasterKey("pw".toCharArray(), salt) // same password+salt => same masterKey

        val auth1 = crypto.deriveAuthKey(mk1)
        val auth2 = crypto.deriveAuthKey(mk2)

        assertEquals(32, auth1.size)
        assertContentEquals(auth1, auth2) // deterministic

        // different masterKey => different authKey
        val mkOther = crypto.deriveMasterKey("pw".toCharArray(), crypto.newSalt())
        assertFalse(crypto.deriveAuthKey(mkOther).contentEquals(auth1))

        // authKey is domain-separated from the dataKey wrapping: not byte-equal to it
        val dataKey = crypto.newDataKey()
        assertFalse(crypto.wrapDataKey(mk1, dataKey).contentEquals(auth1))
    }

    @Test
    fun `seal then open round-trips the plaintext`() = cryptoTest {
        val key = crypto.newDataKey()
        val message = "192.168.1.45 root".encodeToByteArray()

        val sealed = crypto.seal(key, message, VaultCrypto.EMPTY_AAD)
        val opened = crypto.open(key, sealed, VaultCrypto.EMPTY_AAD)

        assertContentEquals(message, opened)
    }

    @Test
    fun `seal does not leak the plaintext`() = cryptoTest {
        val key = crypto.newDataKey()
        val message = "secret-host-name".encodeToByteArray()

        val sealed = crypto.seal(key, message, VaultCrypto.EMPTY_AAD)

        // ciphertext is longer than the plaintext (nonce + tag) and does not contain it verbatim
        assertTrue(sealed.size > message.size)
        assertFalse(sealed.toList().windowed(message.size).any { it == message.toList() })
    }

    @Test
    fun `seal uses a fresh nonce each call`() = cryptoTest {
        val key = crypto.newDataKey()
        val message = "same plaintext".encodeToByteArray()

        val a = crypto.seal(key, message, VaultCrypto.EMPTY_AAD)
        val b = crypto.seal(key, message, VaultCrypto.EMPTY_AAD)

        assertFalse(a.contentEquals(b))
        assertContentEquals(message, crypto.open(key, a, VaultCrypto.EMPTY_AAD))
        assertContentEquals(message, crypto.open(key, b, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `open returns null when the ciphertext is tampered`() = cryptoTest {
        val key = crypto.newDataKey()
        val sealed = crypto.seal(key, "payload".encodeToByteArray(), VaultCrypto.EMPTY_AAD)

        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        assertNull(crypto.open(key, sealed, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `open returns null with a different data key`() = cryptoTest {
        val sealed = crypto.seal(crypto.newDataKey(), "payload".encodeToByteArray(), VaultCrypto.EMPTY_AAD)

        assertNull(crypto.open(crypto.newDataKey(), sealed, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `open binds ciphertext to its associated data`() = cryptoTest {
        val key = crypto.newDataKey()
        val payload = "host password".encodeToByteArray()
        val sealed = crypto.seal(key, payload, associatedData = "host-42".encodeToByteArray())

        // correct AAD — opens
        assertContentEquals(payload, crypto.open(key, sealed, "host-42".encodeToByteArray()))
        // wrong AAD (record moved to a different slot) — tag check fails
        assertNull(crypto.open(key, sealed, "host-99".encodeToByteArray()))
        // missing AAD — also fails to open
        assertNull(crypto.open(key, sealed, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `seal and open round-trips empty plaintext`() = cryptoTest {
        val key = crypto.newDataKey()

        val opened = crypto.open(key, crypto.seal(key, ByteArray(0), VaultCrypto.EMPTY_AAD), VaultCrypto.EMPTY_AAD)

        assertNotNull(opened)
        assertEquals(0, opened.size)
    }

    @Test
    fun `open returns null on a blob too short to hold a nonce and tag`() = cryptoTest {
        // A too-short blob is a plain AEAD failure (null), not a programming error: the blob may
        // come from an untrusted source (a sync-server record), and throwing would crash the whole
        // list — a DoS vector.
        assertNull(crypto.open(crypto.newDataKey(), ByteArray(39), VaultCrypto.EMPTY_AAD)) // NPUB(24)+ABYTES(16)-1
    }

    @Test
    fun `wrap then unwrap recovers a working data key`() = cryptoTest {
        val salt = crypto.newSalt()
        val masterKey = crypto.deriveMasterKey("correct horse".toCharArray(), salt)
        val dataKey = crypto.newDataKey()
        val record = crypto.seal(dataKey, "host record".encodeToByteArray(), VaultCrypto.EMPTY_AAD)

        val wrapped = crypto.wrapDataKey(masterKey, dataKey)
        val unwrapped = crypto.unwrapDataKey(masterKey, wrapped)

        assertNotNull(unwrapped)
        // the unwrapped key is functionally equivalent to the original
        assertContentEquals("host record".encodeToByteArray(), crypto.open(unwrapped, record, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `unwrapDataKey returns null when the wrapped blob is tampered`() = cryptoTest {
        val salt = crypto.newSalt()
        val masterKey = crypto.deriveMasterKey("pass".toCharArray(), salt)
        val wrapped = crypto.wrapDataKey(masterKey, crypto.newDataKey())

        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1].toInt() xor 0xFF).toByte()

        assertNull(crypto.unwrapDataKey(masterKey, wrapped))
    }

    @Test
    fun `unwrapDataKey returns null for a wrong master password`() = cryptoTest {
        val salt = crypto.newSalt()
        val right = crypto.deriveMasterKey("right".toCharArray(), salt)
        val wrong = crypto.deriveMasterKey("wrong".toCharArray(), salt)
        val wrapped = crypto.wrapDataKey(right, crypto.newDataKey())

        assertNull(crypto.unwrapDataKey(wrong, wrapped))
    }

    @Test
    fun `deriveMasterKey is deterministic for the same password and salt`() = cryptoTest {
        val salt = crypto.newSalt()
        val k1 = crypto.deriveMasterKey("master".toCharArray(), salt)
        val k2 = crypto.deriveMasterKey("master".toCharArray(), salt)
        val dataKey = crypto.newDataKey()

        // a wrapping made with the first derivation unwraps with the second
        val wrapped = crypto.wrapDataKey(k1, dataKey)
        assertNotNull(crypto.unwrapDataKey(k2, wrapped))
    }

    @Test
    fun `deriveMasterKey differs for a different salt`() = cryptoTest {
        val masterKey1 = crypto.deriveMasterKey("master".toCharArray(), crypto.newSalt())
        val masterKey2 = crypto.deriveMasterKey("master".toCharArray(), crypto.newSalt())
        val wrapped = crypto.wrapDataKey(masterKey1, crypto.newDataKey())

        // different salt => different masterKey => the wrapping doesn't unwrap
        assertNull(crypto.unwrapDataKey(masterKey2, wrapped))
    }

    @Test
    fun `newSalt has the libsodium salt length and is random`() = cryptoTest {
        val a = crypto.newSalt()
        val b = crypto.newSalt()

        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `transfer key round-trips the data key to a new device`() = cryptoTest {
        val dataKey = crypto.newDataKey()
        // a record sealed with the original dataKey on device A
        val record = crypto.seal(dataKey, "10.0.0.1 admin".encodeToByteArray(), VaultCrypto.EMPTY_AAD)

        val transferKey = crypto.newTransferKey()
        assertEquals(32, transferKey.size) // XChaCha20 key length — usable as an AEAD key
        val envelope = crypto.sealDataKeyForTransfer(dataKey, transferKey)

        // device B unwraps the dataKey with the transferKey and reads the same record
        val adopted = crypto.openTransferredDataKey(transferKey, envelope)
        assertNotNull(adopted)
        assertContentEquals("10.0.0.1 admin".encodeToByteArray(), crypto.open(adopted, record, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `transfer envelope is useless without the right transfer key`() = cryptoTest {
        val dataKey = crypto.newDataKey()
        val envelope = crypto.sealDataKeyForTransfer(dataKey, crypto.newTransferKey())

        // a foreign transferKey (ciphertext intercepted without the QR) — AEAD failure, null
        assertNull(crypto.openTransferredDataKey(crypto.newTransferKey(), envelope))
    }

    @Test
    fun `newTransferKey is random`() = cryptoTest {
        assertFalse(crypto.newTransferKey().contentEquals(crypto.newTransferKey()))
    }
}
