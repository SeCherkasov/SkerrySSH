package app.skerry.shared.rdp

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val TIMEOUT_MS = 15_000L

/**
 * Integration test for the connection-establishment step against a raw [ServerSocket] that speaks
 * X.224 negotiation and then upgrades to TLS in-process.
 */
class RdpTcpConnectorTest {

    private lateinit var server: ServerSocket
    private val sockets = mutableListOf<Socket>()

    @BeforeTest
    fun start() {
        server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    }

    @AfterTest
    fun stop() {
        sockets.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }

    private fun serve(handle: (Socket) -> Unit) {
        thread(name = "rdp-test-server", isDaemon = true) {
            runCatching {
                val socket = server.accept()
                sockets.add(socket)
                handle(socket)
            }
        }
    }

    /** Read one TPKT packet off [input] (the request the connector just sent). */
    private fun readPacket(input: DataInputStream): ByteArray {
        val head = ByteArray(4)
        input.readFully(head)
        val length = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val packet = ByteArray(length)
        head.copyInto(packet)
        input.readFully(packet, 4, length - 4)
        return packet
    }

    private fun connectionConfirm(selectedProtocol: Int, flags: Int = 0x03): ByteArray = byteArrayOf(
        0x03, 0x00, 0x00, 0x13,
        0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
        0x02, flags.toByte(), 0x08, 0x00,
        selectedProtocol.toByte(), 0x00, 0x00, 0x00,
    )

    @Test
    fun `a server that accepts the connection and then says nothing does not hang the client`() {
        // Accepted, and answered never. Coroutine cancellation cannot interrupt the blocking read
        // that follows, so without a read timeout this call never returns at all.
        serve { }

        assertFailsWith<SocketTimeoutException> {
            runBlocking {
                RdpTcpConnector(
                    certificateVerifier = RdpCertificateVerifier { true },
                    negotiationTimeoutMillis = 250,
                ).connect(host = server.inetAddress.hostAddress, port = server.localPort)
            }
        }
    }

    private fun connect(
        verifier: RdpCertificateVerifier = RdpCertificateVerifier { true },
        cookie: String? = null,
        requestedProtocols: Int = RdpSecurityProtocol.SSL or RdpSecurityProtocol.HYBRID,
    ): RdpConnection = runBlocking {
        RdpTcpConnector(certificateVerifier = verifier).connect(
            host = server.inetAddress.hostAddress,
            port = server.localPort,
            requestedProtocols = requestedProtocols,
            cookie = cookie,
        )
    }

