package app.skerry.shared.rdp

import app.skerry.shared.io.causeChain
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x509.KeyUsage

/**
 * Windows signs its own Remote Desktop certificate with a key usage of key encipherment only. Android's
 * TLS stack refuses such a certificate for an ECDHE suite, where the key has to sign — the handshake
 * that the desktop client completes fails on a phone before the certificate is even shown (#395).
 * A suite where the key encrypts instead is the one this certificate was made for.
 */
class RdpTlsKeyUsageTest {

    private lateinit var server: ServerSocket
    private val sockets = mutableListOf<Socket>()
    private val accepted = AtomicInteger()

    @BeforeTest
    fun start() {
        server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    }

    @AfterTest
    fun stop() {
        sockets.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }

    /**
     * Answers each negotiation with SSL, one connection after another. The connections with a
     * context in [tls] (by 0-based index) go on to a real TLS handshake; the rest stay open.
     */
    private fun serveNegotiations(connections: Int, tls: Map<Int, SSLContext> = emptyMap()) {
        thread(name = "rdp-key-usage-server", isDaemon = true) {
            runCatching {
                repeat(connections) { index ->
                    val socket = server.accept()
                    accepted.incrementAndGet()
                    sockets.add(socket)
                    val input = DataInputStream(socket.getInputStream())
                    val head = ByteArray(4).also { input.readFully(it) }
                    val length = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                    input.readFully(ByteArray(length - 4))
                    socket.getOutputStream().apply {
                        write(
                            byteArrayOf(
                                0x03, 0x00, 0x00, 0x13,
                                0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
                                0x02, 0x00, 0x08, 0x00,
                                RdpSecurityProtocol.SSL.toByte(), 0x00, 0x00, 0x00,
                            ),
                        )
                        flush()
                    }
                    tls[index]?.let { context ->
                        runCatching {
                            val secure = context.socketFactory.createSocket(socket, null, socket.port, false) as SSLSocket
                            secure.useClientMode = false
                            secure.startHandshake()
                        }
                    }
                }
            }
        }
    }

    private class Attempt(val cipherSuites: List<String>, val protocols: List<String>)

    private fun connect(
        verifier: RdpCertificateVerifier = RecordingVerifier(),
        onStage: (RdpConnectStage) -> Unit = {},
        platformSuites: (SSLSocket) -> Array<String> = { RSA_SUITES + it.supportedCipherSuites },
        handshake: (SSLSocket) -> Unit,
    ) = runBlocking {
        RdpTcpConnector(certificateVerifier = verifier, handshake = handshake, platformSuites = platformSuites)
            .connect(host = server.inetAddress.hostAddress, port = server.localPort, onStage = onStage)
    }

    /**
     * The retry's offer, recorded, then widened back to the JVM's defaults for the handshake itself:
     * newer JDKs disable the RSA key-exchange suites outright, and what is under test is what the
     * connector offered and what it does with the certificate that comes back.
     */
    private fun recordThenHandshake(attempts: MutableList<Attempt>): (SSLSocket) -> Unit = { socket ->
        attempts += Attempt(socket.enabledCipherSuites.toList(), socket.enabledProtocols.toList())
        if (attempts.size == 1) throw keyUsageRefusal()
        socket.enabledCipherSuites = SSLContext.getDefault().defaultSSLParameters.cipherSuites
        socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.startHandshake()
    }

    @Test
    fun `a certificate refused for its key usage is dialled again with rsa key exchange`() {
        val encipherOnly = RdpTestCertificates.serverContext(keyUsage = KeyUsage.keyEncipherment or KeyUsage.dataEncipherment)
        serveNegotiations(connections = 2, tls = mapOf(1 to encipherOnly))
        val attempts = mutableListOf<Attempt>()
        val stages = mutableListOf<RdpConnectStage>()
        val verifier = RecordingVerifier()

        val connection = connect(verifier, onStage = { stages += it }, handshake = recordThenHandshake(attempts))
        connection.close()

        assertEquals(2, accepted.get(), "the retry is a connection of its own")
        assertTrue(attempts[0].cipherSuites.any { it.startsWith("TLS_ECDHE_") }, "the first attempt is the platform's usual offer")
        val retry = attempts[1]
        assertTrue(retry.cipherSuites.isNotEmpty())
        assertTrue(
            retry.cipherSuites.all { it.startsWith("TLS_RSA_WITH_AES_") && "_GCM_" in it },
            "offered: ${retry.cipherSuites}",
        )
        assertEquals(listOf("TLSv1.2"), retry.protocols, "TLS 1.3 has no RSA key exchange")
        assertEquals(1, verifier.remembered.size)
        assertEquals(
            listOf(RdpConnectStage.Tcp, RdpConnectStage.Negotiation, RdpConnectStage.Tls).let { it + it },
            stages,
        )
    }

