package app.skerry.shared.securitykey

import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.ensureCryptoProvider
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Full SSH userauth wire proof with a synthetic authenticator, independent MINA signature verifier. */
class SecurityKeySshIntegrationTest {
    @Test fun serverAcceptsHardwareSignatureAndHardwareFailureNeverFallsBackToPam() = runBlocking {
        ensureCryptoProvider()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val wire = Buffer.PlainBuffer(Buffer.PlainBuffer().putPublicKey(pair.public).compactData)
        wire.readString(); wire.readString()
        val stub = encodeSecurityKey(SecurityKeyGenerationOptions(SecurityKeyAlgorithm.ECDSA), wire.readBytes(), byteArrayOf(1))
        val pam = AtomicInteger()
        var fail = false
        val backend = object : SecurityKeyService {
            override suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter) = error("unused")
            override suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion {
                if (fail) throw SecurityKeyException(SecurityKeyFailure.PinInvalid)
                val bytes = MessageDigest.getInstance("SHA-256").digest(request.application.toByteArray()) + byteArrayOf(1) +
                    ByteBuffer.allocate(4).putInt(4).array() + request.challenge
                return SecurityKeyAssertion(Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }, 1, 4)
            }
        }
        val prompt = object : SecurityKeyPrompter {
            override suspend fun requestPin(retries: Int?): CharArray? = null
            override suspend fun onTouch() = Unit
        }
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            signatureFactories = signatureFactories + BuiltinSignatures.sk_ecdsa_sha2_nistp256
            setPublickeyAuthenticator { username, key, _ -> username == "test" && key is org.apache.sshd.common.config.keys.u2f.SecurityKeyPublicKey<*> }
            start()
        }
        try {
            val transport = SshjTransport(
                HostKeyVerifier { null }, securityKeys = backend, securityKeyPrompt = prompt, securityKeysEnabled = { true },
                keyboardInteractiveResponder = { pam.incrementAndGet(); listOf("unused") },
            )
            val target = SshTarget("127.0.0.1", server.port, "test")
            val connection = transport.connect(target, SshAuth.PublicKey(stub.privateKeyPem))
            assertTrue(connection.isConnected)
            connection.disconnect()
            fail = true
            val error = assertFailsWith<SecurityKeyException> { transport.connect(target, SshAuth.PublicKey(stub.privateKeyPem)) }
            assertEquals(SecurityKeyFailure.PinInvalid, error.reason)
            assertEquals(0, pam.get())
        } finally { server.stop(true) }
    }
}
