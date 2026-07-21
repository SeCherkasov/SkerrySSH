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
        // ECDSA on the wire is `mpint r || mpint s`, not the DER sequence JCA expects, so it is
        // checked with the same decoder an sshj server uses rather than being re-encoded here.
        if (algorithm.startsWith("ecdsa-")) {
            val ecdsa = net.schmizz.sshj.signature.SignatureECDSA.Factory256().create()
            ecdsa.initVerify(key)
            ecdsa.update(data)
            // extractSig wants the whole `algorithm || signature` blob, not the unwrapped half.
            assertTrue(ecdsa.verify(blob), "signature does not verify under the offered key ($algorithm)")
            return algorithm
        }
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
    fun `signs a challenge with an ecdsa key`() = runTest {
        // Skerry generates ED25519/RSA, but a user can paste an ECDSA key from elsewhere, and the
        // agent must sign with it: the signature type comes from the PUBLIC half, because sshj
        // classifies an EC private key as UNKNOWN and would silently refuse.
        val (pem, publicLine) = ecdsaKey()
        val keys = SshjAgentKeys({ listOf(material(pem)) })
        val blob = keys.identities().single().keyBlob

        assertContentEquals(publicBlob(publicLine), blob)
        val signature = assertNotNull(keys.sign(blob, challenge, flags = 0))
        assertEquals("ecdsa-sha2-nistp256", verify(publicKeyOf(blob), challenge, signature.blob))
    }

    @Test
    fun `signs with a pkcs8 key, the shape sshj cannot read on its own`() = runTest {
        // What `openssl genpkey` and most cloud consoles hand out. Before the shared loader such a
        // key was simply invisible to the agent.
        val pair = java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val keys = SshjAgentKeys({ listOf(material(pem)) })
        val blob = keys.identities().single().keyBlob

        val signature = assertNotNull(keys.sign(blob, challenge, flags = 0))
        assertEquals("ecdsa-sha2-nistp256", verify(publicKeyOf(blob), challenge, signature.blob))
    }

    /**
     * A real P-256 key in the shape `ssh-keygen -t ecdsa` writes today: `openssh-key-v1` in an
     * `OPENSSH PRIVATE KEY` PEM. (BouncyCastle's `encodePrivateKey` emits that container for EC —
     * not the SEC1 DER an `EC PRIVATE KEY` header would promise, which is why the header matters.)
     * Returned with its `authorized_keys` line, as ground truth for what the agent offers.
     */
    private fun ecdsaKey(): Pair<String, String> {
        val generator = org.bouncycastle.crypto.generators.ECKeyPairGenerator().apply {
            val curve = org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("P-256")
            val domain = org.bouncycastle.crypto.params.ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
            init(org.bouncycastle.crypto.params.ECKeyGenerationParameters(domain, java.security.SecureRandom()))
        }
        val pair = generator.generateKeyPair()
        val der = org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
        val blob = org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        return pem to "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(blob)}"
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
    fun `a host restricted to one key sees only that key`() = runTest {
        // Forwarding hands the far side a live agent. A host limited to its deploy key must not be
        // able to enumerate — let alone use — the rest of the keyring.
        val deploy = generator.generate(SshKeyType.ED25519, comment = "")
        val personal = generator.generate(SshKeyType.ED25519, comment = "")
        val keys = SshjAgentKeys({
            listOf(
                material(deploy.privateKeyPem, id = "deploy", comment = "deploy key"),
                material(personal.privateKeyPem, id = "personal", comment = "personal key"),
            )
        })
        val scope = SshAgentScope(setOf("deploy"))

        assertEquals(listOf("deploy key"), keys.identities(scope).map { it.comment })
        assertNotNull(keys.sign(publicBlob(deploy.info.publicKeyOpenSsh), challenge, 0, scope))
        assertNull(
            keys.sign(publicBlob(personal.info.publicKeyOpenSsh), challenge, 0, scope),
            "a key outside the host's set was used",
        )
        // The same keyring still serves everything to a caller that was not narrowed.
        assertEquals(2, keys.identities().size)
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
