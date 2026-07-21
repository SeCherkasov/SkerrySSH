package app.skerry.shared.agent

import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.SshKeyType
import java.security.PublicKey
import java.util.Base64
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.common.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyring against real generated keys: what it offers must be exactly the public key of the
 * stored secret, and what it signs must verify under that key with the algorithm the client asked
 * for.
 */
class SshjAgentKeysTest {

    private val generator = BouncyCastleSshKeyGenerator()
    private val challenge = "session id and userauth request".encodeToByteArray()

    private fun material(pem: String, id: String = "c1", comment: String = "work key") =
        SshAgentKeyMaterial(id = id, comment = comment, privateKeyPem = pem)

    /** Public key blob as written in the `authorized_keys` line — the ground truth for identities. */
    private fun publicBlob(openSshLine: String): ByteArray =
        Base64.getDecoder().decode(openSshLine.split(" ")[1])

    private fun publicKeyOf(blob: ByteArray): PublicKey = Buffer.PlainBuffer(blob).readPublicKey()

    /** Verify an agent signature blob (`string algorithm || string signature`) under [key]. */
    private fun verify(key: PublicKey, data: ByteArray, blob: ByteArray): String {
        val buffer = Buffer.PlainBuffer(blob)
        val algorithm = buffer.readString()
        val raw = buffer.readBytes()
        val jca = java.security.Signature.getInstance(
            when (algorithm) {
                "ssh-ed25519" -> "Ed25519"
                "rsa-sha2-512" -> "SHA512withRSA"
                "rsa-sha2-256" -> "SHA256withRSA"
                "ssh-rsa" -> "SHA1withRSA"
                else -> error("unexpected signature algorithm $algorithm")
            },
        )
        jca.initVerify(key)
        jca.update(data)
        assertTrue(jca.verify(raw), "signature does not verify under the offered key ($algorithm)")
        return algorithm
    }

    @Test
    fun `offers the public key of the stored secret`() = runTest {
        val generated = generator.generate(SshKeyType.ED25519, comment = "")
        val identities = SshjAgentKeys({ listOf(material(generated.privateKeyPem)) }).identities()

        assertEquals(1, identities.size)
        assertEquals("work key", identities.single().comment)
        assertContentEquals(publicBlob(generated.info.publicKeyOpenSsh), identities.single().keyBlob)
    }

    @Test
    fun `signs a challenge with an ed25519 key`() = runTest {
        val generated = generator.generate(SshKeyType.ED25519, comment = "")
        val keys = SshjAgentKeys({ listOf(material(generated.privateKeyPem)) })
        val blob = keys.identities().single().keyBlob

        val signature = assertNotNull(keys.sign(blob, challenge, flags = 0))
        assertEquals("work key", signature.keyComment)
        assertEquals("ssh-ed25519", verify(publicKeyOf(blob), challenge, signature.blob))
    }

    @Test
    fun `honours the rsa-sha2 flags of the sign request`() = runTest {
        // A modern server asks for rsa-sha2-*; answering with a SHA-1 signature would be rejected,
        // and answering SHA-2 to a client that did not ask breaks old ones. Both must follow the flag.
        val generated = generator.generate(SshKeyType.RSA_4096, comment = "")
        val keys = SshjAgentKeys({ listOf(material(generated.privateKeyPem)) })
        val blob = keys.identities().single().keyBlob
        val key = publicKeyOf(blob)

        assertEquals("ssh-rsa", verify(key, challenge, assertNotNull(keys.sign(blob, challenge, 0)).blob))
        assertEquals(
            "rsa-sha2-256",
            verify(key, challenge, assertNotNull(keys.sign(blob, challenge, SshAgentCodec.FLAG_RSA_SHA2_256)).blob),
        )
        assertEquals(
            "rsa-sha2-512",
            verify(key, challenge, assertNotNull(keys.sign(blob, challenge, SshAgentCodec.FLAG_RSA_SHA2_512)).blob),
        )
    }

