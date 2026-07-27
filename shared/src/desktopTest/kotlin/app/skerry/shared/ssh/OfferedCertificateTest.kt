package app.skerry.shared.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HOST = "web.example.com"

/**
 * Mapping of an sshj-parsed certificate onto [OfferedHostCertificate] — the fields a trust decision
 * is made from. Unit-level on purpose: an embedded MINA server won't present a *user* certificate
 * as a host key (it matches the certificate to the host key role itself), yet a hostile server can,
 * and that is exactly the case the mapping has to carry through.
 */
class OfferedCertificateTest {

    private val hostKey: KeyPair = ecKeyPair()
    private val caKey: KeyPair = ecKeyPair()

    private fun certificate(
        type: Long = 2L,
        principals: List<String> = listOf("web.example.com"),
        validAfter: Date = Date(1_700_000_000_000L),
        validBefore: Date = Date(1_700_003_600_000L),
        critOptions: Map<String, String> = emptyMap(),
        signatureKey: ByteArray = Buffer.PlainBuffer().putPublicKey(caKey.public).compactData,
    ): Certificate<PublicKey> = Certificate.getBuilder<PublicKey>()
        .publicKey(hostKey.public)
        .nonce(ByteArray(16))
        .serial(BigInteger.valueOf(42))
        .type(type)
        .id("web-01")
        .validPrincipals(principals)
        .validAfter(validAfter)
        .validBefore(validBefore)
        .critOptions(critOptions)
        .extensions(emptyMap())
        .signatureKey(signatureKey)
        .signature(ByteArray(8))
        .build()

    @Test
    fun `reads the key inside the certificate, not the certificate blob`() {
        val offered = offeredCertificate(certificate(), HOST)
        assertEquals(opensshFingerprint(hostKey.public), offered.fingerprint)
        assertEquals("ecdsa-sha2-nistp256", offered.keyType)
    }

    @Test
    fun `reads the signing CA`() {
        val offered = offeredCertificate(certificate(), HOST)
        assertEquals(opensshFingerprint(caKey.public), offered.caFingerprint)
        assertEquals("ecdsa-sha2-nistp256", offered.caKeyType)
    }

    @Test
    fun `marks a host certificate as one`() {
        assertTrue(offeredCertificate(certificate(type = 2L), HOST).hostCertificate)
    }

    @Test
    fun `marks a user certificate as not a host certificate`() {
        // SSH_CERT_TYPE_USER: sshj's own KEX check passes it, so this flag is what stops it.
        assertEquals(false, offeredCertificate(certificate(type = 1L), HOST).hostCertificate)
    }

    @Test
    fun `carries principals, validity and critical option names`() {
        val offered = offeredCertificate(certificate(critOptions = mapOf("force-command" to "/bin/false")), HOST)
        assertEquals(listOf("web.example.com"), offered.principals)
        assertEquals(1_700_000_000L, offered.validAfterEpochSeconds)
        assertEquals(1_700_003_600L, offered.validBeforeEpochSeconds)
        assertEquals(listOf("force-command"), offered.criticalOptions)
        assertEquals("42", offered.serial)
        assertEquals("web-01", offered.keyId)
    }

    @Test
    fun `an unparsable CA key leaves the fingerprint blank rather than guessing`() {
        // A blank fingerprint matches no stored authority, so such an offer falls through to TOFU.
        val offered = offeredCertificate(certificate(signatureKey = byteArrayOf(1, 2, 3)), HOST)
        assertEquals("", offered.caFingerprint)
        assertEquals("", offered.caKeyType)
    }

    @Test
    fun `a certificate whose signature does not check out is not reported as verified`() {
        // This fixture's signature bytes are filler, so the check must fail — a genuinely signed
        // certificate is covered in HostCertificateSignatureTest.
        assertFalse(offeredCertificate(certificate(), HOST).caSignatureVerified)
    }
}

private fun ecKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
