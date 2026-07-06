package app.skerry.shared.vault

import app.skerry.shared.ssh.ensureCryptoProvider
import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date

/**
 * JVM SSH certificate inspector on sshj (shared desktop+Android, like [BouncyCastleSshKeyGenerator]).
 * A `*-cert.pub` line is parsed by sshj's ssh-wire decoder: for a cert type [Buffer.readPublicKey]
 * returns a [Certificate], the source of the public metadata. A plain (non-cert) public key decodes
 * to a bare `PublicKey`; such input is rejected (`null`), as is any unreadable line.
 */
class SshjCertificateInspector : SshCertificateInspector {

    override fun inspect(certificate: String): SshCertificateInfo? = runCatching {
        // Android's stripped-down system BouncyCastle breaks certificate key parsing; register the
        // full provider before decoding (idempotent, no-op on desktop), same as the generator/transport.
        ensureCryptoProvider()
        // authorized_keys format: "<type> <base64> [comment]"; take the second field as the cert body.
        val blob = certificate.trim().split(Regex("\\s+")).getOrNull(1)?.let { Base64.getDecoder().decode(it) }
            ?: return null
        val cert = Buffer.PlainBuffer(blob).readPublicKey() as? Certificate<*> ?: return null
        SshCertificateInfo(
            keyTypeLabel = displayLabel(cert.key),
            keyId = cert.id,
            principals = cert.validPrincipals.toList(),
            serial = cert.serial.toString(),
            validFrom = formatDate(cert.validAfter),
            validUntil = if (isForever(cert.validBefore)) SshCertificateInfo.FOREVER else formatDate(cert.validBefore),
            expired = !isForever(cert.validBefore) && cert.validBefore.before(Date()),
            // cert.signatureKey is the ssh-wire encoding of the CA public key (matching OpenSSH's
            // fingerprint encoding), so its SHA256 matches `ssh-keygen -l` on the CA key.
            caFingerprintSha256 = fingerprint(cert.signatureKey),
        )
    }.getOrNull()

    /** Label for the carrier key: RSA shows actual bit length, otherwise the wire name (as in the generator). */
    private fun displayLabel(key: PublicKey): String = when {
        key is RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
        KeyType.fromKey(key) == KeyType.ED25519 -> "ED25519"
        else -> KeyType.fromKey(key).toString().removePrefix("ssh-").uppercase()
    }

    private fun formatDate(date: Date): String = DATE_FORMAT.format(date.toInstant())

    // An OpenSSH "forever" certificate has validBefore == uint64 max, which overflows a signed long
    // to a large negative value (time <= 0) once multiplied by 1000 for Date; also catch an
    // out-of-range year as a fallback. A legitimate cert expiring before 1970 is not realistic.
    private fun isForever(validBefore: Date): Boolean = validBefore.time <= 0L || yearOf(validBefore) > 9999

    private fun yearOf(date: Date): Int = date.toInstant().atZone(ZoneOffset.UTC).year

    private fun fingerprint(publicKeyBlob: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        // DateTimeFormatter is immutable and thread-safe (unlike SimpleDateFormat); the inspector
        // is called from multiple places (vault list + import dialog validation).
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}
