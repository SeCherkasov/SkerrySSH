package app.skerry.shared.io

import app.skerry.shared.rdp.RdpTestCertificates
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client's own WebSocket connector: the upgrade is read under a byte cap and a deadline, and
 * the socket after it is held to the frame and ping limits it was given. These cover what the
 * connector owns — the handshake, TLS, proxies, cancellation — beyond the header and queue bounds
 * that [HeaderSectionBoundTest] and `WebSocketBackpressureTest` pin through the sync client.
 */
class UntrustedWebSocketTest {

    private val limits = WebSocketLimits(maxFrameBytes = 1024, incomingFrames = 8, pingIntervalMillis = 60_000, handshakeTimeoutMillis = 5_000)
    private val plain = SSLSocketFactory.getDefault() as SSLSocketFactory

    private suspend fun <T> open(
        url: String,
        limits: WebSocketLimits = this.limits,
        tls: SSLSocketFactory = plain,
        proxies: ProxySelector? = null,
        block: suspend io.ktor.websocket.DefaultWebSocketSession.() -> T,
    ): T = untrustedWebSocket(url, "token", limits, WebSocketNetwork(tls, proxies), block)

    /** Texts of the frames the session delivers until it ends, however it ends. */
    private suspend fun io.ktor.websocket.DefaultWebSocketSession.texts(): List<String> {
        val texts = mutableListOf<String>()
        runCatching { for (frame in incoming) if (frame is Frame.Text) texts += frame.readText() }
        return texts
    }

    @Test
    fun `a frame that arrives with the upgrade answer is not lost`() {
        HostilePeer.frameFlood(first = "1", opcode = TEXT, frameBytes = 16, count = 0).use { peer ->
            val first = runBlocking { withTimeout(5_000) { open(peer.wsUrl) { (incoming.receive() as Frame.Text).readText() } } }
            assertEquals("1", first)
        }
    }

    @Test
    fun `the upgrade request carries the bearer token and the path`() {
        val request = CompletableDeferred<String>()
        HostilePeer { input, output ->
            val head = HostilePeer.readHead(input)
            request.complete(head)
            output.write(HostilePeer.upgradeAnswer(head))
            output.flush()
            while (input.read() != -1) Unit
        }.use { peer ->
            runBlocking { withTimeout(5_000) { open("ws://127.0.0.1:${peer.port}/teams/t/shares/s/join?x=1") {} } }
            val head = runBlocking { request.await() }
            assertTrue(head.startsWith("GET /teams/t/shares/s/join?x=1 HTTP/1.1\r\n"), head)
            assertTrue("\r\nAuthorization: Bearer token\r\n" in head, head)
            assertTrue("\r\nHost: 127.0.0.1:${peer.port}\r\n" in head, head)
        }
    }

