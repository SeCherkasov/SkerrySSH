package app.skerry.shared.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshConnectionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {

    @Test
    fun `forwards channel output to a subscriber`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        val received = mutableListOf<ByteArray>()
        scope.launch { session.output.collect { received += it } }

        channel.emit("ab".encodeToByteArray())
        channel.emit("cd".encodeToByteArray())

        assertEquals(2, received.size)
        assertContentEquals("ab".encodeToByteArray(), received[0])
        assertContentEquals("cd".encodeToByteArray(), received[1])
        scope.cancel()
    }

    @Test
    fun `forwards output to multiple subscribers`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        val a = mutableListOf<ByteArray>()
        val b = mutableListOf<ByteArray>()
        scope.launch { session.output.collect { a += it } }
        scope.launch { session.output.collect { b += it } }

        channel.emit("x".encodeToByteArray())

        assertContentEquals("x".encodeToByteArray(), a.single())
        assertContentEquals("x".encodeToByteArray(), b.single())
        scope.cancel()
    }

    @Test
    fun `does not drop output emitted before the subscriber attaches`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        // Shell banner arrives before the UI subscribes (real first-connect).
        channel.emit("banner\r\n".encodeToByteArray())

        val received = mutableListOf<ByteArray>()
        scope.launch { session.output.collect { received += it } }

        assertContentEquals("banner\r\n".encodeToByteArray(), received.single())
        scope.cancel()
    }

    @Test
    fun `send writes to the channel`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        session.send("ls -la\n".encodeToByteArray())

        assertContentEquals("ls -la\n".encodeToByteArray(), channel.writes.single())
        scope.cancel()
    }

    @Test
    fun `resize forwards to the channel`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        session.resize(PtySize(cols = 120, rows = 40))

        assertEquals(PtySize(cols = 120, rows = 40), channel.resizes.single())
        scope.cancel()
    }

    @Test
    fun `state starts open`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        assertEquals(TerminalState.Open, session.state.value)
        scope.cancel()
    }

    @Test
    fun `clean EOF closes the session with cleanExit true`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)
        scope.launch { session.output.collect {} } // channel collection starts on subscription

        channel.eof() // clean EOF (shell ran `exit`)

        // Clean shell exit → cleanExit=true: caller closes the session without reconnecting.
        assertEquals(TerminalState.Closed(cleanExit = true), session.state.value)
        scope.cancel()
    }

    @Test
    fun `close closes the channel and moves to closed without cleanExit`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        session.close()

        assertTrue(channel.closed)
        // Our close is not a clean shell exit: cleanExit=false.
        assertEquals(TerminalState.Closed(cleanExit = false), session.state.value)
        scope.cancel()
    }

    @Test
    fun `transport error closes session without crashing the scope and without cleanExit`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = ThrowingShellChannel()
        val session = ShellTerminalSession(channel, scope)
        scope.launch { session.output.collect {} } // channel collection starts on subscription

        // Output collection starts on subscription; a transport error (not cancellation) must
        // move the session to Closed (without cleanExit — a reconnect candidate) without killing scope.
        assertEquals(TerminalState.Closed(cleanExit = false), session.state.value)
        assertTrue(scope.isActive, "a transport error must not cancel the scope")
        scope.cancel()
    }

    @Test
    fun `send after close fails with connection exception`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        session.close()

        assertFailsWith<SshConnectionException> {
            session.send("late\n".encodeToByteArray())
        }
        scope.cancel()
    }
}

/** Manually driven fake channel: emission and EOF controlled by the test. */
private class FakeShellChannel : ShellChannel {
    val writes = mutableListOf<ByteArray>()
    val resizes = mutableListOf<PtySize>()
    var closed = false
        private set
    private var eof = false

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    private var collected = false

    override val isOpen: Boolean get() = !closed
    override val endedWithEof: Boolean get() = eof

    override val output: Flow<ByteArray> = flow {
        check(!collected) { "second collector" }
        collected = true
        for (chunk in emissions) emit(chunk)
    }

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    /** Clean channel EOF (server closed the shell itself via `exit`): sets endedWithEof before closing. */
    fun eof() {
        eof = true
        emissions.close()
    }

    override suspend fun write(data: ByteArray) {
        if (closed) throw SshConnectionException("channel closed")
        writes += data
    }

    override suspend fun resize(size: PtySize) {
        resizes += size
    }

    override suspend fun close() {
        closed = true
        emissions.close()
    }
}

/** Channel whose output immediately fails with a transport error — simulates a dropped connection. */
private class ThrowingShellChannel : ShellChannel {
    override val isOpen: Boolean get() = false
    override val output: Flow<ByteArray> = flow { throw SshConnectionException("transport down") }
    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}
