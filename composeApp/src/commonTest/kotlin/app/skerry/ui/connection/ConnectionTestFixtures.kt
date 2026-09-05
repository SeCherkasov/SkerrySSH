@file:OptIn(ExperimentalCoroutinesApi::class)

package app.skerry.ui.connection

// Fakes and the controller fixture shared by the connection tests. They live here rather than in
// whichever test class happened to define them first, so no test file depends on another being
// kept around.

import kotlinx.coroutines.ExperimentalCoroutinesApi
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vnc.VncAuth
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.vnc.VncQuality
import app.skerry.shared.vnc.VncSession
import app.skerry.shared.vnc.VncTransport
import app.skerry.shared.vnc.VncUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal val testTarget = SshTarget(host = "h", port = 22, username = "u")

internal fun TestScope.controllerWith(
    transport: SshTransport,
    maxReconnectAttempts: Int = 0,
    // No backoff by default (determinism). The reconnect-cancellation test sets a nonzero delay
    // so the attempt actually "hangs" on delay and can be cancelled (delay(0) doesn't suspend).
    reconnectDelayMillis: (Int) -> Long = { 0L },
    keepAlive: app.skerry.ui.keepalive.SessionKeepAliveBridge? = null,
): Pair<ConnectionController, CoroutineScope> {
    val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val controller = ConnectionController(
        transport = transport,
        scope = scope,
        newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
        maxReconnectAttempts = maxReconnectAttempts,
        reconnectDelayMillis = reconnectDelayMillis,
        keepAlive = keepAlive,
    )
    return controller to scope
}

/**
 * Transport that returns a predefined sequence of outcomes (success/failure) — one per
 * [connect] call. Counts calls and records targets/credentials for reconnect verification.
 */
internal class ScriptedTransport(private val outcomes: List<Result<SshConnection>>) : SshTransport {
    var connectCalls = 0
        private set
    val targets = mutableListOf<SshTarget>()
    val auths = mutableListOf<SshAuth>()

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        val index = connectCalls++
        targets += target
        auths += auth
        return outcomes[index].getOrThrow()
    }
}

/** Fake transport: optional delay via a gate, otherwise success or failure. */
internal class FakeSshTransport(
    private val connection: SshConnection? = null,
    private val error: Throwable? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        gate?.await()
        error?.let { throw it }
        return connection!!
    }
}

internal class FakeSshConnection(
    private val channel: ShellChannel,
    private val sftp: SftpClient? = null,
) : SshConnection {
    var disconnected = false
        private set
    var openSftpCalls = 0
        private set
    var roundTrips = 0
        private set

    /** Scripted ping outcome: `null` models a dead link (keepalives unanswered). */
    var roundTripResult: Long? = 7L

    override val isConnected: Boolean get() = !disconnected
    override suspend fun measureRoundTrip(): Long? {
        roundTrips++
        return roundTripResult
    }
    /** Metrics polling round-trips: parsable output, so the poller keeps running while connected. */
    var execCalls = 0
    override suspend fun exec(command: String): ExecResult {
        execCalls++
        return ExecResult(0, "cpu  1 0 1 8 0 0 0 0\n@MEM\nMem: 400 200 200\n@DISK\n/dev/sda1 100 87 13 87% /", "")
    }
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = channel
    override suspend fun openSftp(): SftpClient {
        openSftpCalls++
        return sftp ?: throw UnsupportedOperationException()
    }
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() {
        disconnected = true
    }
}

internal class FakeShellChannel : ShellChannel {
    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    private var eof = false

    /** What the session sent, decoded — the only way to tell one answer to a prompt from another. */
    val written = mutableListOf<String>()
    override val isOpen: Boolean = true
    override val endedWithEof: Boolean get() = eof
    override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    /** Normal shell exit (`exit`): channel EOF — endedWithEof=true. */
    fun exit() {
        eof = true
        emissions.close()
    }

    override suspend fun write(data: ByteArray) {
        written += data.decodeToString()
    }
    override suspend fun resize(size: PtySize) {}
    /** Server/transport-side drop: the channel ends WITHOUT EOF (reconnect candidate). */
    override suspend fun close() {
        emissions.close()
    }

}

/** VNC transport that returns a fresh fake session on each connect; list is used to verify closes. */
internal class FakeVncTransport : VncTransport {
    val sessions = mutableListOf<FakeVncSession>()
    override suspend fun connect(target: SshTarget, auth: VncAuth): VncSession =
        FakeVncSession().also { sessions += it }
}

internal class FakeVncSession : VncSession {
    var closed = false
        private set

    override val serverName = "desk"
    override val framebuffer = RemoteFramebuffer(1, 1)

    // Never emits: keeps the read loop parked (like a quiet server) until the scope is cancelled.
    override val updates: Flow<VncUpdate> = flow { awaitCancellation() }

    override suspend fun sendPointer(event: VncPointerEvent) {}
    override suspend fun sendKey(keySym: Long, down: Boolean) {}
    override suspend fun sendClientCutText(text: String) {}
    override suspend fun requestUpdate(incremental: Boolean) {}
    override suspend fun setQuality(quality: VncQuality) {}
    override suspend fun setDesktopSize(width: Int, height: Int) {}
    override suspend fun setLocalCursor(enabled: Boolean) {}
    override suspend fun close() {
        closed = true
    }
}
