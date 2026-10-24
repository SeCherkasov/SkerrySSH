package app.skerry.shared.securitykey

import app.skerry.shared.vault.GeneratedSshKey
import app.skerry.shared.vault.SshPublicKeyInfo
import kotlin.io.encoding.Base64
import kotlin.random.Random
import okio.Buffer
import okio.ByteString.Companion.toByteString

/** OpenSSH PROTOCOL.u2f stub; never contains a software private scalar or provider path. */
internal fun encodeSecurityKey(options: SecurityKeyGenerationOptions, publicBytes: ByteArray, handle: ByteArray): GeneratedSshKey {
    require(handle.size in 1..4096)
    require(publicBytes.size == if (options.algorithm == SecurityKeyAlgorithm.ED25519) 32 else 65)
    val pub = wire {
        string(options.algorithm.wireName)
        if (options.algorithm == SecurityKeyAlgorithm.ECDSA) string("nistp256")
        string(publicBytes)
        string(options.application)
    }
    val check = Random.nextInt()
    val private = wire {
        writeInt(check); writeInt(check)
        string(options.algorithm.wireName)
        if (options.algorithm == SecurityKeyAlgorithm.ECDSA) string("nistp256")
        string(publicBytes); string(options.application)
        writeByte(options.flags or if (options.resident) 0x20 else 0)
        string(handle); string(byteArrayOf()); string(options.comment)
    }.let { it + ByteArray(8 - it.size % 8) { n -> (n + 1).toByte() } }
    val encoded = wire {
        write("openssh-key-v1\u0000".encodeToByteArray())
        string("none"); string("none"); string(byteArrayOf()); writeInt(1); string(pub); string(private)
    }
    return try {
        GeneratedSshKey(
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" + Base64.encode(encoded).chunked(64).joinToString("\n") +
                "\n-----END OPENSSH PRIVATE KEY-----\n",
            SshPublicKeyInfo(
                options.algorithm.wireName + " " + Base64.encode(pub) + options.comment.let { if (it.isEmpty()) "" else " $it" },
                "SHA256:" + pub.toByteString().sha256().base64().trimEnd('='),
                options.algorithm.wireName,
                SecurityKeyMetadata(options.application, options.resident, options.flags),
            ),
        )
    } finally { private.fill(0); encoded.fill(0) }
}
private class Wire {
    val buffer = Buffer()
    fun writeInt(value: Int) { buffer.writeInt(value) }
    fun writeByte(value: Int) { buffer.writeByte(value) }
    fun write(value: ByteArray) { buffer.write(value) }
    fun string(value: String) = string(value.encodeToByteArray())
    fun string(value: ByteArray) { writeInt(value.size); write(value) }
}
private fun wire(block: Wire.() -> Unit): ByteArray = Wire().apply(block).buffer.readByteArray()


internal class DecodedSecurityKey(
    val application: String, val publicKey: ByteArray, val keyHandle: ByteArray, val flags: Int,
)

/** Bounded unencrypted-stub parser, also validates inner/public copies and the OpenSSH padding. */
internal fun decodeUnencryptedSecurityKey(pem: String): DecodedSecurityKey {
    require(pem.length <= 262144)
    val trimmed = pem.trim()
    val header = "-----BEGIN OPENSSH PRIVATE KEY-----"
    val footer = "-----END OPENSSH PRIVATE KEY-----"
    require(trimmed.startsWith(header) && trimmed.endsWith(footer))
    val raw = Base64.decode(trimmed.removePrefix(header).removeSuffix(footer).filterNot(Char::isWhitespace))
    val reader = BoundedWire(raw)
    require(reader.raw(15).contentEquals("openssh-key-v1\u0000".encodeToByteArray()))
    require(reader.string().decodeToString() == "none" && reader.string().decodeToString() == "none")
    require(reader.string().isEmpty() && reader.uint32() == 1L)
    val public = BoundedWire(reader.string())
    val private = BoundedWire(reader.string())
    require(reader.remaining == 0)
    require(private.uint32() == private.uint32())
    fun fields(source: BoundedWire): Triple<String, ByteArray, String> {
        val type = source.string().decodeToString()
        require(type == SecurityKeyAlgorithm.ED25519.wireName || type == SecurityKeyAlgorithm.ECDSA.wireName)
        if (type == SecurityKeyAlgorithm.ECDSA.wireName) require(source.string().decodeToString() == "nistp256")
        val key = source.string()
        require(key.size == if (type == SecurityKeyAlgorithm.ED25519.wireName) 32 else 65)
        return Triple(type, key, source.string().decodeToString())
    }
    val pub = fields(public)
    val priv = fields(private)
    require(public.remaining == 0 && pub.first == priv.first && pub.second.contentEquals(priv.second) && pub.third == priv.third)
    val flags = private.raw(1)[0].toInt() and 255
    require(flags and 0x25 == flags)
    val handle = private.string()
    require(handle.size in 1..4096 && private.string().isEmpty())
    private.string() // comment
    require(private.remaining in 1..8)
    val padding = private.raw(private.remaining)
    require(padding.indices.all { padding[it].toInt() == it + 1 })
    SecurityKeyGenerationOptions(application = pub.third)
    return DecodedSecurityKey(pub.third, pub.second, handle, flags)
}
private class BoundedWire(private val bytes: ByteArray) {
    private var position = 0
    val remaining get() = bytes.size - position
    fun raw(size: Int): ByteArray {
        require(size in 0..remaining)
        return bytes.copyOfRange(position, position + size).also { position += size }
    }
    fun uint32(): Long = raw(4).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 255) }
    fun string(): ByteArray {
        val size = uint32()
        require(size <= remaining && size <= 262144)
        return raw(size.toInt())
    }
}

/** Validates public SK fields before any KDF/hardware side effect; inner copies when unencrypted. */
internal fun validateSecurityKeyEnvelope(pem: String) {
    if (!pem.trimStart().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) return
    require(pem.length <= 262144)
    val body = pem.trim().removePrefix("-----BEGIN OPENSSH PRIVATE KEY-----").removeSuffix("-----END OPENSSH PRIVATE KEY-----")
    val reader = BoundedWire(Base64.decode(body.filterNot(Char::isWhitespace)))
    require(reader.raw(15).contentEquals("openssh-key-v1\u0000".encodeToByteArray()))
    val cipher = reader.string().decodeToString()
    reader.string(); reader.string()
    require(reader.uint32() == 1L)
    val public = BoundedWire(reader.string())
    val type = public.string().decodeToString()
    if (type != SecurityKeyAlgorithm.ED25519.wireName && type != SecurityKeyAlgorithm.ECDSA.wireName) return
    if (type == SecurityKeyAlgorithm.ECDSA.wireName) require(public.string().decodeToString() == "nistp256")
    val key = public.string()
    require(key.size == if (type == SecurityKeyAlgorithm.ED25519.wireName) 32 else 65)
    SecurityKeyGenerationOptions(application = public.string().decodeToString())
    require(public.remaining == 0)
    if (cipher == "none") decodeUnencryptedSecurityKey(pem)
}

/** PROTOCOL.u2f signed bytes: SHA256(application) || flags || counter(big endian) || challenge. */
internal fun securityKeySignedData(request: SecurityKeySignRequest, assertion: SecurityKeyAssertion): ByteArray {
    validateAssertion(request, assertion)
    return wire {
        write(request.application.encodeToByteArray().toByteString().sha256().toByteArray())
        writeByte(assertion.flags)
        writeInt(assertion.counter.toInt())
        write(request.challenge)
    }
}
