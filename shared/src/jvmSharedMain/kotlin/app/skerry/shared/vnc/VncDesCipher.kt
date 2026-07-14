package app.skerry.shared.vnc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC Authentication challenge-response (security type 2): DES-encrypt the server's 16-byte
 * challenge with a key derived from the password. The VNC quirk is that each key byte is
 * BIT-REVERSED (the password bytes are fed to DES least-significant-bit-first) — the load-bearing
 * detail this whole scheme hinges on. The password is truncated to 8 bytes, zero-padded if shorter.
 *
 * Uses plain JCE (`DES/ECB/NoPadding`), available on both JVM targets. Lives in `jvmSharedMain`
 * because it's platform crypto; the pure codec reaches it through [VncChallengeResponder].
 */
object VncDesCipher : VncChallengeResponder {
    override fun respond(password: String, challenge: ByteArray): ByteArray {
        val pw = password.encodeToByteArray()
        val key = ByteArray(8) { i -> if (i < pw.size) reverseBits(pw[i]) else 0 }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    /** Reverse the 8 bits of a byte (VNC feeds each key byte LSB-first to the DES key schedule). */
    internal fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var r = 0
        repeat(8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r.toByte()
    }
}
