package app.skerry.shared.ssh

import app.skerry.shared.vault.SecretFileResult
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshCertificateInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----\n"
private const val CERT = "ssh-ed25519-cert-v01@openssh.com AAAAcert user@laptop"

/** Inspector stub: certificates map to fixed metadata; anything else fails to parse (null). */
private fun inspectorOf(vararg certs: Pair<String, SshCertificateInfo>) =
    SshCertificateInspector { certificate -> certs.toMap()[certificate] }

private fun certInfo(validUntil: String = "2027-01-01", expired: Boolean = false) = SshCertificateInfo(
    keyTypeLabel = "ED25519",
    keyId = "dev@corp",
    principals = listOf("dev"),
    serial = "7",
    validFrom = "2026-07-27",
    validUntil = validUntil,
    expired = expired,
    caFingerprintSha256 = "SHA256:ca",
)

class KeyFileResolverTest {

    @Test
    fun `explicit certificate ref resolves to certificate auth`() {
        val resolver = KeyFileResolver(
            files = { ref ->
                when (ref) {
                    "/keys/id" -> SecretFileResult.Ok(PEM)
                    "/keys/id-cert.pub" -> SecretFileResult.Ok(CERT)
                    else -> SecretFileResult.NotFound
                }
            },
            inspector = inspectorOf(CERT to certInfo()),
        )

        val auth = resolver.resolve(SshAuth.KeyFile("/keys/id", "/keys/id-cert.pub", passphrase = "pp"))

        assertEquals(SshAuth.Certificate(PEM, CERT, "pp"), auth)
    }

    @Test
    fun `blank certificate ref picks up the OpenSSH sibling next to the key`() {
        val resolver = KeyFileResolver(
            files = { ref ->
                when (ref) {
                    "/keys/id" -> SecretFileResult.Ok(PEM)
                    "/keys/id-cert.pub" -> SecretFileResult.Ok(CERT)
                    else -> SecretFileResult.NotFound
                }
            },
            inspector = inspectorOf(CERT to certInfo()),
        )

        val auth = resolver.resolve(SshAuth.KeyFile("/keys/id", certificateRef = null))

        assertEquals(SshAuth.Certificate(PEM, CERT, null), auth)
    }

    @Test
    fun `a sibling certificate that exists but cannot be read fails instead of silently dropping to the key`() {
        // "No sibling" and "the sibling is unreadable" are different situations: the first is the
        // normal plain-key case, the second means the cert-auth the user expects is quietly not
        // happening, and the server's refusal would point at nothing.
        val resolver = KeyFileResolver(
            files = { ref ->
                when (ref) {
                    "/keys/id" -> SecretFileResult.Ok(PEM)
                    else -> SecretFileResult.Denied
                }
            },
        )

        val e = assertFailsWith<SshAuthenticationException> {
            resolver.resolve(SshAuth.KeyFile("/keys/id", certificateRef = null))
        }

        assertTrue("/keys/id-cert.pub" in e.message.orEmpty(), "message should name the sibling: ${e.message}")
    }

    @Test
    fun `no certificate anywhere falls back to plain public-key auth`() {
        val resolver = KeyFileResolver(
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok(PEM) else SecretFileResult.NotFound },
        )

        val auth = resolver.resolve(SshAuth.KeyFile("/keys/id", certificateRef = null, passphrase = "pp"))

        assertEquals(SshAuth.PublicKey(PEM, "pp"), auth)
    }

    @Test
    fun `missing private key fails with the ref in the message`() {
        val resolver = KeyFileResolver(files = { SecretFileResult.NotFound })

        val e = assertFailsWith<SshAuthenticationException> {
            resolver.resolve(SshAuth.KeyFile("/keys/gone", null))
        }

        assertTrue("/keys/gone" in e.message.orEmpty(), "message should name the ref: ${e.message}")
    }

    @Test
    fun `explicitly named certificate that is missing fails instead of falling back to the key`() {
        // Silently degrading to publickey would answer a cert-only server with the wrong method and
        // leave the user reading "authentication failed" with no hint that the cert file went stale.
        val resolver = KeyFileResolver(
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok(PEM) else SecretFileResult.NotFound },
        )

        val e = assertFailsWith<SshAuthenticationException> {
            resolver.resolve(SshAuth.KeyFile("/keys/id", "/keys/id-cert.pub"))
        }

        assertTrue("/keys/id-cert.pub" in e.message.orEmpty(), "message should name the ref: ${e.message}")
    }

    @Test
    fun `expired certificate fails before connecting and says when it expired`() {
        val resolver = KeyFileResolver(
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok(PEM) else SecretFileResult.Ok(CERT) },
            inspector = inspectorOf(CERT to certInfo(validUntil = "2026-07-01", expired = true)),
        )

        val e = assertFailsWith<SshAuthenticationException> {
            resolver.resolve(SshAuth.KeyFile("/keys/id", "/keys/id-cert.pub"))
        }

        assertTrue("2026-07-01" in e.message.orEmpty(), "message should carry the expiry date: ${e.message}")
    }

    @Test
    fun `unparseable certificate is passed through for the server to judge`() {
        // An inspector that doesn't know a newer key type must not block a certificate the server
        // would have accepted; only a parsed-and-expired certificate is refused locally.
        val resolver = KeyFileResolver(
            files = { ref -> if (ref == "/keys/id") SecretFileResult.Ok(PEM) else SecretFileResult.Ok("garbage") },
            inspector = inspectorOf(),
        )

        val auth = resolver.resolve(SshAuth.KeyFile("/keys/id", "/keys/id-cert.pub"))

        assertEquals(SshAuth.Certificate(PEM, "garbage", null), auth)
    }

    @Test
    fun `denied and unsupported refs are reported apart from a missing file`() {
        val denied = KeyFileResolver(files = { SecretFileResult.Denied })
        val foreign = KeyFileResolver(files = { SecretFileResult.Unsupported })

        val deniedMessage = assertFailsWith<SshAuthenticationException> {
            denied.resolve(SshAuth.KeyFile("/keys/id", null))
        }.message.orEmpty()
        val foreignMessage = assertFailsWith<SshAuthenticationException> {
            foreign.resolve(SshAuth.KeyFile("content://doc/1", null))
        }.message.orEmpty()

        assertTrue("denied" in deniedMessage.lowercase(), deniedMessage)
        assertTrue("device" in foreignMessage.lowercase(), foreignMessage)
    }

    @Test
    fun `sibling probe is skipped for refs that are not paths`() {
        // "content://doc/42-cert.pub" is not a sibling of anything — appending a suffix to an opaque
        // Uri would query the provider for a document that never exists.
        val probed = mutableListOf<String>()
        val resolver = KeyFileResolver(
            files = { ref -> probed += ref; SecretFileResult.Ok(PEM) },
        )

        resolver.resolve(SshAuth.KeyFile("content://doc/42", certificateRef = null))

        assertEquals(listOf("content://doc/42"), probed)
    }

    @Test
    fun `other auth methods pass through untouched`() {
        val resolver = KeyFileResolver(files = { SecretFileResult.NotFound })

        assertEquals(SshAuth.Interactive, resolver.resolve(SshAuth.Interactive))
        assertEquals(SshAuth.Password("p"), resolver.resolve(SshAuth.Password("p")))
    }

    @Test
    fun `resolution never leaks key material through toString`() {
        val auth = KeyFileResolver(files = { SecretFileResult.Ok(PEM) })
            .resolve(SshAuth.KeyFile("/keys/id", "/keys/id-cert.pub"))

        assertNull(PEM.takeIf { it in auth.toString() }, "auth.toString() leaked the private key")
    }
}
