package app.skerry.shared.agent

import app.skerry.shared.io.PrivateConfig
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Exposes the built-in agent to other programs on this machine through a unix socket, the
 * `SSH_AUTH_SOCK` protocol every OpenSSH tool speaks. With it, `ssh`, `git` or `scp` in the user's
 * own terminal can authenticate with a key that never leaves Skerry's vault.
 *
 * Desktop only, and only where the filesystem can express POSIX permissions ([isSupported]): the
 * socket's access control IS its directory mode. Anything that can open the socket can make the
 * agent sign, so the socket lives alone in a 0700 directory and the socket file itself is set to
 * 0600 — the same protection OpenSSH's own agent relies on. On Windows the OpenSSH agent is a named
 * pipe instead, which this does not implement, so the feature reports itself unavailable rather
 * than opening something weaker.
 *
 * Off by default; started only when the user asks for it in Settings → SSH agent.
 */
class UnixSocketSshAgent(
    private val directory: Path,
    private val service: SshAgentService,
    parentScope: CoroutineScope,
    // Defaults to the agent's bounded slice of the IO pool ([agentDispatcher]); injectable so a
    // test gets threads of its own instead of queueing behind another test's parked readers.
    dispatcher: CoroutineDispatcher = agentDispatcher,
) {
    // Own child scope: accept() and the per-connection read loops are blocking, and cancelling this
    // scope must not take the caller's scope with it.
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)

    // Connections being served right now. Any local process can open them, and each parks a thread
    // in a blocking read, so the listener needs its own ceiling as the forwarder has. The channels
    // are held (not just counted) because cancelling the serving coroutine is not enough to end a
    // read that is already blocked — closing the channel underneath it is what unblocks it.
    private val openConnections = AtomicInteger()
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()

    private var channel: ServerSocketChannel? = null
    private var selector: Selector? = null
    private var acceptJob: Job? = null

    // Guards the handover of an accepted connection into [clients]. A client can be connected (the
    // kernel completes a unix-socket connect before anything calls accept) while stop() is running:
    // without this, it would be registered after stop() emptied the set and served on past the
    // switch-off, holding a thread in a blocking read for the rest of the app's life.
    private val lock = Any()
    private var listening = false

    // Which round of start()/stop() we are in. A connection accepted by the previous round's loop
    // could otherwise be handed to a socket the user has since switched off and on again, and be
    // served as if it belonged to the new one.
    private var generation = 0

    /** Path of the socket while it is listening, `null` when stopped. */
    var socketPath: Path? = null
        private set

    /**
     * Bind the socket and start accepting. Idempotent: a second call while listening is a no-op.
     * @throws IOException the socket could not be bound (stale socket held by a live process,
     *   unwritable directory, platform without unix sockets)
     */
    fun start(): Path {
        socketPath?.let { return it }
        // 0700 on the directory is what keeps other users out; do it before the socket exists, so
        // there is no window where the socket sits in a world-traversable directory.
        PrivateConfig.ensureDir(directory)
        val path = directory.resolve(SOCKET_NAME)
        // A socket file left behind by a crash would make bind() fail with "address already in
        // use", so it has to go — but only once it is certain nobody is listening on it. A second
        // running copy of Skerry shares this path, and unlinking its socket would leave it serving
        // an unreachable inode while new clients silently landed on a different key set.
        if (Files.exists(path)) {
            if (isAlive(path)) throw IOException("another SSH agent is already listening on this socket")
            Files.deleteIfExists(path)
        }
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            server.bind(UnixDomainSocketAddress.of(path))
            PrivateConfig.harden(path)
        } catch (e: IOException) {
            runCatching { server.close() }
            throw e
        }
        // Accept through a selector rather than a blocking accept(): stop() then has a wakeup() that
        // is guaranteed to end the loop. A thread parked in a blocking accept() is only woken as a
        // side effect of closing the channel, and while it sits there the listening descriptor stays
        // open — a client that connected but had not been accepted yet would keep waiting for an
        // answer from an agent that is already switched off.
        val selector = Selector.open()
        try {
            server.configureBlocking(false)
            server.register(selector, SelectionKey.OP_ACCEPT)
        } catch (e: IOException) {
            runCatching { selector.close() }
            runCatching { server.close() }
            throw e
        }
        channel = server
        this.selector = selector
        socketPath = path
        val round = synchronized(lock) {
            listening = true
            ++generation
        }
        acceptJob = scope.launch { accept(server, selector, round) }
        return path
    }

    /** Stop listening, drop the socket file and end every connection being served. Idempotent. */
    fun stop() {
        val path = socketPath
        socketPath = null
        acceptJob = null
        // Cancel the CHILDREN, not the scope: a cancelled scope could never be restarted, and the
        // user can switch the socket back on.
        scope.coroutineContext.cancelChildren()
        // Then close what those coroutines are blocked on. A client that keeps its connection open
        // without sending anything (a long-lived `ssh`) sits in a read that cancellation alone does
        // not end, and would hold a thread and an fd for the rest of the app's life.
        synchronized(lock) {
            listening = false
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        // wakeup() ends the select the accept loop sits in (cancellation alone does not), and closing
        // the listening channel releases its descriptor, which resets whatever the kernel had queued
        // behind it — a client that connected without being accepted gets an answer, not a hang.
        selector?.let { runCatching { it.wakeup() } }
        runCatching { channel?.close() }
        runCatching { selector?.close() }
        channel = null
        selector = null
        path?.let { runCatching { Files.deleteIfExists(it) } }
    }

    private suspend fun accept(server: ServerSocketChannel, selector: Selector, round: Int) {
        while (true) {
            try {
                runInterruptible { selector.select() }
            } catch (e: IOException) {
                return // selector or channel closed by stop()
            }
            selector.selectedKeys().clear()
            while (true) {
                // Non-blocking: null means the batch is drained and we go back to select().
                val client = try {
                    server.accept() ?: break
                } catch (e: IOException) {
                    return // the listening channel is gone
                }
                val accepted = synchronized(lock) {
                    if (!listening || generation != round || openConnections.get() >= MAX_CONNECTIONS) false
                    else {
                        openConnections.incrementAndGet()
                        clients += client
                        true
                    }
                }
                if (!accepted) {
                    // Over the ceiling, or this round is over (socket switched off, possibly back
                    // on since) — either way the client is closed, not served.
                    runCatching { client.close() }
                    if (synchronized(lock) { !listening || generation != round }) return
                    continue
                }
                scope.launch { serve(client) }
            }
        }
    }

    private suspend fun serve(client: SocketChannel) {
        try {
            serveSshAgent(
                Channels.newInputStream(client),
                Channels.newOutputStream(client),
                service,
                SshAgentOrigin.LocalSocket,
            )
        } finally {
            openConnections.decrementAndGet()
            clients -= client
            runCatching { client.close() }
        }
    }

    /** Is somebody already serving this socket? A refused connection means the file is stale. */
    private fun isAlive(path: Path): Boolean = runCatching {
        SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
    }.getOrDefault(false)

    companion object {
        private const val SOCKET_NAME = "agent.sock"

        /** Connections served at once; see [openConnections]. */
        private const val MAX_CONNECTIONS = 8

        /**
         * Whether a unix socket with owner-only permissions can be created here. False on
         * filesystems without POSIX modes (Windows), where the socket could not be protected.
         */
        val isSupported: Boolean
            get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    }
}