    @Test
    fun `negotiates, upgrades to TLS and reports the selected protocol`() {
        val tls = RdpTestCertificates.serverContext()
        val received = CompletableFuture<ByteArray>()
        serve { socket ->
            val input = DataInputStream(socket.getInputStream())
            received.complete(readPacket(input))
            socket.getOutputStream().apply {
                write(connectionConfirm(RdpSecurityProtocol.HYBRID))
                flush()
            }
            val secure = tls.socketFactory.createSocket(socket, null, socket.port, false) as SSLSocket
            secure.useClientMode = false
            secure.startHandshake()
            secure.getOutputStream().apply { write(byteArrayOf(0x42)); flush() }
        }

        val connection = connect()

        try {
            assertEquals(RdpSecurityProtocol.HYBRID, connection.selectedProtocol)
            assertTrue(connection.negotiation.supportsGraphicsPipeline)
            // The connector asked for what we told it to, and the request reached the server verbatim.
            val request = received.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertEquals(
                RdpSecurityProtocol.SSL or RdpSecurityProtocol.HYBRID,
                request[request.size - 4].toInt() and 0xFF,
            )
            // The byte only arrives if the TLS session is really established on both ends.
            val one = ByteArray(1)
            runBlocking { connection.source.readFully(one, 0, 1) }
            assertContentEquals(byteArrayOf(0x42), one)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the server certificate is offered to the verifier before anything is sent`() {
        val tls = RdpTestCertificates.serverContext(commonName = "win-host")
        serve { socket ->
            DataInputStream(socket.getInputStream()).let { readPacket(it) }
            socket.getOutputStream().apply { write(connectionConfirm(RdpSecurityProtocol.SSL)); flush() }
            val secure = tls.socketFactory.createSocket(socket, null, socket.port, false) as SSLSocket
            secure.useClientMode = false
            secure.startHandshake()
        }
        val offers = mutableListOf<RdpCertificateOffer>()

        val connection = connect(verifier = { offer -> offers.add(offer); true })

        try {
            assertEquals(1, offers.size)
            val offer = offers.single()
            assertEquals(server.localPort, offer.port)
            assertTrue(offer.subject.contains("win-host"))
            assertTrue(offer.issuer.contains("win-host"))
            // A self-signed certificate is neither platform-trusted nor a hostname match for an IP.
            assertTrue(!offer.trustedByPlatform)
            assertTrue(!offer.hostnameMatches)
            assertEquals(64 + 31, offer.fingerprintSha256.length) // 32 bytes as AA:BB:...
            assertTrue(offer.publicKey.isNotEmpty())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a rejected certificate fails the connection and closes the socket`() {
        val tls = RdpTestCertificates.serverContext()
        serve { socket ->
            DataInputStream(socket.getInputStream()).let { readPacket(it) }
            socket.getOutputStream().apply { write(connectionConfirm(RdpSecurityProtocol.SSL)); flush() }
            val secure = tls.socketFactory.createSocket(socket, null, socket.port, false) as SSLSocket
            secure.useClientMode = false
            runCatching { secure.startHandshake() }
        }

        assertFailsWith<RdpCertificateRejectedException> { connect(verifier = { false }) }
    }

    @Test
    fun `a negotiation failure is reported with its reason`() {
        serve { socket ->
            DataInputStream(socket.getInputStream()).let { readPacket(it) }
            socket.getOutputStream().apply {
                write(
                    byteArrayOf(
                        0x03, 0x00, 0x00, 0x13,
                        0x0E, 0xD0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x03, 0x00, 0x08, 0x00, 0x05, 0x00, 0x00, 0x00,
                    ),
                )
                flush()
            }
        }

        val failure = assertFailsWith<RdpNegotiationException> { connect() }

        assertEquals(RdpNegotiationFailure.HYBRID_REQUIRED_BY_SERVER, failure.reason)
    }

    @Test
    fun `a server that answers with standard rdp security is refused`() {
        // No negotiation block at all: an old server saying "let's use RC4 with no TLS". We never
        // offered that, and continuing would mean an unencrypted transport the user thinks is secure.
        serve { socket ->
            DataInputStream(socket.getInputStream()).let { readPacket(it) }
            socket.getOutputStream().apply {
                write(byteArrayOf(0x03, 0x00, 0x00, 0x0B, 0x06, 0xD0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00))
                flush()
            }
        }

        assertFailsWith<RdpProtocolException> { connect() }
    }

    @Test
    fun `a selected protocol we did not ask for is refused`() {
        serve { socket ->
            DataInputStream(socket.getInputStream()).let { readPacket(it) }
            socket.getOutputStream().apply {
                write(connectionConfirm(RdpSecurityProtocol.RDSTLS))
                flush()
            }
        }

        assertFailsWith<RdpProtocolException> { connect(requestedProtocols = RdpSecurityProtocol.SSL) }
    }

    @Test
    fun `the routing cookie reaches the server when the profile has a user name`() {
        val received = CompletableFuture<ByteArray>()
        serve { socket ->
            received.complete(readPacket(DataInputStream(socket.getInputStream())))
            socket.getOutputStream().apply {
                write(byteArrayOf(0x03, 0x00, 0x00, 0x0B, 0x06, 0xD0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00))
                flush()
            }
        }

        runCatching { connect(cookie = "elton") }

        val request = received.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).decodeToString()
        assertTrue(request.contains("Cookie: mstshash=elton\r\n"))
    }
}
