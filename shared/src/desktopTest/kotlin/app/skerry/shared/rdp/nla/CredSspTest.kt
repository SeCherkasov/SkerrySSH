package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.RdpAuthException
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpSink
import app.skerry.shared.rdp.RdpSource
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The CredSSP exchange driven end to end against a model server that knows the password. The point
 * of the protocol is the TLS binding, so the tests that matter are the ones where the binding is
 * wrong: a relayed exchange must not end with the password on the wire.
 */
class CredSspTest {

    private val crypto = JvmNtlmCrypto()

    private val credentials = NtlmCredentials(
        domain = "CORP",
        user = "elton",
        password = "s3cret",
        workstation = "SKERRY",
    )

    private val serverKey = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }.generateKeyPair().public.encoded

    @Test
    fun `a completed exchange delivers the credentials bound to the server key`() {
        val server = ModelServer(crypto, credentials, serverKey)

        runBlocking { CredSspClient(credentials, crypto).authenticate(server.source, server.sink, serverKey) }

        val delivered = server.receivedCredentials()
        assertTrue(delivered.contains("elton"), "the user name arrives")
        assertTrue(delivered.contains("s3cret"), "the password arrives")
        assertTrue(delivered.contains("CORP"), "the domain arrives")
        assertEquals(6, server.negotiatedVersion)
    }

    @Test
    fun `a server answering for a different public key never receives the password`() {
        // The relay case: the exchange completes, but the machine at the other end holds a different
        // TLS key, so its binding hash cannot match.
        val otherKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair().public.encoded
        val server = ModelServer(crypto, credentials, otherKey)

        val failure = assertFailsWith<RdpAuthException> {
            runBlocking { CredSspClient(credentials, crypto).authenticate(server.source, server.sink, serverKey) }
        }

        assertTrue(failure.message!!.contains("does not match"), failure.message)
        assertEquals(null, server.authInfo)
    }

    @Test
    fun `a tampered binding answer is refused before the credentials are sent`() {
        val server = ModelServer(crypto, credentials, serverKey, corruptBindingAnswer = true)

        assertFailsWith<RdpAuthException> {
            runBlocking { CredSspClient(credentials, crypto).authenticate(server.source, server.sink, serverKey) }
        }

        assertEquals(null, server.authInfo)
    }

    @Test
    fun `an error code from the server is reported in the user's terms`() {
        val server = ModelServer(crypto, credentials, serverKey, errorCode = 0xC000006D.toInt())

        val failure = assertFailsWith<RdpAuthException> {
            runBlocking { CredSspClient(credentials, crypto).authenticate(server.source, server.sink, serverKey) }
        }

        assertEquals("the user name or password is incorrect", failure.message)
        assertEquals(null, server.authInfo)
    }

    @Test
    fun `a server too old for public key binding is refused`() {
        val server = ModelServer(crypto, credentials, serverKey, serverVersion = 1)

        assertFailsWith<RdpAuthException> {
            runBlocking { CredSspClient(credentials, crypto).authenticate(server.source, server.sink, serverKey) }
        }

        assertEquals(null, server.authInfo)
    }

    @Test
    fun `a version 4 server is bound by the incremented public key instead of a hash`() {
        val server = ModelServer(crypto, credentials, serverKey, serverVersion = 4)

        runBlocking { CredSspClient(credentials, crypto).authenticate(server.source, server.sink, serverKey) }

        assertEquals(4, server.negotiatedVersion)
        assertTrue(server.receivedCredentials().contains("s3cret"))
    }

    @Test
    fun `TSRequest survives a round trip through its encoding`() {
        val request = TsRequest(
            version = 6,
            negoToken = byteArrayOf(1, 2, 3),
            pubKeyAuth = ByteArray(300) { it.toByte() },
            clientNonce = ByteArray(32) { 7 },
        )

        val decoded = TsRequest.decode(request.encode())

        assertEquals(6, decoded.version)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.negoToken)
        assertContentEquals(request.pubKeyAuth, decoded.pubKeyAuth)
        assertContentEquals(request.clientNonce, decoded.clientNonce)
    }

    @Test
    fun `the subject public key is the BIT STRING content, not the whole key info`() {
        val extracted = Der.subjectPublicKey(serverKey)

        assertTrue(extracted.size < serverKey.size)
        // A DER RSAPublicKey SEQUENCE, which is what the BIT STRING of an RSA key wraps.
        assertEquals(0x30, extracted[0].toInt() and 0xFF)
    }

    /**
     * The server half of CredSSP: verifies the NTLM authenticate message the way a real server
     * would (it knows the password), then answers the binding for [publicKey].
     */
    private class ModelServer(
        private val crypto: NtlmCrypto,
        private val credentials: NtlmCredentials,
        private val publicKey: ByteArray,
        private val serverVersion: Int = 6,
        private val corruptBindingAnswer: Boolean = false,
        private val errorCode: Int? = null,
    ) {
        private val outgoing = ArrayDeque<Byte>()
        private var session: NtlmSession? = null
        private var nonce: ByteArray? = null
        var authInfo: ByteArray? = null
            private set
        var negotiatedVersion = 0
            private set

        /** Whether the client's binding hash matched this server's own key. */
        var clientBindingVerified = false
            private set

        val source = RdpSource { dst, offset, len ->
            repeat(len) { index ->
                dst[offset + index] = outgoing.removeFirstOrNull()
                    ?: throw RdpProtocolException("model server has nothing more to say")
            }
        }

        val sink = RdpSink { bytes -> onClientMessage(TsRequest.decode(bytes)) }

        fun receivedCredentials(): String {
            val credentials = requireNotNull(authInfo) { "no credentials were delivered" }
            // The TSCredentials DER holds UTF-16LE strings; strip the zero bytes to search it.
            return credentials.filter { it.toInt() != 0 }.toByteArray().decodeToString()
        }

        private fun onClientMessage(request: TsRequest) {
            when {
                request.negoToken != null && request.pubKeyAuth == null -> answerChallenge()
                request.negoToken != null -> answerBinding(request)
                request.authInfo != null -> authInfo = session!!.unseal(request.authInfo)
                else -> throw RdpProtocolException("unexpected TSRequest from the client")
            }
        }

        private fun answerChallenge() {
            if (errorCode != null) {
                reply(TsRequest(version = serverVersion, errorCode = errorCode))
                return
            }
            negotiatedVersion = minOf(serverVersion, 6)
            reply(TsRequest(version = serverVersion, negoToken = challengeMessage()))
        }

        private fun answerBinding(request: TsRequest) {
            // Recompute the session key from the authenticate message, as a real server does.
            val message = requireNotNull(request.negoToken)
            val key = sessionKeyOf(message)
            val server = NtlmSession(crypto, key, NtlmRole.Server)
            session = server

            val received = server.unseal(requireNotNull(request.pubKeyAuth))
            nonce = request.clientNonce
            val expected = if (negotiatedVersion >= 5) {
                crypto.sha256(magic("CredSSP Client-To-Server Binding Hash") + nonce!! + subjectPublicKey())
            } else {
                subjectPublicKey()
            }
            // A relay cannot verify the client's binding — it was computed over the real server's
            // key — and answers anyway. Recording the outcome rather than failing here is what
            // lets the test check that the *client* is the one that stops.
            clientBindingVerified = received.contentEquals(expected)

            var answer = if (negotiatedVersion >= 5) {
                crypto.sha256(magic("CredSSP Server-To-Client Binding Hash") + nonce!! + subjectPublicKey())
            } else {
                subjectPublicKey().copyOf().also { it[0] = (it[0] + 1).toByte() }
            }
            if (corruptBindingAnswer) answer = answer.copyOf().also { it[0] = (it[0] + 1).toByte() }
            reply(TsRequest(version = serverVersion, pubKeyAuth = server.seal(answer)))
        }

        /** Derive the exported session key from the client's authenticate message. */
        private fun sessionKeyOf(authenticateMessage: ByteArray): ByteArray {
            val ntOffset = readU32le(authenticateMessage, 24)
            val ntLength = readU16le(authenticateMessage, 20)
            val proof = authenticateMessage.copyOfRange(ntOffset, ntOffset + 16)
            // EncryptedRandomSessionKeyFields: Len at 52, MaxLen at 54, BufferOffset at 56.
            val sessionKeyLength = readU16le(authenticateMessage, 52)
            val sessionKeyOffset = readU32le(authenticateMessage, 56)
            val encryptedSessionKey =
                authenticateMessage.copyOfRange(sessionKeyOffset, sessionKeyOffset + sessionKeyLength)
            check(ntLength > 16) { "NTLMv2 response must carry the temp buffer" }
            val responseKey =
                NtlmV2.responseKeyNt(crypto, credentials.user, credentials.domain, credentials.password)
            val baseKey = NtlmV2.sessionBaseKey(crypto, responseKey, proof)
            return Rc4(baseKey).process(encryptedSessionKey)
        }

        private fun subjectPublicKey(): ByteArray = Der.subjectPublicKey(publicKey)

        private fun magic(text: String): ByteArray = text.encodeToByteArray() + ByteArray(1)

        private fun challengeMessage(): ByteArray {
            val targetInfo = byteArrayOf(
                0x02, 0x00, 0x08, 0x00, 0x43, 0x00, 0x4F, 0x00, 0x52, 0x00, 0x50, 0x00, // NbDomainName "CORP"
                0x00, 0x00, 0x00, 0x00, // EOL
            )
            val message = ByteArray(48 + targetInfo.size)
            "NTLMSSP".encodeToByteArray().copyInto(message)
            writeU32le(message, 8, 2)
            writeU32le(
                message,
                20,
                NtlmFlags.NEGOTIATE_UNICODE or NtlmFlags.NEGOTIATE_NTLM or NtlmFlags.NEGOTIATE_TARGET_INFO or
                    NtlmFlags.NEGOTIATE_EXTENDED_SESSIONSECURITY or NtlmFlags.NEGOTIATE_KEY_EXCH or
                    NtlmFlags.NEGOTIATE_SEAL or NtlmFlags.NEGOTIATE_128,
            )
            crypto.randomBytes(8).copyInto(message, 24)
            writeU16le(message, 40, targetInfo.size)
            writeU16le(message, 42, targetInfo.size)
            writeU32le(message, 44, 48)
            targetInfo.copyInto(message, 48)
            return message
        }

        private fun reply(request: TsRequest) {
            for (byte in request.encode()) outgoing.addLast(byte)
        }

        private fun readU16le(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun readU32le(data: ByteArray, offset: Int): Int =
            readU16le(data, offset) or (readU16le(data, offset + 2) shl 16)

        private fun writeU16le(data: ByteArray, offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value ushr 8).toByte()
        }

        private fun writeU32le(data: ByteArray, offset: Int, value: Int) {
            writeU16le(data, offset, value and 0xFFFF)
            writeU16le(data, offset + 2, value ushr 16)
        }
    }
}
