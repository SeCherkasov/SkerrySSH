package app.skerry.shared.ssh

import app.skerry.shared.sftp.SftpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * A transport that counts its dials and can hold each one open ([gate]) — what a jump host asking
 * for a login looks like from the client: the dial does not finish until someone answers.
 */
internal class CountingTransport : SshTransport {
    var dials = 0
        private set
    val connections = mutableListOf<FakePooledConnection>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: Exception? = null
    var lastTarget: SshTarget? = null
    var lastAuth: SshAuth? = null

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        dials++
        lastTarget = target
        lastAuth = auth
        gate?.await()
        failWith?.let { throw it }
        return FakePooledConnection().also { connections += it }
    }
}

internal class FakePooledConnection : SshConnection {
    var connected = true
    var answers = true
    var roundTripGate: CompletableDeferred<Unit>? = null
    var disconnects = 0
        private set
    val shells = mutableListOf<FakeShell>()

    override val isConnected: Boolean get() = connected
    override val cipher: String get() = "aes256-gcm@openssh.com"

    override suspend fun measureRoundTrip(): Long? {
        roundTripGate?.await()
        return if (answers) 5L else null
    }
    override suspend fun exec(command: String): ExecResult = ExecResult(0, "", "")
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeShell().also { shells += it }
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()

    override suspend fun disconnect() {
        disconnects++
        connected = false
    }
}

/** A shell whose output the test emits by hand and whose input it reads back as text. */
internal class FakeShell : ShellChannel {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<String>()
    var failWrites = false

    override val isOpen: Boolean get() = true
    override val output: Flow<ByteArray> = out.consumeAsFlow()

    fun emit(text: String) {
        out.trySend(text.encodeToByteArray())
    }

    fun end() {
        out.close()
    }

    override suspend fun write(data: ByteArray) {
        if (failWrites) throw SshConnectionException("channel closed")
        written += data.decodeToString()
    }

    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = end()
}
