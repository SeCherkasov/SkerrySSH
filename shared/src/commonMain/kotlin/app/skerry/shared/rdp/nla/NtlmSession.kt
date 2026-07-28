package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.RdpProtocolException

/** Which end of the exchange a session speaks as; it decides which key pair seals and which verifies. */
enum class NtlmRole { Client, Server }

/**
 * NTLM session security (MS-NLMP 3.4): the sealing and signing the CredSSP layer wraps its public
 * key and credentials with.
 *
 * Each direction has its own signing key, its own sealing keystream and its own sequence number,
 * and the keystream is *running* — messages decrypt only in the order they were sealed. That is the
 * property CredSSP leans on: a replayed public-key message cannot be slotted back into the stream.
 */
class NtlmSession(
    private val crypto: NtlmCrypto,
    val exportedSessionKey: ByteArray,
    role: NtlmRole,
) {
    private val clientSignKey = signKey(crypto, exportedSessionKey, CLIENT_TO_SERVER_SIGN)
    private val serverSignKey = signKey(crypto, exportedSessionKey, SERVER_TO_CLIENT_SIGN)
    private val clientSeal = Rc4(signKey(crypto, exportedSessionKey, CLIENT_TO_SERVER_SEAL))
    private val serverSeal = Rc4(signKey(crypto, exportedSessionKey, SERVER_TO_CLIENT_SEAL))

    private val outgoingSignKey = if (role == NtlmRole.Client) clientSignKey else serverSignKey
    private val incomingSignKey = if (role == NtlmRole.Client) serverSignKey else clientSignKey
    private val outgoingSeal = if (role == NtlmRole.Client) clientSeal else serverSeal
    private val incomingSeal = if (role == NtlmRole.Client) serverSeal else clientSeal

    private var outgoingSequence = 0
    private var incomingSequence = 0

    /**
     * GSS_WrapEx: encrypt [message] and prefix the signature, as CredSSP expects in `pubKeyAuth`
     * and `authInfo`.
     */
    fun seal(message: ByteArray): ByteArray {
        // Order matters: the payload consumes the keystream first, then the checksum does. Swap the
        // two and every byte after the first message decrypts to noise on the peer.
        val sealed = outgoingSeal.process(message)
        val signature = sign(outgoingSignKey, outgoingSeal, outgoingSequence, message)
        outgoingSequence++
        return signature + sealed
    }

    /**
     * Verify and decrypt a message sealed by the peer.
     *
     * @throws RdpProtocolException the signature does not match — a tampered, reordered or replayed
     * message, all of which are the same answer: stop.
     */
    fun unseal(message: ByteArray): ByteArray {
        if (message.size < SIGNATURE_SIZE) throw RdpProtocolException("NTLM message shorter than its signature")
        val signature = message.copyOfRange(0, SIGNATURE_SIZE)
        val plain = incomingSeal.process(message.copyOfRange(SIGNATURE_SIZE, message.size))
        val expected = sign(incomingSignKey, incomingSeal, incomingSequence, plain)
        incomingSequence++
        if (!constantTimeEquals(expected, signature)) {
            throw RdpProtocolException("NTLM signature mismatch")
        }
        return plain
    }

    private fun sign(signKey: ByteArray, seal: Rc4, sequence: Int, message: ByteArray): ByteArray {
        val sequenceBytes = byteArrayOf(
            sequence.toByte(),
            (sequence ushr 8).toByte(),
            (sequence ushr 16).toByte(),
            (sequence ushr 24).toByte(),
        )
        val checksum = crypto.hmacMd5(signKey, sequenceBytes + message).copyOfRange(0, CHECKSUM_SIZE)
        // With key exchange negotiated the checksum rides the same keystream as the payload.
        val sealedChecksum = seal.process(checksum)
        return byteArrayOf(1, 0, 0, 0) + sealedChecksum + sequenceBytes
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private companion object {
        const val SIGNATURE_SIZE = 16
        const val CHECKSUM_SIZE = 8

        // MS-NLMP 3.4.5.2/3.4.5.3 — the magic constants include their null terminator.
        const val CLIENT_TO_SERVER_SIGN = "session key to client-to-server signing key magic constant"
        const val SERVER_TO_CLIENT_SIGN = "session key to server-to-client signing key magic constant"
        const val CLIENT_TO_SERVER_SEAL = "session key to client-to-server sealing key magic constant"
        const val SERVER_TO_CLIENT_SEAL = "session key to server-to-client sealing key magic constant"

        fun signKey(crypto: NtlmCrypto, exportedSessionKey: ByteArray, magic: String): ByteArray =
            crypto.md5(exportedSessionKey + magic.encodeToByteArray() + ByteArray(1))
    }
}
