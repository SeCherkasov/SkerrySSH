package app.skerry.shared.ssh

import net.schmizz.sshj.common.Buffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshjCaKeyParserTest {

    private val parser = SshjCaKeyParser()
    private val caKey: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val blob: String =
        Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(caKey.public).compactData)

    @Test
    fun `parses a bare public key`() {
        val parsed = parser.parse("ecdsa-sha2-nistp256 $blob ca@example.com")
        assertEquals("ecdsa-sha2-nistp256", parsed?.keyType)
        assertEquals(blob, parsed?.publicKey)
        assertEquals(opensshFingerprint(caKey.public), parsed?.fingerprint)
        assertEquals("ca@example.com", parsed?.comment)
        assertNull(parsed?.hostPattern)
    }

    @Test
    fun `parses a whole known_hosts cert-authority line and keeps its host pattern`() {
        val parsed = parser.parse("@cert-authority *.prod.example.com ecdsa-sha2-nistp256 $blob prod CA")
        assertEquals("*.prod.example.com", parsed?.hostPattern)
        assertEquals("ecdsa-sha2-nistp256", parsed?.keyType)
        assertEquals(opensshFingerprint(caKey.public), parsed?.fingerprint)
    }

    @Test
    fun `ignores surrounding whitespace and blank lines`() {
        val parsed = parser.parse("\n   ecdsa-sha2-nistp256   $blob   \n")
        assertEquals(opensshFingerprint(caKey.public), parsed?.fingerprint)
    }

    @Test
    fun `refuses a certificate pasted in place of a CA key`() {
        // `*-cert.pub` is the thing the CA *issues*; trusting it would pin one machine's
        // certificate as an authority.
        assertNull(parser.parse("ecdsa-sha2-nistp256-cert-v01@openssh.com $blob"))
    }

    @Test
    fun `refuses a revoked-key line`() {
        // @revoked means the opposite of trust and we don't implement revocation lists yet.
        assertNull(parser.parse("@revoked *.example.com ecdsa-sha2-nistp256 $blob"))
    }

    @Test
    fun `refuses garbage`() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("ecdsa-sha2-nistp256"))
        assertNull(parser.parse("ecdsa-sha2-nistp256 not-base64!!"))
        assertNull(parser.parse("not-a-key-type $blob"))
        // Valid base64 that isn't a key blob.
        assertNull(parser.parse("ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"))
    }

    @Test
    fun `refuses a key whose blob disagrees with the declared type`() {
        assertNull(parser.parse("ssh-ed25519 $blob"))
    }
}