    @Test
    fun `a certificate is offered together with the plain key behind it`() = runTest {
        // `ssh-add` offers both: a server that trusts the CA takes the certificate, one that only
        // has the key in authorized_keys takes the key. Offering the certificate alone would lock
        // the user out of every host that is not CA-enrolled.
        val generated = generator.generate(SshKeyType.ED25519, comment = "")
        val plain = publicBlob(generated.info.publicKeyOpenSsh)
        val certBlob = "test certificate blob".encodeToByteArray()
        val certificate = "ssh-ed25519-cert-v01@openssh.com " + Base64.getEncoder().encodeToString(certBlob)
        val keys = SshjAgentKeys({
            listOf(SshAgentKeyMaterial(id = "c1", comment = "ca key", privateKeyPem = generated.privateKeyPem, certificate = certificate))
        })

        val offered = keys.identities()
        assertEquals(listOf("ca key", "ca key"), offered.map { it.comment })
        assertContentEquals(certBlob, offered.first().keyBlob)
        assertContentEquals(plain, offered.last().keyBlob)
        // Possession is proven by the same private key whichever blob the server picked.
        assertEquals("ssh-ed25519", verify(publicKeyOf(plain), challenge, assertNotNull(keys.sign(certBlob, challenge, 0)).blob))
    }

    @Test
    fun `a damaged certificate costs only itself, not the key`() = runTest {
        val generated = generator.generate(SshKeyType.ED25519, comment = "")
        val keys = SshjAgentKeys({
            listOf(SshAgentKeyMaterial(id = "c1", comment = "ca key", privateKeyPem = generated.privateKeyPem, certificate = "not-a-certificate"))
        })

        assertContentEquals(publicBlob(generated.info.publicKeyOpenSsh), keys.identities().single().keyBlob)
    }

    @Test
    fun `refuses a key it does not hold`() = runTest {
        val mine = generator.generate(SshKeyType.ED25519, comment = "")
        val other = generator.generate(SshKeyType.ED25519, comment = "")
        val keys = SshjAgentKeys({ listOf(material(mine.privateKeyPem)) })

        assertNull(keys.sign(publicBlob(other.info.publicKeyOpenSsh), challenge, flags = 0))
    }

    @Test
    fun `a key that cannot be parsed is skipped instead of breaking the listing`() = runTest {
        val good = generator.generate(SshKeyType.ED25519, comment = "")
        val keys = SshjAgentKeys({
            listOf(
                SshAgentKeyMaterial(id = "broken", comment = "damaged", privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nnope\n"),
                material(good.privateKeyPem, id = "good", comment = "good key"),
            )
        })

        assertEquals(listOf("good key"), keys.identities().map { it.comment })
    }

    @Test
    fun `reflects the current key set on every request`() = runTest {
        // The user can add or remove keys (or lock the vault) while a forwarded channel is open.
        val generated = generator.generate(SshKeyType.ED25519, comment = "")
        var offered = listOf(material(generated.privateKeyPem))
        val keys = SshjAgentKeys({ offered })

        assertEquals(1, keys.identities().size)
        offered = emptyList()
        assertEquals(0, keys.identities().size)
    }

    @Test
    fun `clear drops the parsed keys`() = runTest {
        val generated = generator.generate(SshKeyType.ED25519, comment = "")
        val keys = SshjAgentKeys({ listOf(material(generated.privateKeyPem)) })
        val blob = keys.identities().single().keyBlob

        keys.clear()
        // The material provider is what gates access after a lock; clear() only drops what was
        // parsed, so with the provider still returning the key the agent keeps working.
        assertNotNull(keys.sign(blob, challenge, flags = 0))
    }

    @Test
    fun `an edited key is re-parsed instead of served from the cache`() = runTest {
        val first = generator.generate(SshKeyType.ED25519, comment = "")
        val second = generator.generate(SshKeyType.ED25519, comment = "")
        var pem = first.privateKeyPem
        val keys = SshjAgentKeys({ listOf(material(pem)) })

        assertContentEquals(publicBlob(first.info.publicKeyOpenSsh), keys.identities().single().keyBlob)
        pem = second.privateKeyPem
        assertContentEquals(publicBlob(second.info.publicKeyOpenSsh), keys.identities().single().keyBlob)
    }
}
