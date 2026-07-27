package app.skerry.shared.ssh

import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType

/**
 * [CaKeyParser] over sshj's wire decoder. Accepts what a user can reasonably paste: a public key
 * line (`ssh-ed25519 AAAA… comment`) or a whole `known_hosts` entry
 * (`@cert-authority *.example.com ssh-ed25519 AAAA…`), whose host pattern is kept.
 *
 * Refused rather than accepted-and-mangled:
 *  - a `*-cert.pub` certificate, which is what a CA *issues* — trusting it would pin one machine's
 *    certificate as an authority;
 *  - any other marker (`@revoked`) — revocation is not implemented, and silently reading it as
 *    trust would invert its meaning;
 *  - a blob that doesn't decode, or decodes to a key of a different type than the line declares.
 */
class SshjCaKeyParser : CaKeyParser {

    override fun parse(text: String): ParsedCaKey? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        var fields = line.split(Regex("\\s+"))
        var hostPattern: String? = null
        if (fields.first().startsWith("@")) {
            if (!fields.first().equals(MARKER_CERT_AUTHORITY, ignoreCase = true) || fields.size < 4) return null
            hostPattern = fields[1]
            fields = fields.drop(2)
        }
        if (fields.size < 2) return null
        val (declaredType, blob) = fields[0] to fields[1]
        val comment = fields.drop(2).joinToString(" ")

        val keyType = KeyType.fromString(declaredType)
        // KeyType.UNKNOWN for anything sshj can't name; a certificate type has a parent key type.
        if (keyType == KeyType.UNKNOWN || keyType.parent != null) return null
        val key = runCatching {
            Buffer.PlainBuffer(Base64.getDecoder().decode(blob)).readPublicKey()
        }.getOrNull() ?: return null
        // The declared type and the blob must agree — otherwise the entry would display one thing
        // and match another.
        if (KeyType.fromKey(key) != keyType) return null

        return ParsedCaKey(
            keyType = keyType.toString(),
            publicKey = blob,
            fingerprint = opensshFingerprint(key),
            hostPattern = hostPattern,
            comment = comment,
        )
    }

    private companion object {
        const val MARKER_CERT_AUTHORITY = "@cert-authority"
    }
}
