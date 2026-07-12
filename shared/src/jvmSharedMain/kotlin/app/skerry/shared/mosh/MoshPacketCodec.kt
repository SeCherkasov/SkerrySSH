package app.skerry.shared.mosh

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.OCBBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * AES-128-OCB (RFC 7253, 128-bit tag) — mosh's datagram cipher. Uses BouncyCastle's
 * lightweight API directly: no JCA provider lookup, so the desktop release keeps working
 * without the signed-jar constraints noted in composeApp/build.gradle.kts.
 */
class MoshCipher(key: ByteArray) {
    private val key = KeyParameter(key)

    fun seal(nonce: ByteArray, plaintext: ByteArray): ByteArray =
        process(forEncryption = true, nonce = nonce, input = plaintext)
            ?: error("OCB encryption cannot fail")

    /** Returns null when the tag doesn't verify (tampered/foreign datagram). */
    fun open(nonce: ByteArray, ciphertext: ByteArray): ByteArray? =
        process(forEncryption = false, nonce = nonce, input = ciphertext)

    private fun process(forEncryption: Boolean, nonce: ByteArray, input: ByteArray): ByteArray? {
        val ocb = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
        ocb.init(forEncryption, AEADParameters(key, TAG_BITS, nonce))
        val out = ByteArray(ocb.getOutputSize(input.size))
        val n = ocb.processBytes(input, 0, input.size, out, 0)
        return try {
            val total = n + ocb.doFinal(out, n)
            if (total == out.size) out else out.copyOf(total)
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    companion object {
        const val TAG_BITS = 128
        const val TAG_SIZE = TAG_BITS / 8
    }
}

/** One decrypted mosh datagram: 63-bit sequence, direction, 16-bit timestamps, payload. */
data class MoshPacket(
    val seq: ULong,
    val toServer: Boolean,
    val timestamp: Int,
    val timestampReply: Int,
    val payload: ByteArray,
)

/**
 * Wire form of a mosh datagram: 8 bytes of big-endian nonce material (top bit = direction,
 * low 63 bits = sequence) followed by OCB ciphertext of `ts ‖ ts_reply ‖ payload`. The full
 * OCB nonce is those 8 bytes left-padded with 4 zero bytes.
 */
class MoshPacketCodec(key: MoshKey) {
    private val cipher = MoshCipher(key.bytes)

    fun seal(packet: MoshPacket): ByteArray {
        val directionSeq = packet.seq or if (packet.toServer) 0uL else DIRECTION_BIT
        val nonce = nonceFor(directionSeq)
        val plaintext = ByteArray(4 + packet.payload.size)
        plaintext[0] = (packet.timestamp shr 8).toByte()
        plaintext[1] = packet.timestamp.toByte()
        plaintext[2] = (packet.timestampReply shr 8).toByte()
        plaintext[3] = packet.timestampReply.toByte()
        packet.payload.copyInto(plaintext, 4)
        return nonce.copyOfRange(4, 12) + cipher.seal(nonce, plaintext)
    }

    /** Returns null for anything that isn't a valid datagram under our session key. */
    fun open(datagram: ByteArray): MoshPacket? {
        if (datagram.size < 8 + 4 + MoshCipher.TAG_SIZE) return null
        var directionSeq = 0uL
        for (i in 0 until 8) directionSeq = (directionSeq shl 8) or (datagram[i].toULong() and 0xFFu)
        val plaintext = cipher.open(nonceFor(directionSeq), datagram.copyOfRange(8, datagram.size))
            ?: return null
        return MoshPacket(
            seq = directionSeq and DIRECTION_BIT.inv(),
            toServer = directionSeq and DIRECTION_BIT == 0uL,
            timestamp = ((plaintext[0].toInt() and 0xFF) shl 8) or (plaintext[1].toInt() and 0xFF),
            timestampReply = ((plaintext[2].toInt() and 0xFF) shl 8) or (plaintext[3].toInt() and 0xFF),
            payload = plaintext.copyOfRange(4, plaintext.size),
        )
    }

    private fun nonceFor(directionSeq: ULong): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (directionSeq shr ((7 - i) * 8)).toByte()
        return nonce
    }

    companion object {
        private const val DIRECTION_BIT: ULong = 0x8000_0000_0000_0000uL
    }
}
