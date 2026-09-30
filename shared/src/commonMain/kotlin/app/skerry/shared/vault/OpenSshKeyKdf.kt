package app.skerry.shared.vault

import kotlin.io.encoding.Base64

/** What an `openssh-key-v1` key's header says it costs to decrypt, as sshj would read it. */
sealed interface OpenSshKdf {
    /** sshj would not read the text as `openssh-key-v1` at all (PKCS#1/PKCS#8, PuTTY, not a key). */
    data object NotOpenSsh : OpenSshKdf

    /** bcrypt rounds the key asks for; 0 for an unencrypted key. */
    data class Rounds(val count: Long) : OpenSshKdf

    /** sshj would treat it as `openssh-key-v1`, but its header can't be read here. */
    data object Unreadable : OpenSshKdf
}

/**
 * Reads the kdf of the block sshj would load from [pem], found the way sshj finds it: format by the
 * first non-blank line, then the first line starting `-----BEGIN ` up to one starting `-----END `.
 *
 * The rounds are chosen by whoever wrote the key and sshj runs them unbounded — with no passphrase
 * too — in a blocking call nothing can interrupt. A key pasted from someone else can therefore pin a
 * CPU for hours; reading the count first is what lets a caller refuse it (see [kdfCostAcceptable]).
 */
fun readOpenSshKdf(pem: String): OpenSshKdf {
    val lines = pem.lines()
    val first = lines.map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return OpenSshKdf.NotOpenSsh
    val openSsh = first.startsWith("-----BEGIN") && first.endsWith("PRIVATE KEY-----") && OPENSSH_LABEL in first
    if (!openSsh) return OpenSshKdf.NotOpenSsh
    val header = lines.indexOfFirst { it.startsWith(BEGIN) }
    val footer = lines.withIndex().indexOfFirst { (i, line) -> i > header && line.startsWith(END) }
    if (header < 0 || footer < 0) return OpenSshKdf.Unreadable
    val body = lines.subList(header + 1, footer).joinToString("").filterNot { it.isWhitespace() }
    return kdfOf(body) ?: OpenSshKdf.Unreadable
}

/** Whether sshj may be handed [pem]: a known cost within [MAX_KDF_ROUNDS], or no bcrypt at all. */
fun kdfCostAcceptable(pem: String): Boolean = when (val kdf = readOpenSshKdf(pem)) {
    OpenSshKdf.NotOpenSsh -> true
    OpenSshKdf.Unreadable -> false
    is OpenSshKdf.Rounds -> kdf.count <= MAX_KDF_ROUNDS
}

private fun kdfOf(body: String): OpenSshKdf.Rounds? {
    val bytes = runCatching { LENIENT_BASE64.decode(body) }.getOrNull() ?: return null
    val reader = SshWireReader(bytes)
    if (!reader.hasPrefix(MAGIC)) return null
    reader.skip(MAGIC.size)
    reader.string() ?: return null // cipher
    val kdf = reader.string()?.decodeToString() ?: return null
    val options = SshWireReader(reader.string() ?: return null)
    return when (kdf) {
        "none" -> OpenSshKdf.Rounds(0)
        "bcrypt" -> {
            options.string() ?: return null // salt
            options.uint32()?.let(OpenSshKdf::Rounds)
        }
        else -> null
    }
}

/**
 * Most bcrypt rounds a key is read with. OpenSSH writes 16 by default and `ssh-keygen -a 100` is
 * the usual hardening advice; a thousand is already seconds per attempt on a phone.
 */
const val MAX_KDF_ROUNDS: Long = 1024

// sshj's own markers (OpenSSHKeyV1KeyFile): the header must continue with the label right after BEGIN.
private const val BEGIN = "-----BEGIN "
private const val END = "-----END "
private const val OPENSSH_LABEL = "OPENSSH PRIVATE KEY-----"
// java.util.Base64, which sshj decodes with, does not require the padding.
private val LENIENT_BASE64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
private val MAGIC = "openssh-key-v1".encodeToByteArray() + 0

/** Just enough of the SSH wire format for the key header: length-prefixed strings and a uint32. */
private class SshWireReader(private val bytes: ByteArray) {
    private var pos = 0

    fun hasPrefix(prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    fun skip(n: Int) { pos += n }

    fun uint32(): Long? {
        if (pos + 4 > bytes.size) return null
        var v = 0L
        repeat(4) { v = (v shl 8) or (bytes[pos + it].toLong() and 0xFF) }
        pos += 4
        return v
    }

    fun string(): ByteArray? {
        val len = uint32() ?: return null
        if (len > bytes.size - pos) return null
        return bytes.copyOfRange(pos, pos + len.toInt()).also { pos += len.toInt() }
    }
}