    @Test
    fun `a refused upgrade reaches the share client as the status it was`() {
        HostilePeer { input, output ->
            HostilePeer.readHead(input)
            output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())
            output.flush()
        }.use { peer ->
            val session = SyncSession(accountId = "a@example.com", accessToken = "t", refreshToken = "r")
            val failure = assertFailsWith<SyncException> {
                runBlocking { withTimeout(5_000) { KtorSyncClient(peer.httpUrl).joinShare(session, "team", "share-e2e") {} } }
            }
            assertEquals(SyncException.Kind.UNAUTHORIZED, failure.kind)
            assertEquals(401, failure.status)
        }
    }

    @Test
    fun `a 101 that does not accept the client's key is not a WebSocket`() {
        HostilePeer { input, output ->
            output.write(HostilePeer.upgradeAnswer(HostilePeer.readHead(input), accept = "bm90IHRoZSBrZXk="))
            output.flush()
            while (input.read() != -1) Unit
        }.use { peer ->
            var ran = false
            assertFailsWith<IOException> { runBlocking { withTimeout(5_000) { open(peer.wsUrl) { ran = true } } } }
            assertFalse(ran, "the session ran over an upgrade the server never accepted")
        }
    }

    @Test
    fun `a server that never answers the upgrade fails at the handshake deadline`() {
        silentPeer().use { peer ->
            val started = System.nanoTime()
            assertFailsWith<IOException> {
                runBlocking { withTimeout(5_000) { open(peer.wsUrl, limits.copy(handshakeTimeoutMillis = 300)) {} } }
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the handshake outlived its deadline")
        }
    }

    @Test
    fun `cancelling the caller ends a handshake stuck on a silent server`() {
        silentPeer().use { peer ->
            runBlocking {
                val call = launch(Dispatchers.Default) { open(peer.wsUrl, limits.copy(handshakeTimeoutMillis = 60_000)) {} }
                delay(300)
                withTimeout(3_000) { call.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `a server that stops answering pings ends the session`() {
        HostilePeer { input, output ->
            output.write(HostilePeer.upgradeAnswer(HostilePeer.readHead(input)))
            output.flush()
            while (input.read() != -1) Unit // reads the pings, never answers them
        }.use { peer ->
            runBlocking { withTimeout(5_000) { open(peer.wsUrl, limits.copy(pingIntervalMillis = 200)) { texts() } } }
        }
    }

    @Test
    fun `a frame past the cap ends the session without reaching the reader`() {
        HostilePeer.frameFlood(first = "1", opcode = TEXT, frameBytes = 4 * 1024, count = 3).use { peer ->
            val texts = runBlocking { withTimeout(5_000) { open(peer.wsUrl) { texts() } } }
            assertEquals(listOf("1"), texts)
        }
    }

    @Test
    fun `a bearer token that would break the request head is refused before connecting`() {
        silentPeer().use { peer ->
            assertFailsWith<IllegalArgumentException> {
                runBlocking { untrustedWebSocket(peer.wsUrl, "t\r\nX-Evil: 1", limits, WebSocketNetwork(plain, null)) {} }
            }
        }
    }

    @Test
    fun `wss checks the certificate against the host name`() {
        val (server, certificate) = RdpTestCertificates.serverWithCertificate(commonName = "other.example", dnsNames = listOf("other.example"))
        val listener = server.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        HostilePeer.frameFlood(first = "1", opcode = TEXT, frameBytes = 16, count = 0, server = listener).use { peer ->
            assertFailsWith<SSLHandshakeException> {
                runBlocking { withTimeout(5_000) { open("wss://127.0.0.1:${peer.port}/", tls = trusting(certificate)) {} } }
            }
        }
    }

    @Test
    fun `wss opens to a server whose certificate names it`() {
        val (server, certificate) = RdpTestCertificates.serverWithCertificate(commonName = "loopback", ipAddresses = listOf("127.0.0.1"))
        val listener = server.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        HostilePeer.frameFlood(first = "1", opcode = TEXT, frameBytes = 16, count = 0, server = listener).use { peer ->
            val first = runBlocking {
                withTimeout(5_000) {
                    open("wss://127.0.0.1:${peer.port}/", tls = trusting(certificate)) { (incoming.receive() as Frame.Text).readText() }
                }
            }
            assertEquals("1", first)
        }
    }

    @Test
    fun `an HTTP proxy is tunnelled through with CONNECT`() {
        val connect = CompletableDeferred<String>()
        HostilePeer { input, output ->
            connect.complete(HostilePeer.readHead(input))
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
            output.flush()
            // The tunnel is up: from here the proxy peer plays the server.
            output.write(HostilePeer.upgradeAnswer(HostilePeer.readHead(input)) + HostilePeer.frame(TEXT, "1".encodeToByteArray()))
            output.flush()
            while (input.read() != -1) Unit
        }.use { proxy ->
            val first = runBlocking {
                withTimeout(5_000) {
                    open("ws://sync.example:8443/sync", proxies = proxyAt(proxy.port)) { (incoming.receive() as Frame.Text).readText() }
                }
            }
            assertEquals("1", first)
            assertTrue(runBlocking { connect.await() }.startsWith("CONNECT sync.example:8443 HTTP/1.1\r\n"))
        }
    }

    @Test
    fun `a proxy that refuses the tunnel fails the connection`() {
        HostilePeer { input, output ->
            HostilePeer.readHead(input)
            output.write("HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())
            output.flush()
        }.use { proxy ->
            val failure = runBlocking {
                runCatching { withTimeout(5_000) { open("ws://sync.example/sync", proxies = proxyAt(proxy.port)) {} } }
            }.exceptionOrNull()
            assertTrue(failure is IOException && "407" in failure.message.orEmpty(), "got $failure")
        }
    }

    @Test
    fun `a proxy that sends data ahead of the tunnel is refused`() {
        HostilePeer { input, output ->
            HostilePeer.readHead(input)
            // Bytes behind the proxy's reply would be read as the server's upgrade answer.
            output.write("HTTP/1.1 200 Connection established\r\n\r\nHTTP/1.1 101 Switching Protocols\r\n".encodeToByteArray())
            output.flush()
            while (input.read() != -1) Unit
        }.use { proxy ->
            val failure = runBlocking {
                runCatching { withTimeout(5_000) { open("ws://sync.example/sync", proxies = proxyAt(proxy.port)) {} } }
            }.exceptionOrNull()
            assertTrue(failure is IOException && "ahead of the tunnel" in failure.message.orEmpty(), "got $failure")
        }
    }

    @Test
    fun `wss through an HTTP proxy checks the certificate against the target, not the proxy`() {
        val (server, certificate) = RdpTestCertificates.serverWithCertificate(commonName = "sync.example", dnsNames = listOf("sync.example"))
        HostilePeer { input, output ->
            HostilePeer.readHead(input)
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
            output.flush()
            val tls = server.socketFactory.createSocket(accepted, null, true) as javax.net.ssl.SSLSocket
            tls.useClientMode = false
            val tlsIn = tls.inputStream.buffered()
            tls.outputStream.write(HostilePeer.upgradeAnswer(HostilePeer.readHead(tlsIn)) + HostilePeer.frame(TEXT, "1".encodeToByteArray()))
            tls.outputStream.flush()
            while (tlsIn.read() != -1) Unit
        }.use { proxy ->
            val first = runBlocking {
                withTimeout(5_000) {
                    open("wss://sync.example:8443/sync", tls = trusting(certificate), proxies = proxyAt(proxy.port)) {
                        (incoming.receive() as Frame.Text).readText()
                    }
                }
            }
            assertEquals("1", first)
        }
    }

    @Test
    fun `a server that hangs up in the middle of its answer fails the handshake`() {
        HostilePeer { input, output ->
            HostilePeer.readHead(input)
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n".encodeToByteArray())
            output.flush()
        }.use { peer ->
            val failure = runBlocking { runCatching { withTimeout(5_000) { open(peer.wsUrl) {} } }.exceptionOrNull() }
            assertTrue(failure is IOException && "closed the connection during the handshake" in failure.message.orEmpty(), "got $failure")
        }
    }

    @Test
    fun `a TLS handshake fed a byte at a time still ends at the deadline`() {
        HostilePeer { input, output ->
            // A handshake record that announces 16 KiB and delivers it one byte per 100 ms: each
            // read is quick, so only a deadline on the whole handshake ends it.
            output.write(byteArrayOf(0x16, 0x03, 0x03, 0x40, 0x00))
            output.flush()
            while (true) {
                output.write(0)
                output.flush()
                Thread.sleep(100)
            }
        }.use { peer ->
            val started = System.nanoTime()
            val failure = runBlocking {
                runCatching {
                    withTimeout(10_000) { open("wss://127.0.0.1:${peer.port}/", limits.copy(handshakeTimeoutMillis = 800)) {} }
                }.exceptionOrNull()
            }
            assertTrue(failure is SocketTimeoutException, "got $failure")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the TLS handshake outlived its deadline")
        }
    }

    @Test
    fun `an upgrade answer up to the head cap is read, one past it is refused`() {
        fun padded(total: Int) = HostilePeer { input, output ->
            val answer = HostilePeer.upgradeAnswer(HostilePeer.readHead(input)).decodeToString()
            val filler = "X-Pad: \r\n".length
            val pad = "a".repeat(total - answer.length - filler)
            output.write((answer.removeSuffix("\r\n") + "X-Pad: $pad\r\n\r\n").encodeToByteArray())
            output.flush()
            while (input.read() != -1) Unit
        }
        padded(MAX_HANDSHAKE_HEAD_BYTES).use { peer ->
            runBlocking { withTimeout(5_000) { open(peer.wsUrl) {} } }
        }
        padded(MAX_HANDSHAKE_HEAD_BYTES + 2).use { peer ->
            assertFailsWith<ResponseHeadTooLargeException> { runBlocking { withTimeout(5_000) { open(peer.wsUrl) {} } } }
        }
    }

    @Test
    fun `a SOCKS proxy is handed the host name to resolve`() {
        val requested = CompletableDeferred<String>()
        HostilePeer { input, output ->
            val data = java.io.DataInputStream(input)
            data.readUnsignedByte() // version 5
            data.skipBytes(data.readUnsignedByte()) // offered methods
            output.write(byteArrayOf(5, 0)) // no authentication
            data.skipBytes(3) // version, CONNECT, reserved
            val type = data.readUnsignedByte()
            val host = if (type == 3) String(ByteArray(data.readUnsignedByte()).also { data.readFully(it) }) else "type $type"
            requested.complete("$host:${data.readUnsignedShort()}")
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.write(HostilePeer.upgradeAnswer(HostilePeer.readHead(input)) + HostilePeer.frame(TEXT, "1".encodeToByteArray()))
            output.flush()
            while (input.read() != -1) Unit
        }.use { proxy ->
            val socks = object : ProxySelector() {
                override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxy.port)))
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            }
            val first = runBlocking {
                withTimeout(5_000) { open("ws://sync.example:8443/sync", proxies = socks) { (incoming.receive() as Frame.Text).readText() } }
            }
            assertEquals("1", first)
            assertEquals("sync.example:8443", runBlocking { requested.await() })
        }
    }

    @Test
    fun `the Host header names the port only when it is not the scheme's, and brackets IPv6`() {
        assertEquals("sync.example", WebSocketTarget.parse("wss://sync.example/sync").hostHeader)
        assertEquals("sync.example", WebSocketTarget.parse("ws://sync.example:80/sync").hostHeader)
        assertEquals("sync.example:8443", WebSocketTarget.parse("wss://sync.example:8443/sync").hostHeader)
        val v6 = WebSocketTarget.parse("wss://[::1]:8443/sync?x=1")
        assertEquals("::1", v6.host)
        assertEquals("[::1]:8443", v6.hostHeader)
        assertEquals("/sync?x=1", v6.pathAndQuery)
        assertEquals("[::1]", WebSocketTarget.parse("wss://[::1]/").hostHeader)
        assertEquals("/", WebSocketTarget.parse("ws://sync.example").pathAndQuery)
        assertFailsWith<IllegalArgumentException> { WebSocketTarget.parse("https://sync.example/sync") }
    }

    private fun silentPeer() = HostilePeer { input, _ -> while (input.read() != -1) Unit }

    private fun WebSocketLimits.copy(handshakeTimeoutMillis: Long = this.handshakeTimeoutMillis, pingIntervalMillis: Long = this.pingIntervalMillis) =
        WebSocketLimits(maxFrameBytes, incomingFrames, pingIntervalMillis, handshakeTimeoutMillis)

    private fun trusting(certificate: X509Certificate): SSLSocketFactory {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    /** Unresolved, as the platform's own selector hands a configured proxy out. */
    private fun proxyAt(port: Int) = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", port)))
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    private companion object {
        const val TEXT = 0x1
    }
}
