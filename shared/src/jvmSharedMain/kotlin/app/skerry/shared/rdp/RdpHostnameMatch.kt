package app.skerry.shared.rdp

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** `subjectAltName` entry tags from RFC 5280 §4.2.1.6, as `getSubjectAlternativeNames` reports them. */
private const val SAN_DNS_NAME = 2
private const val SAN_IP_ADDRESS = 7

/** `id-ce-subjectAltName`, read raw to tell "no extension" from "an extension we could not parse". */
private const val SUBJECT_ALT_NAME_OID = "2.5.29.17"

/**
 * Whether [certificate] was issued for [host], by the RFC 6125 rules: `subjectAltName` decides
 * whenever the extension is present at all, the common name only when it is absent, and a literal
 * address matches nothing but an `iPAddress` entry.
 *
 * Not the platform's hostname verifier, which needs a finished `SSLSession` — this answer is needed
 * while the handshake is still running, so that the trust decision can be taken there. It feeds
 * [RdpCertificateOffer.hostnameMatches], which describes the certificate rather than accepting or
 * refusing it: an RDP host commonly names itself something other than the address dialled.
 */
internal fun certificateMatchesHost(host: String, certificate: X509Certificate): Boolean {
    val name = host.trim().trimEnd('.')
    if (name.isEmpty()) return false
    // A subjectAltName we cannot parse is a certificate that names nothing usable, not one without
    // the extension — falling back to the common name there would let a malformed extension pick
    // which name is consulted.
    val hasAlternativeNames = certificate.getExtensionValue(SUBJECT_ALT_NAME_OID) != null
    val alternatives = runCatching { certificate.subjectAlternativeNames }.getOrNull().orEmpty()
        .mapNotNull { entry ->
            val tag = entry.getOrNull(0) as? Int ?: return@mapNotNull null
            val value = entry.getOrNull(1) as? String ?: return@mapNotNull null
            tag to value
        }

    val address = literalAddressOf(name)
    if (address != null) {
        return alternatives.any { (tag, value) ->
            tag == SAN_IP_ADDRESS && literalAddressOf(value)?.contentEquals(address) == true
        }
    }

    val dnsNames = alternatives.filter { it.first == SAN_DNS_NAME }.map { it.second }
    val candidates = when {
        hasAlternativeNames -> dnsNames
        else -> listOfNotNull(commonNameOf(certificate))
    }
    return candidates.any { dnsNameMatches(it, name) }
}

/** One wildcard, leftmost label only, and never one that would cover a whole registry (`*.com`). */
private fun dnsNameMatches(pattern: String, host: String): Boolean {
    val candidate = pattern.trim().trimEnd('.').lowercase()
    val target = host.lowercase()
    if (candidate.isEmpty()) return false
    if (!candidate.startsWith("*.")) return candidate == target
    val suffix = candidate.substring(1)
    if (suffix.count { it == '.' } < 2) return false
    if (!target.endsWith(suffix)) return false
    val label = target.dropLast(suffix.length)
    return label.isNotEmpty() && !label.contains('.')
}

/**
 * [host] as raw address bytes, or null when it is a name rather than a literal. The shape is
 * checked first: `InetAddress.getByName` resolves anything it cannot parse, and a DNS lookup in
 * the middle of a TLS handshake is not what this asks for.
 */
private fun literalAddressOf(host: String): ByteArray? {
    val text = host.removeSurrounding("[", "]")
    if (!IPV4.matches(text) && !isIpv6Literal(text)) return null
    return runCatching { InetAddress.getByName(text).address }.getOrNull()
}

/**
 * The subject's common name, or null when it has none. Hand-rolled because `javax.naming.ldap`
 * does not exist on Android: the RFC 2253 rendering is split on unescaped separators, then the
 * value is unescaped.
 */
private fun commonNameOf(certificate: X509Certificate): String? =
    splitAttributes(certificate.subjectX500Principal.getName(X500Principal.RFC2253))
        .firstNotNullOfOrNull { attribute ->
            val separator = attribute.indexOf('=')
            if (separator < 0) return@firstNotNullOfOrNull null
            if (!attribute.take(separator).trim().equals("CN", ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            // "#0c0477696e" is a DER-encoded value rather than a string; nothing to match against.
            unescape(attribute.substring(separator + 1)).takeUnless { it.startsWith("#") }
        }

/** Split on the separators RFC 2253 gives meaning to — `,` between attributes, `+` within an RDN. */
private fun splitAttributes(distinguishedName: String): List<String> {
    val attributes = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (character in distinguishedName) {
        when {
            escaped -> {
                current.append(character)
                escaped = false
            }

            character == '\\' -> {
                current.append(character)
                escaped = true
            }

            character == ',' || character == '+' -> {
                attributes += current.toString()
                current.clear()
            }

            else -> current.append(character)
        }
    }
    attributes += current.toString()
    return attributes
}

/** Undo RFC 2253 escaping: `\c` is the character itself, `\XX` a byte of the UTF-8 encoding. */
private fun unescape(value: String): String {
    if (!value.contains('\\')) return value.trim()
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character != '\\' || index + 1 >= value.length) {
            bytes.write(character.toString().toByteArray(Charsets.UTF_8))
            index++
            continue
        }
        val pair = value.substring(index + 1).take(2)
        // `toIntOrNull` accepts a leading sign, so "\+1" would decode as the byte 0x01.
        val byte = pair.takeIf { it.length == 2 && it.all(Char::isHexDigit) }?.toIntOrNull(radix = 16)
        if (byte != null) {
            bytes.write(byte)
            index += 3
        } else {
            bytes.write(value[index + 1].toString().toByteArray(Charsets.UTF_8))
            index += 2
        }
    }
    return bytes.toByteArray().decodeToString().trim()
}

private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.isIpv6Character() = isHexDigit() || this == ':' || this == '.'

/**
 * A real IPv6 literal: eight groups, or fewer with one `::` standing in for the rest, optionally
 * ending in a dotted IPv4 form. Anything looser — "any hex and colons" — lets `dead:beef` through
 * to `InetAddress.getByName`, which cannot parse it and falls back to a DNS lookup.
 */
internal fun isIpv6Literal(text: String): Boolean {
    val body = text.substringBefore('%')
    if (body.count { it == ':' } < 2) return false
    if (!body.all(Char::isIpv6Character)) return false
    val elided = body.split("::")
    if (elided.size > 2) return false
    val tail = elided.last()
    val trailingIpv4 = IPV4.matches(tail.substringAfterLast(':', missingDelimiterValue = ""))
    val groups = elided.sumOf { part ->
        part.split(':').count { it.isNotEmpty() }
    } + if (trailingIpv4) 1 else 0
    if (elided.size == 2) return groups < 8
    return groups == 8 && !body.startsWith(":") && !body.endsWith(":")
}
