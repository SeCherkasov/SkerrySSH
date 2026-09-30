package app.skerry.shared.vault

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `an encrypted key reads with its passphrase and not without`() {
        assertEquals("ED25519", gen.inspect(ENCRYPTED_ED25519, ENCRYPTED_ED25519_PASSPHRASE)?.keyTypeLabel)
        assertNull(gen.inspect(ENCRYPTED_ED25519, null))
        assertNull(gen.inspect(ENCRYPTED_ED25519, "wrong"))
    }

    /**
     * A pasted key sets its own bcrypt cost and sshj pays it in full, uninterruptibly — the vault's
     * import dialog reads keys as they are typed. Past the cap it must be refused, not computed:
     * Int.MAX_VALUE rounds would run for days, so a thread still busy after seconds means it was run.
     */
    /** Each form sshj reads: plain, with the base64 padding dropped, with text after the header. */
    @Test
    fun `a key demanding more bcrypt rounds than the cap is refused without running them`() {
        val hostile = withRounds(ENCRYPTED_ED25519, Int.MAX_VALUE.toLong())
        val forms = listOf(
            hostile,
            hostile.replace("=", ""),
            hostile.replace("-----BEGIN OPENSSH PRIVATE KEY-----", "-----BEGIN OPENSSH PRIVATE KEY----- PRIVATE KEY-----"),
        )
        for (form in forms) {
            var result: SshPublicKeyInfo? = SshPublicKeyInfo("", "", "unset")
            val reader = Thread { result = gen.inspect(form, ENCRYPTED_ED25519_PASSPHRASE) }.apply { isDaemon = true; start() }
            reader.join(10_000)

            assertFalse(reader.isAlive, "the key's bcrypt rounds were run:\n$form")
            assertNull(result)
        }
    }
}
