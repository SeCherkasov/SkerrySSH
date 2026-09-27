package app.skerry.shared.io

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.reader
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.RawWebSocket
import io.ktor.websocket.WebSocketChannelsConfig
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** What a WebSocket to a server the app does not control may cost the client. */
class WebSocketLimits(
    /** Largest frame, or reassembled message, the client reads; a larger one closes the socket. */
    val maxFrameBytes: Long,
    /** Frames queued for a reader that is busy; past it the client stops reading the socket. */
    val incomingFrames: Int,
    /** Ping period; a peer that misses two pongs in a row fails the session. */
    val pingIntervalMillis: Long,
    /** Connect, TLS and the upgrade exchange together, including any proxy tunnel. */
    val handshakeTimeoutMillis: Long = UNTRUSTED_REQUEST_TIMEOUT_MS,
)

/** The server answered a WebSocket upgrade with [status] instead of `101`. */
class WebSocketUpgradeRefusedException(val status: Int) : IOException("server refused the WebSocket upgrade ($status)")

/** A response head (an upgrade answer, a proxy's reply) ran past [limit] bytes; the rest was never read. */
class ResponseHeadTooLargeException(val limit: Int) : IOException("response head exceeds $limit bytes")

/**
 * Opens a WebSocket to [url] (`ws://` or `wss://`), runs [block] on it and closes it.
 *
 * The client's own connector, not Ktor's: Ktor's engines either leave the upgrade answer's header
 * section unbounded and untimed (CIO) or read a whole message into memory however large it is
 * (OkHttp). Here the upgrade is read with a byte cap and a deadline, and what follows it is Ktor's
 * own frame codec, fed from the socket and held to [limits] — frame size, queue depth, pings.
 *
 * The connection honours the platform's proxy settings like the HTTP clients do: an HTTP proxy is
 * tunnelled through with `CONNECT`, a SOCKS one is handed to the socket. TLS is the platform's, with
 * the host name checked against the certificate.
 */
suspend fun <T> untrustedWebSocket(
    url: String,
    bearerToken: String,
    limits: WebSocketLimits,
    block: suspend DefaultWebSocketSession.() -> T,
): T = untrustedWebSocket(url, bearerToken, limits, WebSocketNetwork.platform(), block)

@OptIn(InternalAPI::class)
internal suspend fun <T> untrustedWebSocket(
    url: String,
    bearerToken: String,
    limits: WebSocketLimits,
    network: WebSocketNetwork,
    block: suspend DefaultWebSocketSession.() -> T,
): T {
    val target = WebSocketTarget.parse(url)
    require(bearerToken.isHeaderSafe()) { "bearer token is not a valid header value" }
    val upgraded = handshake(target, bearerToken, limits, network)
    // The session's coroutines are children of the caller, so cancelling it cancels them; a
    // supervisor with a silent handler, so a socket failure reaches the caller through `incoming`
    // (where Ktor's codec puts it) instead of as an uncaught exception, which on Android kills the app.
    val connection = SupervisorJob(currentCoroutineContext().job)
    val context = Dispatchers.IO + connection + CoroutineName("untrusted-ws") + CoroutineExceptionHandler { _, _ -> }
    try {
        val channels = WebSocketChannelsConfig().apply { incoming = bounded(limits.incomingFrames) }
        val input = upgraded.input.toByteReadChannel(context)
        val socketOut = upgraded.socket.getOutputStream()
        val output = CoroutineScope(context).reader { channel.copyTo(socketOut) }.channel
        val raw = RawWebSocket(input, output, limits.maxFrameBytes, masking = true, context, channels)
        val session = DefaultWebSocketSession(raw, limits.pingIntervalMillis, limits.pingIntervalMillis * 2, channels)
        session.start()
        try {
            return session.block()
        } finally {
            // As Ktor's own `webSocket {}` does: say goodbye, then stop reading. Bounded, because a
            // peer that stopped reading would otherwise hold the close frame, and this, forever.
            withContext(NonCancellable) { withTimeoutOrNull(CLOSE_GRACE_MS) { session.close() } }
            session.incoming.cancel()
        }
    } finally {
        connection.cancel()
        upgraded.socket.close()
    }
}

/** Grace for the closing handshake before the socket is simply dropped. */
private const val CLOSE_GRACE_MS = 1_000L

/**
 * Largest response head read during a handshake: status line and headers of the upgrade answer, or
 * of a proxy's reply to `CONNECT`. A real one is a few hundred bytes; OkHttp allows 256 KiB for any
 * response, and nothing here needs more than a sliver of that.
 */
internal const val MAX_HANDSHAKE_HEAD_BYTES = 16 * 1024

private const val HANDSHAKE_TIMED_OUT = "server did not finish the handshake in time"

private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/** Where a socket's TLS and proxy choice come from: the platform's, or a test's own. */
internal class WebSocketNetwork(val tls: SSLSocketFactory, val proxies: ProxySelector?) {
    companion object {
        fun platform() = WebSocketNetwork(SSLSocketFactory.getDefault() as SSLSocketFactory, ProxySelector.getDefault())
    }
}

