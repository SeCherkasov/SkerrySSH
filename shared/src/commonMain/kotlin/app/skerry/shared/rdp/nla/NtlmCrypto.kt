package app.skerry.shared.rdp.nla

/**
 * The primitives NTLM needs, injected rather than called directly so the whole NTLM/CredSSP state
 * machine stays in `commonMain` and is testable against the MS-NLMP vectors without a platform.
 *
 * MD4 and MD5 are here because NTLM is built on them, not because they are good: the NT hash is
 * MD4 of the password and cannot be changed by a client. They are used for nothing else.
 */
interface NtlmCrypto {
    /** MD4 — only ever applied to the UTF-16LE password to form the NT hash. */
    fun md4(data: ByteArray): ByteArray

    fun md5(data: ByteArray): ByteArray

    fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray

    /** Cryptographically secure random bytes (client challenge, session key, CredSSP nonce). */
    fun randomBytes(count: Int): ByteArray
}

/**
 * RC4 as NTLM session security uses it: one keystream per direction, kept across messages, so the
 * sixth PDU decrypts only if the first five went through the same handle.
 *
 * Implemented here rather than taken from a provider because RC4 has been dropped from JCE default
 * configurations and is not available on every Android image — while NTLM's sealing has no
 * alternative to negotiate.
 */
class Rc4(key: ByteArray) {
    private val state = IntArray(256) { it }
    private var i = 0
    private var j = 0

    init {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        var k = 0
        for (index in 0 until 256) {
            k = (k + state[index] + (key[index % key.size].toInt() and 0xFF)) and 0xFF
            val swap = state[index]
            state[index] = state[k]
            state[k] = swap
        }
    }

    /** Encrypt (or decrypt — RC4 is symmetric) [data], advancing the keystream. */
    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (index in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
            out[index] = (data[index].toInt() xor state[(state[i] + state[j]) and 0xFF]).toByte()
        }
        return out
    }
}
