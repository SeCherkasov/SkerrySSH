package app.skerry.shared.ssh

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The PEM loader against the shapes a pasted key really has. sshj covers OpenSSH and PKCS#1 on its
 * own; PKCS#8 (`BEGIN PRIVATE KEY`, what `openssl`, cloud consoles and Java tooling hand out) it
 * cannot parse at all, which is what the fallback here is for.
 */
class SshPemKeysTest {

    private fun pem(header: String, der: ByteArray) =
        "-----BEGIN $header-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $header-----\n"

    private fun pkcs8(algorithm: String, curve: String? = null) =
        KeyPairGenerator.getInstance(algorithm)
            .apply { curve?.let { initialize(ECGenParameterSpec(it)) } }
            .generateKeyPair()

    @Test
    fun `loads a pkcs8 ecdsa key and recovers its public half`() {
        val original = pkcs8("EC", "secp256r1")

        val loaded = loadSshKeyPair(pem("PRIVATE KEY", original.private.encoded), passphrase = null)

        assertEquals(original.public, loaded.public)
        assertEquals("ecdsa-sha2-nistp256", net.schmizz.sshj.common.KeyType.fromKey(loaded.public).toString())
    }

    @Test
    fun `loads a pkcs8 rsa key`() {
        val original = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val loaded = loadSshKeyPair(pem("PRIVATE KEY", original.private.encoded), passphrase = null)

        assertEquals(original.public, loaded.public)
    }

    @Test
    fun `loads a pkcs8 ed25519 key`() {
        val original = pkcs8("Ed25519")

        val loaded = loadSshKeyPair(pem("PRIVATE KEY", original.private.encoded), passphrase = null)

        assertEquals("ssh-ed25519", net.schmizz.sshj.common.KeyType.fromKey(loaded.public).toString())
    }

    @Test
    fun `loads an encrypted pkcs8 key with its passphrase`() {
        val original = pkcs8("EC", "secp256r1")

        val loaded = loadSshKeyPair(pem("ENCRYPTED PRIVATE KEY", encrypt(original.private.encoded, "hunter2")), "hunter2")

        assertEquals(original.public, loaded.public)
    }

    @Test
    fun `a wrong passphrase fails instead of returning a broken key`() {
        val original = pkcs8("EC", "secp256r1")
        val encrypted = pem("ENCRYPTED PRIVATE KEY", encrypt(original.private.encoded, "hunter2"))

        assertFailsWith<java.io.IOException> { loadSshKeyPair(encrypted, "wrong") }
    }

    @Test
    fun `an openssh key still goes through sshj`() {
        val generated = app.skerry.shared.vault.BouncyCastleSshKeyGenerator()
            .generate(app.skerry.shared.vault.SshKeyType.ED25519, comment = "")

        val loaded = loadSshKeyPair(generated.privateKeyPem, passphrase = null)

        assertTrue(net.schmizz.sshj.common.KeyType.fromKey(loaded.public) == net.schmizz.sshj.common.KeyType.ED25519)
    }

    @Test
    fun `an unreadable key is named as such, not reported as a dropped connection`() {
        // What the user sees when a key cannot be opened must point at the key: "the connection
        // dropped" sends them looking at the network for a wrong-passphrase typo.
        val failure = assertFailsWith<SshAuthenticationException> {
            loadAuthKey("-----BEGIN PRIVATE KEY-----\nnope\n", passphrase = null)
        }

        assertTrue(failure.message!!.contains("key", ignoreCase = true), "message does not mention the key: ${failure.message}")
    }

    @Test
    fun `a readable key comes back as a usable pair`() {
        val original = pkcs8("EC", "secp256r1")

        assertEquals(original.public, loadAuthKey(pem("PRIVATE KEY", original.private.encoded), null).public)
    }

    @Test
    fun `garbage is reported, not returned`() {
        assertFailsWith<java.io.IOException> { loadSshKeyPair("-----BEGIN PRIVATE KEY-----\nnope\n", null) }
    }

    /**
     * Wrap a key as `ENCRYPTED PRIVATE KEY`. PBES1 here only because it is what the JDK can build an
     * AlgorithmId for in a test; decryption reads the algorithm out of the blob, so a real PBES2 key
     * from `openssl` goes through the same path.
     */
    private fun encrypt(pkcs8Der: ByteArray, passphrase: String): ByteArray {
        val algorithm = "PBEWithMD5AndDES"
        val parameters = java.security.AlgorithmParameters.getInstance(algorithm)
            .apply { init(PBEParameterSpec(ByteArray(8) { it.toByte() }, 10_000)) }
        val key = SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec(passphrase.toCharArray()))
        val cipher = Cipher.getInstance(algorithm).apply { init(Cipher.ENCRYPT_MODE, key, parameters) }
        return EncryptedPrivateKeyInfo(parameters, cipher.doFinal(pkcs8Der)).encoded
    }
}