private fun String.isHeaderSafe(): Boolean = all { it in ' '..'~' }

/** A socket past its upgrade, and its input with whatever arrived behind the upgrade answer put back in front. */
private class Upgraded(val socket: Socket, val input: InputStream)

internal class WebSocketTarget(val secure: Boolean, val host: String, val port: Int, val pathAndQuery: String) {
    /** `Host` header value: the port only when it is not the scheme's own, an IPv6 literal in brackets. */
    val hostHeader: String
        get() {
            val name = if (':' in host) "[$host]" else host
            return if (port == (if (secure) 443 else 80)) name else "$name:$port"
        }

    companion object {
        fun parse(url: String): WebSocketTarget {
            val parsed = Url(url)
            val secure = when (parsed.protocol) {
                URLProtocol.WSS -> true
                URLProtocol.WS -> false
                else -> throw IllegalArgumentException("not a WebSocket URL: ${parsed.protocol.name}")
            }
            val path = parsed.encodedPathAndQuery.ifEmpty { "/" }
            require(path.isHeaderSafe()) { "WebSocket path is not a valid request target" }
            // Url keeps an IPv6 literal bracketed; the socket wants it bare, the Host header re-adds them.
            return WebSocketTarget(secure, parsed.host.removePrefix("[").removeSuffix("]"), parsed.port, path)
        }
    }
}

/**
 * Connect, TLS and upgrade under one deadline. Blocking socket calls are neither cancellable nor
 * bounded as a whole — a socket timeout is per read, and a TLS handshake fed a byte at a time would
 * never hit it — so a watchdog closes the socket when the deadline passes or the caller is cancelled,
 * whichever comes first.
 */
private suspend fun handshake(
    target: WebSocketTarget,
    bearerToken: String,
    limits: WebSocketLimits,
    network: WebSocketNetwork,
): Upgraded = coroutineScope {
    val deadline = Deadline(limits.handshakeTimeoutMillis)
    val proxy = network.proxies.proxyFor(target)
    val tcp = if (proxy.type() == Proxy.Type.SOCKS) Socket(proxy) else Socket()
    val handedOff = AtomicBoolean(false)
    val expired = AtomicBoolean(false)
    val watchdog = launch {
        try {
            delay(limits.handshakeTimeoutMillis)
            expired.set(true)
        } finally {
            if (!handedOff.get()) tcp.close()
        }
    }
    var upgraded: Upgraded? = null
    try {
        runInterruptible(Dispatchers.IO) {
            val socket = connect(tcp, target, proxy, deadline, network.tls)
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            socket.getOutputStream().apply {
                write(upgradeRequest(target, key, bearerToken).encodeToByteArray())
                flush()
            }
            val head = readHead(socket, deadline)
            checkUpgrade(head, key)
            socket.soTimeout = 0 // from here on the pinger decides when the peer is gone
            handedOff.set(true)
            Upgraded(socket, SequenceInputStream(ByteArrayInputStream(head.rest), socket.getInputStream()))
        }.also { upgraded = it }
    } catch (e: IOException) {
        // The watchdog's close surfaces as "Socket closed" from the blocked call: a cancelled
        // caller must see its cancellation, and an expired one the timeout, not a vague failure.
        ensureActive()
        if (expired.get()) throw SocketTimeoutException(HANDSHAKE_TIMED_OUT).apply { initCause(e) }
        throw e
    } finally {
        if (upgraded == null) tcp.close()
        watchdog.cancel()
    }
}

private fun ProxySelector?.proxyFor(target: WebSocketTarget): Proxy {
    val scheme = if (target.secure) "https" else "http"
    return this?.select(URI(scheme, null, target.host, target.port, null, null, null))?.firstOrNull() ?: Proxy.NO_PROXY
}

private fun connect(tcp: Socket, target: WebSocketTarget, proxy: Proxy, deadline: Deadline, tls: SSLSocketFactory): Socket {
    when (proxy.type()) {
        Proxy.Type.HTTP -> {
            // The platform selector hands the proxy out unresolved; a plain socket cannot connect to that.
            val address = proxy.address() as InetSocketAddress
            val resolved = if (address.isUnresolved) InetSocketAddress(address.hostString, address.port) else address
            tcp.connect(resolved, deadline.remaining(CONNECT_TIMEOUT_MS))
            tunnel(tcp, target, deadline)
        }
        // Unresolved, so a SOCKS proxy resolves the name: what a proxied network expects.
        Proxy.Type.SOCKS -> tcp.connect(InetSocketAddress.createUnresolved(target.host, target.port), deadline.remaining(CONNECT_TIMEOUT_MS))
        else -> tcp.connect(InetSocketAddress(target.host, target.port), deadline.remaining(CONNECT_TIMEOUT_MS))
    }
    if (!target.secure) return tcp
    val socket = tls.createSocket(tcp, target.host, target.port, true) as SSLSocket
    socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    socket.soTimeout = deadline.remaining()
    socket.startHandshake()
    return socket
}

