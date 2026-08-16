package app.skerry.shared.ssh

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder

/**
 * Pumps a stream to EOF, flushing after each chunk (needed for interactive TCP), and adds bytes
 * pumped to [counter]. Counted right after reading (before writing) so the counter is never
 * behind data the receiver can already see — telemetry tests rely on this.
 */
private fun pump(input: InputStream, output: OutputStream, counter: AtomicLong) {
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        counter.addAndGet(n.toLong())
        output.write(buf, 0, n)
        output.flush()
    }
}

/**
 * Bidirectional pump between an accepted local connection ([near]) and an SSH channel ([far]):
 * the upstream direction (near->far) runs on a separate daemon thread, downstream (far->near) on
 * the caller. [up] counts bytes sent into the channel (to the server), [down] counts bytes
 * received from it. Either direction ending half-closes the other side's write end, so the peer
 * sees EOF and finishes its own direction. Returns once both directions end.
 *
 * Both half-closes are needed, and the far->near one is the one with teeth: a destination that
 * hangs up first (a close-delimited HTTP/1.0 body, or the SSH transport dropping) ends the
 * downstream pump, while upstream is still parked in a read on a local peer that has no reason to
 * write or close. Without the shutdown that peer waits for bytes forever and this tunnel's thread,
 * socket and channel stay in the forward's live set for the rest of the session.
 */
private fun tunnel(near: Socket, far: Channel, up: AtomicLong, down: AtomicLong, name: String) {
    val upstream = thread(isDaemon = true, name = "$name-up") {
        runCatching { pump(near.getInputStream(), far.outputStream, up); far.outputStream.close() }
    }
    runCatching { pump(far.inputStream, near.getOutputStream(), down) }
    // Half-close succeeded: the peer has been told the far side is done and still owns its own
    // direction, so the wait stays unbounded. A `-R` forward's response, or an upload still in
    // flight, would otherwise be cut off mid-write when this returns and the caller closes both
    // ends. A peer that never closes is what `close()` on the forward is for.
    val halfClosed = runCatching { near.shutdownOutput() }.isSuccess
    if (halfClosed) upstream.join() else upstream.join(TUNNEL_JOIN_MILLIS)
}

/** How long [tunnel] waits for its upstream thread when the socket could not be half-closed. */
private const val TUNNEL_JOIN_MILLIS = 2000L

/**
 * Shared forward state: activity/pause flags, traffic counters, and the set of live resources of
 * open tunnels. Extracted so [AcceptingForward] (`-L`/`-D`, listener on our side) and
 * [SshjRemoteForward] (`-R`, listener on the server) don't duplicate telemetry and pause/close
 * choreography. All fields are thread-safe: written from tunnel threads, read from UI coroutines.
 */
internal class ForwardState {
    val active = AtomicBoolean(true)
    val paused = AtomicBoolean(false)
    val up = AtomicLong(0)
    val down = AtomicLong(0)
    val live: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

    /** Closes all live resources (already-open tunnels/channels); close errors are swallowed. */
    fun closeAll() {
        live.toList().forEach { runCatching { it.close() } }
    }
}

/**
 * Base for forwards with the listener on our side (`-L`, `-D`). Holds [serverSocket], runs accept
 * on a daemon thread, and starts [handle] on its own thread per connection. Carries pause (paused
 * connections are dropped immediately, the port stays bound) and traffic counters in [state],
 * populated by [handle] via [tunnel]. [close] closes the listener and all live tunnels.
 *
 * The accept thread is NOT started in the base constructor (otherwise [handle] could fire on a
 * not-yet-constructed subclass) — the subclass calls [startAccepting] at the end of its init.
 */
