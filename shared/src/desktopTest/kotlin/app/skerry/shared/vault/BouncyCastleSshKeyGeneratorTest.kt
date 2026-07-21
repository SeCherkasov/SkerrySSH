package app.skerry.shared.vault

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the generator against a real integration with the sshj parser (the same one used to
 * load a key on connect): the generated PEM must parse via `SSHClient.loadKeys`, and an
 * independently computed OpenSSH fingerprint from it must match the one returned by the
 * generator. This proves the PEM is valid and usable for authentication without a live server.
 * RSA-4096 is generated once (expensive, but it's what ships). Tests live in desktopTest since
 * the implementation is in jvmSharedMain (BouncyCastle/sshj).
 */
class BouncyCastleSshKeyGeneratorTest {

    private val gen = BouncyCastleSshKeyGenerator()

    /** OpenSSH fingerprint from the private PEM via sshj, an independent format check. */
    private fun fingerprintViaSshj(pem: String): String {
        val keys = SSHClient().loadKeys(pem, null, null)
        val encoded = Buffer.PlainBuffer().putPublicKey(keys.public).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    @Test
    fun `generates ed25519 parseable by sshj with matching fingerprint`() {
        val key = gen.generate(SshKeyType.ED25519, comment = "alice@skerry")

        assertTrue(key.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(key.privateKeyPem.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        assertEquals("ED25519", key.info.keyTypeLabel)
        assertTrue(key.info.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(key.info.publicKeyOpenSsh.endsWith(" alice@skerry"))
        assertTrue(key.info.fingerprintSha256.startsWith("SHA256:"))
        // Generator's fingerprint == fingerprint computed from the PEM by a third-party parser.
        assertEquals(fingerprintViaSshj(key.privateKeyPem), key.info.fingerprintSha256)
    }

    @Test
    fun `generates rsa-4096 parseable by sshj`() {
        val key = gen.generate(SshKeyType.RSA_4096)

        assertEquals("RSA-4096", key.info.keyTypeLabel)
        assertTrue(key.info.publicKeyOpenSsh.startsWith("ssh-rsa "))
        assertEquals(fingerprintViaSshj(key.privateKeyPem), key.info.fingerprintSha256)
    }

    @Test
    fun `empty comment yields public key without trailing space`() {
        val key = gen.generate(SshKeyType.ED25519, comment = "")
        assertEquals(key.info.publicKeyOpenSsh, key.info.publicKeyOpenSsh.trim())
        assertEquals(2, key.info.publicKeyOpenSsh.split(" ").size)
    }

    @Test
    fun `inspect derives same public metadata from generated private key`() {
        val key = gen.generate(SshKeyType.ED25519, comment = "x@y")
        val info = gen.inspect(key.privateKeyPem)

        assertEquals(key.info.fingerprintSha256, info?.fingerprintSha256)
        assertEquals("ED25519", info?.keyTypeLabel)
        // Public part matches by type and body (the PEM comment is not recovered).
        assertTrue(info!!.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        assertEquals(
            key.info.publicKeyOpenSsh.split(" ")[1],
            info.publicKeyOpenSsh.split(" ")[1],
        )
    }

    @Test
    fun `inspect returns null for garbage`() {
        assertNull(gen.inspect("not a pem at all"))
        assertNull(gen.inspect(""))
    }

    @Test
    fun `inspects a pkcs8 key instead of calling it damaged`() {
        // A pasted `BEGIN PRIVATE KEY` must show its type and fingerprint like any other key; sshj
        // alone cannot parse it, so this covers the shared loader's fallback.
        val pair = java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"

        val info = gen.inspect(pem, passphrase = null)

        assertEquals("ECDSA-SHA2-NISTP256", info?.keyTypeLabel)
        assertTrue(info!!.publicKeyOpenSsh.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(info.fingerprintSha256.startsWith("SHA256:"))
    }
}
