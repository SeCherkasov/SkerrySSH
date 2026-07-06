package app.skerry.shared.vault

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Asymmetric layer for team sharing: a sealed envelope (crypto_box_seal, X25519) lets a teamKey
 * be sealed to a member's public key so only the holder of the secret half can open it. As in
 * [IonspinVaultCryptoTest], invariants are checked by behavior (opens/doesn't), not byte comparison.
 */
class SealedEnvelopeCryptoTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()

    private fun cryptoTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `sealed envelope round-trips to the recipient key pair`() = cryptoTest {
        val recipient = crypto.newSharingKeyPair()
        val payload = "team-key-material".encodeToByteArray()

        val envelope = crypto.sealForRecipient(recipient.publicKey, payload)
        val opened = crypto.openSealedEnvelope(recipient, envelope)

        assertContentEquals(payload, opened)
    }

    @Test
    fun `sealed envelope cannot be opened with a different key pair`() = cryptoTest {
        val recipient = crypto.newSharingKeyPair()
        val envelope = crypto.sealForRecipient(recipient.publicKey, "secret".encodeToByteArray())

        assertNull(crypto.openSealedEnvelope(crypto.newSharingKeyPair(), envelope))
    }

    @Test
    fun `sealed envelope rejects tampering`() = cryptoTest {
        val recipient = crypto.newSharingKeyPair()
        val envelope = crypto.sealForRecipient(recipient.publicKey, "secret".encodeToByteArray())

        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0x01).toByte()

        assertNull(crypto.openSealedEnvelope(recipient, envelope))
    }

    @Test
    fun `openSealedEnvelope returns null on a blob too short for a sealed box`() = cryptoTest {
        // Envelope comes from the server (untrusted source): garbage input yields null, not a throw.
        val recipient = crypto.newSharingKeyPair()

        assertNull(crypto.openSealedEnvelope(recipient, ByteArray(0)))
        assertNull(crypto.openSealedEnvelope(recipient, ByteArray(47))) // SEALBYTES(48) - 1
    }

    @Test
    fun `sealForRecipient uses fresh ephemeral material each call`() = cryptoTest {
        val recipient = crypto.newSharingKeyPair()
        val payload = "same payload".encodeToByteArray()

        val a = crypto.sealForRecipient(recipient.publicKey, payload)
        val b = crypto.sealForRecipient(recipient.publicKey, payload)

        assertFalse(a.contentEquals(b))
        assertContentEquals(payload, crypto.openSealedEnvelope(recipient, a))
        assertContentEquals(payload, crypto.openSealedEnvelope(recipient, b))
    }

    @Test
    fun `sealed envelope round-trips empty payload`() = cryptoTest {
        val recipient = crypto.newSharingKeyPair()

        val opened = crypto.openSealedEnvelope(recipient, crypto.sealForRecipient(recipient.publicKey, ByteArray(0)))

        assertNotNull(opened)
        assertEquals(0, opened.size)
    }

    @Test
    fun `newSharingKeyPair yields distinct pairs with X25519-sized public keys`() = cryptoTest {
        val a = crypto.newSharingKeyPair()
        val b = crypto.newSharingKeyPair()

        assertEquals(32, a.publicKey.size)
        assertFalse(a.publicKey.contentEquals(b.publicKey))
    }

    @Test
    fun `sharing key pair survives serialization of both halves`() = cryptoTest {
        // The pair is stored as a vault record (TEAM_IDENTITY) and restored on another device:
        // the pair rebuilt from bytes must open envelopes sealed to the original.
        val original = crypto.newSharingKeyPair()
        val restored = crypto.sharingKeyPairFromBytes(original.publicKey, original.exportSecretKey())
        val envelope = crypto.sealForRecipient(original.publicKey, "roundtrip".encodeToByteArray())

        assertContentEquals("roundtrip".encodeToByteArray(), crypto.openSealedEnvelope(restored, envelope))
    }
}
