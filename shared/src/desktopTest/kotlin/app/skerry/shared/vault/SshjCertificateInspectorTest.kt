package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SshjCertificateInspectorTest {

    private val inspector = SshjCertificateInspector()

    @Test
    fun `reads metadata from ed25519 certificate`() {
        val info = inspector.inspect(CertificateFixtures.ED25519_CERT)
        assertNotNull(info)
        assertEquals("ED25519", info.keyTypeLabel)
        assertEquals("skerry-test@ed25519", info.keyId)
        assertEquals(listOf("alice", "deploy"), info.principals)
        assertEquals("42", info.serial)
        assertEquals("2024-01-01", info.validFrom)
        assertEquals("2034-01-01", info.validUntil)
        assertFalse(info.expired)
        assertEquals(CertificateFixtures.CA_FINGERPRINT, info.caFingerprintSha256)
    }

    @Test
    fun `reads metadata from rsa certificate including real bit length`() {
        val info = inspector.inspect(CertificateFixtures.RSA_CERT)
        assertNotNull(info)
        assertEquals("RSA-2048", info.keyTypeLabel)
        assertEquals("skerry-test@rsa", info.keyId)
        assertEquals(listOf("bob"), info.principals)
        assertEquals("7", info.serial)
        assertEquals(CertificateFixtures.CA_FINGERPRINT, info.caFingerprintSha256)
    }

    @Test
    fun `reports an unbounded certificate as valid forever`() {
        val info = inspector.inspect(CertificateFixtures.FOREVER_CERT)
        assertNotNull(info)
        assertEquals(SshCertificateInfo.FOREVER, info.validUntil)
        assertFalse(info.expired)
        assertEquals(listOf("svc"), info.principals)
    }

    @Test
    fun `returns null for garbage input`() {
        assertNull(inspector.inspect("not a certificate at all"))
    }

    @Test
    fun `returns null for a plain (non-certificate) public key`() {
        // Обычный публичный ключ CA — валидный ключ, но НЕ сертификат: инспектор не должен его принять.
        val caPub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMaQgzqhP+ZzyG6dpQhjVq8kYqyd8kHrJugsGwQ2JDSQ skerry-ca"
        assertNull(inspector.inspect(caPub))
    }
}
