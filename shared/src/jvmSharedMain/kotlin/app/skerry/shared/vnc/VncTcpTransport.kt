package app.skerry.shared.vnc

import app.skerry.shared.ssh.SshTarget
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * TCP implementation of [VncTransport]: opens a socket, runs the RFB handshake, and exposes a live
 * [VncSocketSession]. Structurally the framebuffer sibling of `TelnetTransport` (java.net.Socket,
 * tcpNoDelay, blocking reads with cancel-by-close), but it produces framebuffer updates rather than
 * a terminal byte stream. No expect/actual: `java.net.Socket` is identical on desktop and Android.
 */
class VncTcpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val inflaterFactory: InflaterFactory = JvmInflaterFactory,
    private val challengeResponder: VncChallengeResponder = VncDesCipher,
    private val imageDecoder: VncImageDecoder? = platformVncImageDecoder(),
    // Injectable for tests (a fake server socket); prod uses a real Socket().
    private val openSocket: (host: String, port: Int) -> Socket = { host, port ->
        Socket().apply {
            connect(InetSocketAddress(host, port), connectTimeoutMillis)
            tcpNoDelay = true
        }
    },
) : VncTransport {
    override suspend fun connect(target: SshTarget, auth: VncAuth): VncSession =
        withContext(Dispatchers.IO) {
            val socket = openSocket(target.host, target.port)
            val session = VncSocketSession(socket, auth, inflaterFactory, challengeResponder, imageDecoder)
            try {
                session.handshake()
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
            session
        }
}

/**
 * A live RFB session over a connected [socket]. Reads run through a blocking [DataInputStream]
 * ([java.io.DataInputStream.readFully] is exactly the "read N bytes or throw" the codec needs);
 * writes go through a [BufferedOutputStream] under [writeLock] so input events, update requests and
 * handshake replies never interleave on the shared stream (same reasoning as `TelnetShellChannel`).
 *
 * Cancellation: the blocking socket read doesn't respond to coroutine cancellation, so — like
 * `StreamShellChannel` with `unblockReadOnCancel` — the collector's Job completion closes the
 * socket, dropping the hung read as an [IOException] that ends the loop.
 */
class VncSocketSession(
    private val socket: Socket,
    private val auth: VncAuth,
    inflaterFactory: InflaterFactory,
    challengeResponder: VncChallengeResponder,
    imageDecoder: VncImageDecoder?,
) : VncSession {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val out = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Mutex()

    override val framebuffer = VncFramebuffer(1, 1)

    private val source = VncSource { dst, offset, len -> input.readFully(dst, offset, len) }
    private val sink = VncSink { bytes -> writeRaw(bytes) }
    private val codec = RfbCodec(source, sink, framebuffer, inflaterFactory, challengeResponder, imageDecoder)

    private var _serverName = ""
    override val serverName: String get() = _serverName

    private val collected = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)

    /** Run the handshake (on the caller's IO context) and capture the desktop name. */
    suspend fun handshake() {
        _serverName = codec.handshake(auth)
    }

    override val updates: Flow<VncUpdate> = flow {
        check(collected.compareAndSet(false, true)) { "VncSession.updates supports only one collector" }
        while (true) {
            val update = try {
                codec.readMessage()
            } catch (e: CancellationException) {
                throw e // never swallow cooperative cancellation (collector left / socket closed)
            } catch (e: Exception) {
                // Any decode failure on the untrusted stream — socket IOException, a malformed-data
                // VncProtocolException, a corrupt-zlib DataFormatException, or an OOB from a crafted
                // rect — ends the session cleanly instead of escaping the flow and crashing the app.
                emit(VncUpdate.Closed(cleanExit = false))
                break
            }
            emit(update)
            when (update) {
                // Steady state: after applying an update, ask for the next incremental one.
                is VncUpdate.Region, is VncUpdate.Resize ->
                    codec.writeFramebufferUpdateRequest(true, 0, 0, framebuffer.width, framebuffer.height)
                is VncUpdate.Closed -> break
                else -> {}
            }
        }
    }.flowOn(Dispatchers.IO)
        // Close the socket when collection ends — including cancellation (e.g. the UI leaving, or a
        // `first {}`). This runs on the COLLECTOR side, so it fires even while the read loop is parked
        // in a blocking readFully; closing the socket unblocks that read (throws IOException) and lets
        // the producer coroutine finish. Closing from inside the producer via invokeOnCompletion would
        // deadlock: that handler only runs once the producer completes, which it can't until the read
        // unblocks — which needs the socket closed.
        .onCompletion { closeSocket() }

    override suspend fun sendPointer(event: VncPointerEvent) = withContext(Dispatchers.IO) {
        codec.writePointer(event)
    }

    override suspend fun sendKey(keySym: Long, down: Boolean) = withContext(Dispatchers.IO) {
        codec.writeKey(keySym, down)
    }

    override suspend fun sendClientCutText(text: String) = withContext(Dispatchers.IO) {
        codec.writeClientCutText(text)
    }

    override suspend fun requestUpdate(incremental: Boolean) = withContext(Dispatchers.IO) {
        codec.writeFramebufferUpdateRequest(incremental, 0, 0, framebuffer.width, framebuffer.height)
    }

    override suspend fun setQuality(quality: VncQuality) = withContext(Dispatchers.IO) {
        codec.setQuality(quality)
    }

    override suspend fun close() = withContext(Dispatchers.IO) { closeSocket() }

    private suspend fun writeRaw(bytes: ByteArray) = writeLock.withLock {
        out.write(bytes)
        out.flush()
    }

    private fun closeSocket() {
        if (closedFlag.compareAndSet(false, true)) runCatching { socket.close() }
    }
}