    @Test
    fun `a retry answered by a certificate that may sign is not kept`() {
        // BoringSSL would have accepted this certificate for ECDHE, so the refusal that sent us here
        // came from someone else: whoever answered the first attempt wanted the weaker exchange.
        val maySign = RdpTestCertificates.serverContext(keyUsage = KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        serveNegotiations(connections = 2, tls = mapOf(1 to maySign))
        val verifier = RecordingVerifier()

        val failure = assertFailsWith<RdpTlsException> { connect(verifier, handshake = recordThenHandshake(mutableListOf())) }

        assertTrue(verifier.remembered.isEmpty(), "a certificate from a connection we refused is not pinned")
        assertTrue(failure.refusalKept())
        assertTrue(failure.refusedForKeyUsage(), failure.message)
    }

    @Test
    fun `a retry answered by a certificate without a key usage is not kept`() {
        serveNegotiations(connections = 2, tls = mapOf(1 to RdpTestCertificates.serverContext()))
        val verifier = RecordingVerifier()

        val failure = assertFailsWith<RdpTlsException> { connect(verifier, handshake = recordThenHandshake(mutableListOf())) }

        assertTrue(verifier.remembered.isEmpty())
        assertTrue(failure.refusedForKeyUsage(), failure.message)
    }

    @Test
    fun `a retry answered by a certificate that may not encipher is not kept`() {
        val agreementOnly = RdpTestCertificates.serverContext(keyUsage = KeyUsage.keyAgreement)
        serveNegotiations(connections = 2, tls = mapOf(1 to agreementOnly))
        val verifier = RecordingVerifier()

        val failure = assertFailsWith<RdpTlsException> { connect(verifier, handshake = recordThenHandshake(mutableListOf())) }

        assertTrue(verifier.remembered.isEmpty())
        assertTrue(failure.refusedForKeyUsage(), failure.message)
    }

    @Test
    fun `a certificate approved in the refused attempt is not asked about again`() {
        // Whether BoringSSL checks the key usage before or after asking the trust manager is its own
        // business; if after, the user has already answered for this certificate once.
        val encipherOnly = RdpTestCertificates.serverContext(keyUsage = KeyUsage.keyEncipherment)
        serveNegotiations(connections = 2, tls = mapOf(0 to encipherOnly, 1 to encipherOnly))
        val verifier = RecordingVerifier()
        var attempts = 0

        connect(verifier) { socket ->
            attempts++
            socket.enabledProtocols = arrayOf("TLSv1.2")
            if (attempts == 1) {
                socket.startHandshake()
                throw keyUsageRefusal()
            }
            socket.enabledCipherSuites = SSLContext.getDefault().defaultSSLParameters.cipherSuites
            socket.startHandshake()
        }.close()

        assertEquals(2, attempts)
        assertEquals(1, verifier.verified.size, "one question for one certificate")
        assertEquals(1, verifier.remembered.size)
    }

    @Test
    fun `the rsa retry is made once and keeps the first refusal`() {
        serveNegotiations(connections = 3)
        var attempts = 0

        val failure = assertFailsWith<RdpTlsException> {
            connect { attempts++; throw keyUsageRefusal() }
        }

        assertEquals(2, attempts)
        assertEquals(2, accepted.get())
        assertTrue(failure.refusalKept())
    }

    @Test
    fun `a platform without the rsa key exchange fails the retry and keeps the refusal`() {
        serveNegotiations(connections = 2)
        var handshakes = 0

        val failure = assertFailsWith<RdpTlsException> {
            connect(platformSuites = { socket -> socket.supportedCipherSuites.filterNot { it.startsWith("TLS_RSA_") }.toTypedArray() }) {
                handshakes++
                throw keyUsageRefusal()
            }
        }

        assertEquals(1, handshakes, "nothing to offer, so no second handshake")
        assertEquals(2, accepted.get())
        assertTrue(failure.causeChain().any { it.message.orEmpty().contains("no TLS 1.2 RSA key exchange") }, "was $failure")
        assertTrue(failure.refusalKept())
    }

    @Test
    fun `any other tls failure is not dialled again`() {
        serveNegotiations(connections = 2)
        var attempts = 0

        assertFailsWith<RdpTlsException> {
            connect { attempts++; throw SSLHandshakeException("Received fatal alert: handshake_failure") }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `a connect cancelled while the first attempt fails is not dialled again`() {
        serveNegotiations(connections = 2)
        val stages = mutableListOf<RdpConnectStage>()

        assertFailsWith<CancellationException> {
            runBlocking {
                val call = async(start = CoroutineStart.LAZY) {
                    RdpTcpConnector(
                        certificateVerifier = RecordingVerifier(),
                        handshake = {
                            coroutineContext.cancel()
                            throw keyUsageRefusal()
                        },
                    ).connect(host = server.inetAddress.hostAddress, port = server.localPort, onStage = { stages += it })
                }
                call.await()
            }
        }

        // The stages, not the handshakes: a retry on a cancelled job dies before its handshake.
        assertEquals(listOf(RdpConnectStage.Tcp, RdpConnectStage.Negotiation, RdpConnectStage.Tls), stages)
    }

    /**
     * Whether the first attempt's refusal travels with the failure. Looked for along the chain: in
     * debug mode coroutines rethrow a copy whose cause is the exception that carries it.
     */
    private fun Throwable.refusalKept(): Boolean = causeChain()
        .flatMap { it.suppressedExceptions.asSequence() }
        .any { it.message.orEmpty().contains("KEY_USAGE_BIT_INCORRECT") }

    private fun Throwable.refusedForKeyUsage(): Boolean =
        causeChain().any { it.message.orEmpty().contains("did not need it") }

    /** What Conscrypt reported for the Windows Server 2019 certificate in #395. */
    private fun keyUsageRefusal() = SSLHandshakeException(
        "Read error: ssl=0xb400007072ebf298: Failure in SSL library, usually a protocol error\n" +
            "error:1000012e:SSL routines:OPENSSL_internal:KEY_USAGE_BIT_INCORRECT " +
            "(external/boringssl/src/ssl/ssl_cert.cc:396 0x6eff2dcbc6:0x00000000)",
    )

    private companion object {
        /** What Conscrypt lists, and what JDKs from 21.0.11 on no longer do. */
        val RSA_SUITES = arrayOf(
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
        )
    }
}
