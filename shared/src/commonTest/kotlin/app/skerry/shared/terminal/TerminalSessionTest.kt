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
    fun `state becomes closed when channel output completes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        channel.eof()

        assertEquals(TerminalState.Closed, session.state.value)
        scope.cancel()
    }

    @Test
    fun `close closes the channel and moves to closed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val channel = FakeShellChannel()
        val session = ShellTerminalSession(channel, scope)

        session.close()

        assertTrue(channel.closed)
        assertEquals(TerminalState.Closed, session.state.value)
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

/** Управляемый вручную фейк-канал: эмиссия и EOF под контролем теста. */
private class FakeShellChannel : ShellChannel {
    val writes = mutableListOf<ByteArray>()
    val resizes = mutableListOf<PtySize>()
    var closed = false
        private set

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    private var collected = false

    override val isOpen: Boolean get() = !closed

    override val output: Flow<ByteArray> = flow {
        check(!collected) { "second collector" }
        collected = true
        for (chunk in emissions) emit(chunk)
    }

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    fun eof() {
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