/** Opens a `CONNECT` tunnel through the HTTP proxy [tcp] is connected to. */
private fun tunnel(tcp: Socket, target: WebSocketTarget, deadline: Deadline) {
    val authority = "${if (':' in target.host) "[${target.host}]" else target.host}:${target.port}"
    tcp.getOutputStream().apply {
        write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n".encodeToByteArray())
        flush()
    }
    val head = readHead(tcp, deadline)
    if (head.status !in 200..299) throw IOException("proxy refused the tunnel (${head.status})")
    // A proxy speaks only after the tunnel is up once the client does, so nothing may follow its reply.
    if (head.rest.isNotEmpty()) throw IOException("proxy sent data ahead of the tunnel")
}

private fun upgradeRequest(target: WebSocketTarget, key: String, bearerToken: String) = buildString {
    append("GET ").append(target.pathAndQuery).append(" HTTP/1.1\r\n")
    append("Host: ").append(target.hostHeader).append("\r\n")
    append("Upgrade: websocket\r\n")
    append("Connection: Upgrade\r\n")
    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
    append("Sec-WebSocket-Version: 13\r\n")
    append("Authorization: Bearer ").append(bearerToken).append("\r\n")
    append("\r\n")
}

private fun checkUpgrade(head: ResponseHead, key: String) {
    if (head.status != 101) throw WebSocketUpgradeRefusedException(head.status)
    val expected = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest("$key$WEBSOCKET_GUID".encodeToByteArray()),
    )
    // RFC 6455 §4.1: the client fails the connection unless the server proves it read this key —
    // otherwise any 101 (a misconfigured proxy, a cached answer) would pass for a WebSocket.
    if (!head.header("upgrade").equals("websocket", ignoreCase = true) ||
        head.header("connection")?.split(',')?.none { it.trim().equals("upgrade", ignoreCase = true) } != false ||
        head.header("sec-websocket-accept") != expected
    ) {
        throw IOException("server answered the upgrade without accepting it")
    }
}

internal class ResponseHead(val status: Int, private val headers: Map<String, String>, val rest: ByteArray) {
    fun header(name: String): String? = headers[name]
}

/**
 * Reads a response head, up to the blank line that ends it, from [socket]: never more than
 * [MAX_HANDSHAKE_HEAD_BYTES] of it, never past [deadline]. Bytes that arrived behind the head are
 * handed back in [ResponseHead.rest], so the frames that follow are not lost.
 */
internal fun readHead(socket: Socket, deadline: Deadline): ResponseHead {
    val input = socket.getInputStream()
    val buffer = ByteArray(MAX_HANDSHAKE_HEAD_BYTES + 1)
    var filled = 0
    while (true) {
        val end = indexOfBlankLine(buffer, filled)
        if (end >= 0) return parseHead(buffer.copyOfRange(0, end), buffer.copyOfRange(end + BLANK_LINE.size, filled))
        if (filled > MAX_HANDSHAKE_HEAD_BYTES) throw ResponseHeadTooLargeException(MAX_HANDSHAKE_HEAD_BYTES)
        socket.soTimeout = deadline.remaining()
        val read = try {
            input.read(buffer, filled, buffer.size - filled)
        } catch (e: SocketTimeoutException) {
            throw SocketTimeoutException(HANDSHAKE_TIMED_OUT).apply { initCause(e) }
        }
        if (read == -1) throw IOException("server closed the connection during the handshake")
        filled += read
    }
}

private fun indexOfBlankLine(buffer: ByteArray, filled: Int): Int {
    for (i in 0..filled - BLANK_LINE.size) {
        if (BLANK_LINE.indices.all { buffer[i + it] == BLANK_LINE[it] }) return i
    }
    return -1
}

private val BLANK_LINE = "\r\n\r\n".encodeToByteArray()

private fun parseHead(head: ByteArray, rest: ByteArray): ResponseHead {
    val lines = head.decodeToString().split("\r\n")
    // "HTTP/1.1 101 Switching Protocols": the code is the second token, and the only part read.
    val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
        ?: throw IOException("malformed status line")
    val headers = lines.drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }.toMap()
    return ResponseHead(status, headers, rest)
}

/** A point in time a handshake must finish by. */
internal class Deadline(timeoutMillis: Long) {
    private val at = System.nanoTime() + timeoutMillis * 1_000_000

    /** Milliseconds left, for a socket timeout: at least 1, since 0 would mean "wait forever". */
    fun remaining(cap: Long = Long.MAX_VALUE): Int {
        val left = (at - System.nanoTime()) / 1_000_000
        if (left <= 0) throw SocketTimeoutException(HANDSHAKE_TIMED_OUT)
        return left.coerceAtMost(cap).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }
}