internal abstract class AcceptingForward(
    private val serverSocket: ServerSocket,
    private val threadName: String,
) : PortForward {

    protected val state = ForwardState()

    final override val boundPort: Int = serverSocket.localPort
    final override val isActive: Boolean get() = state.active.get() && !serverSocket.isClosed
    final override val isPaused: Boolean get() = state.paused.get()
    final override val bytesUp: Long get() = state.up.get()
    final override val bytesDown: Long get() = state.down.get()

    private lateinit var acceptor: Thread

    protected fun startAccepting() {
        acceptor = thread(isDaemon = true, name = "$threadName-$boundPort") {
            while (state.active.get() && !serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    // close() closes the listener to break this loop — that is the normal exit and
                    // `active`/`isClosed` already say so. Anything else (fd exhaustion, the OS
                    // dropping the listener) leaves both saying the forward is fine while nothing
                    // is being accepted, so take it down explicitly and let isActive report it.
                    // compareAndSet, not get-then-set: close() may be running concurrently, and
                    // exactly one of the two owns the teardown.
                    if (!serverSocket.isClosed && state.active.compareAndSet(true, false)) {
                        runCatching { serverSocket.close() }
                        state.closeAll()
                    }
                    break
                }
                // Paused: keep the port bound but drop the connection immediately, no tunnel raised.
                if (state.paused.get()) { runCatching { socket.close() }; continue }
                thread(isDaemon = true, name = "$threadName-conn-$boundPort") { handle(socket) }
            }
        }
    }

    /** Serves an accepted connection: opens an SSH channel to the destination and pumps bytes via [tunnel]. */
    protected abstract fun handle(socket: Socket)

    final override suspend fun pause() = withContext(Dispatchers.IO) { state.paused.set(true) }
    final override suspend fun resume() = withContext(Dispatchers.IO) { state.paused.set(false) }

    final override suspend fun close() = withContext(Dispatchers.IO) {
        // The accept loop claims the teardown itself when accept() fails for real, so losing this
        // race only means its work is already being done. The join still has to happen either way:
        // close() returning is the caller's signal that the listener is down and every live tunnel
        // is settled, and that guarantee must not hold only on the path where nothing went wrong.
        if (state.active.compareAndSet(true, false)) {
            runCatching { serverSocket.close() } // breaks accept
            state.closeAll() // tear down already-open tunnels
        }
        acceptor.join(CLOSE_JOIN_MILLIS)
        Unit
    }

    protected companion object {
        const val CLOSE_JOIN_MILLIS = 1000L
    }
}

/**
 * Local forward (`-L`): we accept connections on the listener ourselves and open a direct-tcpip
 * channel per connection to a fixed [destHost]:[destPort] (resolved by the server), then pump
 * bytes bidirectionally. A custom pump (instead of sshj's stock LocalPortForwarder, which hides
 * the stream internally) gives us traffic counters and pause support.
 */
internal class SshjLocalForward(
    private val client: SSHClient,
    serverSocket: ServerSocket,
    private val destHost: String,
    private val destPort: Int,
) : AcceptingForward(serverSocket, "skerry-local-forward") {

    init { startAccepting() }

    override fun handle(socket: Socket) {
        var channel: DirectConnection? = null
        state.live.add(socket)
        try {
            socket.tcpNoDelay = true
            channel = client.newDirectConnection(destHost, destPort)
            val ch = channel
            state.live.add(ch)
            tunnel(socket, ch, state.up, state.down, "skerry-local-$boundPort")
        } catch (e: Exception) {
            // Connection/channel drop — normal tunnel termination.
        } finally {
            channel?.let { state.live.remove(it); runCatching { it.close() } }
            state.live.remove(socket)
            runCatching { socket.close() }
        }
    }
}

/**
 * Dynamic forward (`-D`): runs a SOCKS5 server on the listener. Each connection performs the
 * SOCKS5 handshake ([Socks5]) and opens a direct-tcpip channel to the requested address, then
 * pumps bytes bidirectionally with traffic counting.
 */
