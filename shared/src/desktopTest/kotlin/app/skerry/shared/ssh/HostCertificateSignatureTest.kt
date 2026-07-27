package app.skerry.shared.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import org.apache.sshd.certificate.OpenSshCertificateBuilder
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CA signature over an offered host certificate is verified by *this* client, not assumed to
 * have been verified by sshj during key exchange.
 *
 * Why it can't be assumed: `AbstractDHG` runs the check, but `AbstractDHGex` — the
 * `diffie-hellman-group-exchange-sha256/sha1` family, which sshj's `DefaultConfig` offers third and
 * seventh — only verifies the exchange-hash signature against the key *inside* the certificate and
 * never looks at the CA signature. A server that advertises only group-exchange KEX therefore
 * forces that path, and a certificate carrying a trusted CA's public key (public by design) with
 * arbitrary signature bytes would otherwise be accepted as if that CA had issued it.
 */
class HostCertificateSignatureTest {

    private val caKeyPair: KeyPair = ecKeyPair()
    private val otherCaKeyPair: KeyPair = ecKeyPair()
    private val hostKeyPair: KeyPair = ecKeyPair()

    private fun signedCertificate(principals: List<String> = listOf("web.example.com")): OpenSshCertificate =
        OpenSshCertificateBuilder.hostCertificate()
            .publicKey(hostKeyPair.public)
            .id("web-01")
            .serial(7)
            .principals(principals)
            .validAfter(Instant.now().minusSeconds(600))
            .validBefore(Instant.now().plusSeconds(600))
            .sign(caKeyPair, KeyUtils.getKeyType(caKeyPair))

    /** MINA's certificate on the wire, as sshj would receive and parse it. */
    private fun wireBytes(certificate: OpenSshCertificate): ByteArray =
        Base64.getDecoder().decode(PublicKeyEntry.toString(certificate).split(" ")[1])

    private fun parse(bytes: ByteArray): Certificate<*> =
        Buffer.PlainBuffer(bytes).readPublicKey() as Certificate<*>

    @Test
    fun `a genuinely signed certificate is reported as verified`() {
        val offered = offeredCertificate(parse(wireBytes(signedCertificate())), "web.example.com")
        assertTrue(offered.caSignatureVerified)
        assertEquals(opensshFingerprint(caKeyPair.public), offered.caFingerprint)
    }

    @Test
    fun `a tampered signature is reported as unverified`() {
        val bytes = wireBytes(signedCertificate())
        // Flip a bit in the last byte — the signature is the final field of the blob.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        assertFalse(offeredCertificate(parse(bytes), "web.example.com").caSignatureVerified)
    }

    @Test
    fun `a certificate naming a trusted CA it was not signed by is reported as unverified`() {
        // The attack the group-exchange gap would enable: take a certificate signed by some key,
        // then swap the embedded "signature key" field for the public key of a CA the user trusts.
        // Both are P-256, so the blob keeps its length and still parses.
        val bytes = wireBytes(signedCertificate())
        val signedBy = Buffer.PlainBuffer().putPublicKey(caKeyPair.public).compactData
        val trustedCa = Buffer.PlainBuffer().putPublicKey(otherCaKeyPair.public).compactData
        val at = indexOf(bytes, signedBy)
        assertTrue(at >= 0, "signature key field not found in the certificate blob")
        signedBy.indices.forEach { bytes[at + it] = trustedCa[it] }

        val offered = offeredCertificate(parse(bytes), "web.example.com")
        assertEquals(opensshFingerprint(otherCaKeyPair.public), offered.caFingerprint)
        assertFalse(offered.caSignatureVerified, "a CA whose key did not sign this certificate must not vouch for it")
    }

    @Test
    fun `a certificate issued for another host is reported as unverified`() {
        val offered = offeredCertificate(parse(wireBytes(signedCertificate(listOf("db.example.com")))), "web.example.com")
        assertFalse(offered.caSignatureVerified)
    }

    @Test
    fun `an expired certificate is reported as unverified`() {
        val expired = OpenSshCertificateBuilder.hostCertificate()
            .publicKey(hostKeyPair.public)
            .id("web-01")
            .serial(8)
            .principals(listOf("web.example.com"))
            .validAfter(Instant.now().minusSeconds(7200))
            .validBefore(Instant.now().minusSeconds(60))
            .sign(caKeyPair, KeyUtils.getKeyType(caKeyPair))

        assertFalse(offeredCertificate(parse(wireBytes(expired)), "web.example.com").caSignatureVerified)
    }
}

private fun ecKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (start in 0..haystack.size - needle.size) {
        for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
        return start
    }
    return -1
}
