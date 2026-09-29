package app.skerry.shared.rdp

import java.io.DataInputStream
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * A connect that fails names the step it failed at and how the link ended. The server here closes
 * the socket at each step in turn — the case a user reports as a bare "failed to connect", with
 * nothing on the server side to explain it either.
 */
class RdpConnectStageTest {

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
        thread(name = "rdp-stage-test-server", isDaemon = true) {
            runCatching {
                val socket = server.accept()
                sockets.add(socket)
                handle(socket)
                socket.close()
            }
        }
    }

    private fun readPacket(input: DataInputStream) {
        val head = ByteArray(4)
        input.readFully(head)
        val length = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        input.readFully(ByteArray(length - 4))
    }

    private fun confirm(socket: Socket, selectedProtocol: Int) {
        readPacket(DataInputStream(socket.getInputStream()))
        socket.getOutputStream().apply {
            write(
                byteArrayOf(
                    0x03, 0x00, 0x00, 0x13,
                    0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
                    0x02, 0x00, 0x08, 0x00,
                    selectedProtocol.toByte(), 0x00, 0x00, 0x00,
                ),
            )
            flush()
        }
    }

    private fun upgrade(socket: Socket) {
        val tls = RdpTestCertificates.serverContext()
        val secure = tls.socketFactory.createSocket(socket, null, socket.port, false) as SSLSocket
        secure.useClientMode = false
        secure.startHandshake()
    }

    private fun connectFailure(
        port: Int = server.localPort,
        verifier: RdpCertificateVerifier = RecordingVerifier(),
    ): RdpConnectException =
        assertFailsWith<RdpConnectException> {
            runBlocking {
                RdpTcpTransport(verifier).connect(
                    RdpTarget(host = server.inetAddress.hostAddress, port = port, desktopWidth = 1024, desktopHeight = 768),
                    RdpCredentials(username = "elton", password = "secret"),
                )
            }
        }

    @Test
    fun `nothing listening on the port is a refused tcp connection`() {
        val port = server.localPort
        server.close()

        val failure = connectFailure(port)

        assertEquals(RdpConnectStage.Tcp, failure.stage)
        assertEquals(RdpDrop.Refused, failure.drop)
    }

    @Test
    fun `a server closing before the negotiation answer is named as such`() {
        serve { socket -> readPacket(DataInputStream(socket.getInputStream())) }

        val failure = connectFailure()

        assertEquals(RdpConnectStage.Negotiation, failure.stage)
        assertEquals(RdpDrop.Closed, failure.drop)
    }

    @Test
    fun `a server closing instead of the tls handshake is named as such`() {
        serve { socket -> confirm(socket, RdpSecurityProtocol.SSL) }

        val failure = connectFailure()

        assertEquals(RdpConnectStage.Tls, failure.stage)
        assertEquals(RdpDrop.Closed, failure.drop)
    }

    @Test
    fun `a server closing after tls when nla was selected fails in nla`() {
        serve { socket ->
            confirm(socket, RdpSecurityProtocol.HYBRID)
            upgrade(socket)
        }

        val failure = connectFailure()

        assertEquals(RdpConnectStage.Nla, failure.stage)
        assertEquals(RdpDrop.Closed, failure.drop)
    }

    @Test
    fun `a server closing after tls without nla fails in the mcs connect`() {
        serve { socket ->
            confirm(socket, RdpSecurityProtocol.SSL)
            upgrade(socket)
        }

        val failure = connectFailure()

        assertEquals(RdpConnectStage.Mcs, failure.stage)
        assertEquals(RdpDrop.Closed, failure.drop)
    }

    @Test
    fun `a reason the server gave keeps its type under the stage`() {
        serve { socket ->
            readPacket(DataInputStream(socket.getInputStream()))
            socket.getOutputStream().apply {
                // RDP_NEG_FAILURE, SSL_NOT_ALLOWED_BY_SERVER.
                write(
                    byteArrayOf(
                        0x03, 0x00, 0x00, 0x13,
                        0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
                        0x03, 0x00, 0x08, 0x00,
                        0x02, 0x00, 0x00, 0x00,
                    ),
                )
                flush()
            }
        }

        val failure = connectFailure()

        assertEquals(RdpConnectStage.Negotiation, failure.stage)
        assertNull(failure.drop, "the server answered; the network did not end anything")
        assertIs<RdpNegotiationException>(failure.cause)
    }

    @Test
    fun `a refused certificate is reported under tls with the refusal as its reason`() {
        serve { socket ->
            confirm(socket, RdpSecurityProtocol.SSL)
            runCatching { upgrade(socket) }
        }

        val failure = connectFailure(verifier = RecordingVerifier(answer = false))

        assertEquals(RdpConnectStage.Tls, failure.stage)
        assertNull(failure.drop, "the refusal is the reason, whatever the socket did after it")
        assertIs<RdpCertificateRejectedException>(failure.cause)
    }

    @Test
    fun `a cancelled connect stays a cancellation`() {
        // Accepted and never answered: only the cancellation ends the attempt.
        serve { Thread.sleep(5_000) }

        assertFailsWith<CancellationException> {
            runBlocking {
                withTimeout(300) {
                    RdpTcpTransport(RecordingVerifier()).connect(
                        RdpTarget(host = server.inetAddress.hostAddress, port = server.localPort, desktopWidth = 1024, desktopHeight = 768),
                        RdpCredentials(username = "elton", password = "secret"),
                    )
                }
            }
        }
    }

    @Test
    fun `only a failure inside a named step is wrapped`() {
        val redirect = RdpRedirectException(RdpRedirection(sessionId = 0, flags = 0, targetNetAddress = "rds01.corp.example.com"))
        assertSame(redirect, rdpConnectFailure(RdpConnectStage.Licensing, redirect))

        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, rdpConnectFailure(RdpConnectStage.Tls, cancelled))

        val between = IllegalStateException("audio device")
        assertSame(between, rdpConnectFailure(null, between))

        val wrapped = assertIs<RdpConnectException>(rdpConnectFailure(RdpConnectStage.Mcs, EOFException()))
        assertEquals(RdpConnectStage.Mcs, wrapped.stage)
        assertEquals(RdpDrop.Closed, wrapped.drop)
        assertEquals("Mcs failed (Closed): EOFException", wrapped.message)
    }

    @Test
    fun `the way the link ended is read off the exception behind it`() {
        assertEquals(RdpDrop.Closed, rdpDropOf(EOFException()))
        assertEquals(RdpDrop.Timeout, rdpDropOf(SocketTimeoutException("Read timed out")))
        // The OS words the refusal in the user's language; the type alone decides.
        assertEquals(RdpDrop.Refused, rdpDropOf(ConnectException("В соединении отказано")))
        assertNull(rdpDropOf(NoRouteToHostException("No route to host")))
        // Our own cancellation closing the socket is not the peer's doing.
        assertNull(rdpDropOf(SocketException("Socket closed")))
        assertEquals(RdpDrop.Closed, rdpDropOf(SSLHandshakeException("Remote host terminated the handshake")))
        // A reason the server named outranks what the socket did afterwards.
        assertNull(rdpDropOf(RdpAuthException(RdpAuthFailure.Credentials, "logon failure", EOFException())))
        assertEquals(RdpDrop.Unresolved, rdpDropOf(UnknownHostException("rdp.example")))
        assertEquals(RdpDrop.Reset, rdpDropOf(SocketException("Connection reset")))
        // JSSE reports a peer that hung up mid-handshake as a handshake failure caused by the EOF.
        assertEquals(
            RdpDrop.Closed,
            rdpDropOf(RdpTlsException("handshake", SSLHandshakeException("Remote host terminated the handshake").apply { initCause(EOFException()) })),
        )
        assertNull(rdpDropOf(RdpProtocolException("truncated")))
    }
}
