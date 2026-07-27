package app.skerry.ui.vault

import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SecretFileResult
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshCertificateInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CERT = "ssh-ed25519-cert-v01@openssh.com AAAA"

private fun info(expired: Boolean = false) = SshCertificateInfo(
    keyTypeLabel = "ED25519",
    keyId = "dev@corp",
    principals = listOf("dev"),
    serial = "1",
    validFrom = "2026-07-01",
    validUntil = "2026-08-01",
    expired = expired,
    caFingerprintSha256 = "SHA256:ca",
)

private val inspector = SshCertificateInspector { if (it == CERT) info() else null }

class KeyFileStateTest {

    @Test
    fun `reports both files present and parses the certificate`() {
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("/keys/id", "/keys/id-cert.pub"),
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok("pem") else SecretFileResult.Ok(CERT) },
            inspector = inspector,
        )

        assertTrue(state.keyReadable)
        assertEquals("/keys/id-cert.pub", state.certificateRef)
        assertTrue(state.certificateReadable)
        assertEquals("ED25519", state.certificate?.keyTypeLabel)
    }

    @Test
    fun `blank certificate ref resolves to the sibling actually on disk`() {
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("/keys/id"),
            files = { ref -> if (ref == "/keys/id-cert.pub") SecretFileResult.Ok(CERT) else SecretFileResult.Ok("pem") },
            inspector = inspector,
        )

        assertEquals("/keys/id-cert.pub", state.certificateRef)
        assertTrue(state.certificateReadable)
    }

    @Test
    fun `a missing sibling is not an error - the key authenticates on its own`() {
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("/keys/id"),
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok("pem") else SecretFileResult.NotFound },
            inspector = inspector,
        )

        assertTrue(state.keyReadable)
        assertNull(state.certificateRef)
        assertFalse(state.certificateReadable)
        assertFalse(state.certificateExpected)
    }

    @Test
    fun `an explicitly named certificate that is missing is flagged`() {
        // The user asked for this file by name, so its absence is a broken credential, not a fallback.
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("/keys/id", "/keys/id-cert.pub"),
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok("pem") else SecretFileResult.NotFound },
            inspector = inspector,
        )

        assertTrue(state.certificateExpected)
        assertFalse(state.certificateReadable)
        assertEquals("/keys/id-cert.pub", state.certificateRef)
    }

    @Test
    fun `an unreadable sibling is flagged, unlike an absent one`() {
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("/keys/id"),
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok("pem") else SecretFileResult.Denied },
            inspector = inspector,
        )

        assertTrue(state.certificateExpected)
        assertFalse(state.certificateReadable)
        assertEquals("/keys/id-cert.pub", state.certificateRef)
    }

    @Test
    fun `the private key is probed, never pulled into memory`() {
        // Browsing the vault must not load key material: the list only needs to know the file is
        // there. Only the certificate — public by definition — is actually read.
        var keyRead = false
        val files = object : SecretFileReader {
            override fun read(ref: String): SecretFileResult {
                if (ref == "/keys/id") keyRead = true
                return SecretFileResult.Ok(if (ref == "/keys/id") "pem" else CERT)
            }

            override fun probe(ref: String): Boolean = true
        }

        val state = inspectKeyFile(CredentialSecret.KeyFile("/keys/id"), files, inspector)

        assertTrue(state.keyReadable)
        assertFalse(keyRead, "the private key file was read into memory just to render the vault")
    }

    @Test
    fun `an unreadable key is reported as such`() {
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("/keys/gone"),
            files = { SecretFileResult.NotFound },
            inspector = inspector,
        )

        assertFalse(state.keyReadable)
    }

    @Test
    fun `no sibling is probed for an opaque ref`() {
        val probed = mutableListOf<String>()
        val state = inspectKeyFile(
            CredentialSecret.KeyFile("content://doc/42"),
            files = { ref -> probed += ref; SecretFileResult.Ok("pem") },
            inspector = inspector,
        )

        assertEquals(listOf("content://doc/42"), probed)
        assertNull(state.certificateRef)
    }
}