internal class SshjDynamicForward(
    private val client: SSHClient,
    serverSocket: ServerSocket,
) : AcceptingForward(serverSocket, "skerry-socks") {

    init { startAccepting() }

    override fun handle(socket: Socket) {
        var channel: DirectConnection? = null
        state.live.add(socket)
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val target = Socks5.accept(input, output) ?: return // rejection already sent
            channel = try {
                client.newDirectConnection(target.host, target.port)
            } catch (e: IOException) {
                Socks5.replyFailure(output, Socks5.REP_CONNECTION_REFUSED)
                return
            }
            val ch = channel
            state.live.add(ch)
            Socks5.replySuccess(output)
            tunnel(socket, ch, state.up, state.down, "skerry-socks-$boundPort")
        } catch (e: Exception) {
            // Connection/channel drop — normal tunnel termination.
        } finally {
            channel?.let { state.live.remove(it); runCatching { it.close() } }
            state.live.remove(socket)
            runCatching { socket.close() }
        }
    }
}

/**
 * Remote forward (`-R`): the server holds the listener. sshj delivers each incoming connection to
 * our ConnectListener as a [Channel.Forwarded]; we open a local socket to [destHost]:[destPort]
 * and pump bytes bidirectionally with traffic counting and pause support. While paused, incoming
 * channels are closed immediately (no new tunnels). [close] cancels the server-side binding and
 * tears down live tunnels. Created via [open], which binds the forward and learns the assigned port.
 */
internal class SshjRemoteForward private constructor(
    private val forwarder: RemotePortForwarder,
    private val destHost: String,
    private val destPort: Int,
) : PortForward {

    private val state = ForwardState()
    private lateinit var forward: RemotePortForwarder.Forward

    override var boundPort: Int = 0
        private set

    override val isActive: Boolean get() = state.active.get()
    override val isPaused: Boolean get() = state.paused.get()
    override val bytesUp: Long get() = state.up.get()
    override val bytesDown: Long get() = state.down.get()

    private fun gotConnect(channel: Channel.Forwarded) {
        // Paused/torn down: reject the incoming channel (server sees a refusal) instead of silently closing it.
        if (!state.active.get() || state.paused.get()) {
            runCatching { channel.reject(OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED, "tunnel paused") }
            return
        }
        thread(isDaemon = true, name = "skerry-remote-conn-$boundPort") { handle(channel) }
    }

    private fun handle(channel: Channel.Forwarded) {
        state.live.add(channel)
        // Connect to the local destination first; on failure, reject the channel and bail.
        val socket = try {
            Socket(destHost, destPort).apply { tcpNoDelay = true }
        } catch (e: IOException) {
            runCatching { channel.reject(OpenFailException.Reason.CONNECT_FAILED, "destination unreachable") }
            state.live.remove(channel)
            runCatching { channel.close() }
            return
        }
        state.live.add(socket)
        try {
            // Confirm the forwarded channel open — without this the server won't start sending data.
            channel.confirm()
            // near = local destination socket, far = channel from the server: up = destination's
            // response into the channel (to the server), down = remote client data from the
            // channel to the destination.
            tunnel(socket, channel, state.up, state.down, "skerry-remote-$boundPort")
        } catch (e: Exception) {
            // Connection/channel drop — normal tunnel termination.
        } finally {
            state.live.remove(socket)
            runCatching { socket.close() }
            state.live.remove(channel)
            runCatching { channel.close() }
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) { state.paused.set(true) }
    override suspend fun resume() = withContext(Dispatchers.IO) { state.paused.set(false) }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!state.active.compareAndSet(true, false)) return@withContext
        runCatching { forwarder.cancel(forward) }
        state.closeAll()
        Unit
    }

    companion object {
        /** Binds the remote forward on the server with our ConnectListener and returns the [PortForward]. */
        fun open(
            forwarder: RemotePortForwarder,
            forwardSpec: RemotePortForwarder.Forward,
            destHost: String,
            destPort: Int,
        ): SshjRemoteForward {
            val pf = SshjRemoteForward(forwarder, destHost, destPort)
            val bound = forwarder.bind(
                forwardSpec,
                ConnectListener { channel -> pf.gotConnect(channel) },
            )
            pf.forward = bound
            pf.boundPort = bound.port
            return pf
        }
    }
}
