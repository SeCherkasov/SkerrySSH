package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val CODE = "424242"
private const val PROMPT = "Verification code:"
private const val NAME = "Two-factor authentication"
private const val INSTRUCTION = "Enter the code from your authenticator app"

private val trustAll = HostKeyVerifier { _, _, _, _ -> true }

/**
 * Integration tests for keyboard-interactive authentication against an embedded Apache MINA SSHD:
 * the code-only case, the `publickey,keyboard-interactive` multi-factor chain (the server reports
 * partial success after the key and demands a second method), and the refusal paths.
 */
class SshjKeyboardInteractiveTest {

    private var server: SshServer? = null
    private val authorizedKey: KeyPair = generateRsaKeyPair()

    @AfterTest
    fun stopServer() {
        server?.stop(true)
    }

    /**
     * Starts a server whose only (or, with [multiFactor], whose second) auth method is
     * keyboard-interactive. [accept] decides whether the responses pass; [onChallenge] observes each
     * generated challenge.
     */
    private fun startServer(
        multiFactor: Boolean = false,
        accept: (List<String>) -> Boolean = { it == listOf(CODE) },
    ): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
            override fun generateChallenge(
                session: ServerSession?,
                username: String?,
                lang: String?,
                subMethods: String?,
            ): InteractiveChallenge = InteractiveChallenge().apply {
                interactionName = NAME
                interactionInstruction = INSTRUCTION
                addPrompt(PROMPT, false)
            }

            override fun authenticate(
                session: ServerSession?,
                username: String?,
                responses: MutableList<String>?,
            ): Boolean = username == USER && accept(responses.orEmpty())
        }
        if (multiFactor) {
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                user == USER && key.encoded.contentEquals(authorizedKey.public.encoded)
            }
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE)
            // Comma = "and": the key alone leaves the session partially authenticated, exactly the
            // OpenSSH `AuthenticationMethods publickey,keyboard-interactive` arrangement.
            CoreModuleProperties.AUTH_METHODS.set(this, "publickey,keyboard-interactive")
        } else {
            userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
        }
        start()
        server = this
    }

    private fun target(server: SshServer) = SshTarget(host = "127.0.0.1", port = server.port, username = USER)

    @Test
    fun `authenticates with the code supplied by the responder`() = runTest {
        val server = startServer()
        val transport = SshjTransport(trustAll) { listOf(CODE) }

        val connection = transport.connect(target(server), SshAuth.Password("unused"))

        assertTrue(connection.isConnected)
        connection.disconnect()
    }

    @Test
    fun `completes the publickey then keyboard-interactive chain`() = runTest {
        val server = startServer(multiFactor = true)
        val asked = AtomicInteger()
        val transport = SshjTransport(trustAll) { asked.incrementAndGet(); listOf(CODE) }

        val connection = transport.connect(target(server), SshAuth.PublicKey(pkcs8Pem(authorizedKey)))

        assertTrue(connection.isConnected, "key plus code should authenticate")
        assertEquals(1, asked.get(), "the code should be asked for exactly once")
        connection.disconnect()
    }

    @Test
    fun `hands the server's prompts to the responder`() = runTest {
        val server = startServer()
        var seen: KeyboardInteractiveChallenge? = null
        val transport = SshjTransport(trustAll) { challenge -> seen = challenge; listOf(CODE) }

        transport.connect(target(server), SshAuth.Password("unused")).disconnect()

        val challenge = assertNotNull(seen, "the responder should be called")
        assertEquals(NAME, challenge.name)
        assertEquals(INSTRUCTION, challenge.instruction)
        assertEquals(listOf(PROMPT), challenge.prompts.map { it.text })
        assertTrue(!challenge.prompts.single().echo, "a code prompt must not be echoed")
        assertTrue(!challenge.hop, "the target itself is not a jump host")
    }

    @Test
    fun `cancelling the prompt fails authentication`() = runTest {
        val server = startServer()
        val transport = SshjTransport(trustAll) { null }

        assertFailsWith<SshAuthenticationException> {
            transport.connect(target(server), SshAuth.Password("unused"))
        }
    }

    @Test
    fun `without a responder keyboard-interactive is not attempted`() = runTest {
        val server = startServer()

        assertFailsWith<SshAuthenticationException> {
            SshjTransport(trustAll).connect(target(server), SshAuth.Password("unused"))
        }
    }

    @Test
    fun `stops re-asking after repeated rejections`() = runTest {
        val server = startServer(accept = { false })
        val asked = AtomicInteger()
        val transport = SshjTransport(trustAll) { asked.incrementAndGet(); listOf("wrong") }

        assertFailsWith<SshAuthenticationException> {
            transport.connect(target(server), SshAuth.Password("unused"))
        }
        assertTrue(
            asked.get() in 1..KEYBOARD_INTERACTIVE_MAX_ROUNDS,
            "a server that keeps rejecting must not loop forever, asked ${asked.get()} times",
        )
    }
}

private fun generateRsaKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

private fun pkcs8Pem(keyPair: KeyPair): String {
    val body = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(keyPair.private.encoded)
    return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
}
