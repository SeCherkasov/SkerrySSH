package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What piles up while a write is in flight goes out as one write: every write is a flushed SSH
 * packet, and key repeat or mouse motion would otherwise send one per report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalOutboundBatchTest {

    @Test
    fun `writes queued behind a slow one go out together and in order`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = GatedSession()
        val state = TerminalScreenState(session, scope)

        state.send("a")
        testScheduler.runCurrent() // the first write is now in flight, held by the gate
        state.send("b")
        state.sendBytes(byteArrayOf(0x1b, 0x5b, 0x41))
        state.send("c")
        session.gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("a", "b\u001b[Ac"), session.sent)
        scope.cancel()
    }

    @Test
    fun `a batch stops at the cap and the rest follows in the next write`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = GatedSession()
        val state = TerminalScreenState(session, scope)
        val chunk = "x".repeat(OUTBOUND_BATCH_BYTES / 2)

        state.send("a")
        testScheduler.runCurrent()
        repeat(3) { state.send(chunk) }
        session.gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf(1, OUTBOUND_BATCH_BYTES, OUTBOUND_BATCH_BYTES / 2), session.sent.map { it.length })
        scope.cancel()
    }

    @Test
    fun `a write already past the cap goes out whole and alone`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = GatedSession()
        val state = TerminalScreenState(session, scope)
        val paste = "p".repeat(OUTBOUND_BATCH_BYTES + 7)

        state.send("a")
        testScheduler.runCurrent()
        state.send(paste)
        state.send("next")
        session.gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("a", paste, "next"), session.sent)
        scope.cancel()
    }

    @Test
    fun `a paste backed up behind a slow write does not cost the host its reply`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = GatedSession()
        val state = TerminalScreenState(session, scope)
        state.send("a")
        testScheduler.runCurrent()
        state.send("p".repeat(REPLY_BACKLOG_BYTES * 2))

        session.chunks.trySend("$ESC[6n".encodeToByteArray()) // the host asks where the cursor is
        testScheduler.runCurrent()
        session.gate.complete(Unit)
        testScheduler.runCurrent()

        assertTrue(session.sent.last().endsWith("$ESC[1;1R"), "the cursor report was dropped")
        scope.cancel()
    }

    @Test
    fun `replies stop queueing while the host does not read, typed input does not`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = GatedSession()
        val state = TerminalScreenState(session, scope)
        state.applyTerminalTheme(TerminalThemes.DEFAULT)
        state.send("a")
        testScheduler.runCurrent() // the writer is stuck on the first write: the host stopped reading

        // Each chunk asks for the whole palette: ~6 KiB of reply for 2 KiB of query.
        val volley = "$ESC]4;" + (0..255).joinToString(";") { "$it;?" } + "$ESC\\"
        repeat(100) { session.chunks.trySend(volley.encodeToByteArray()) }
        testScheduler.runCurrent()
        state.send("typed")
        session.gate.complete(Unit)
        testScheduler.runCurrent()

        val written = session.sent.joinToString("")
        assertTrue(written.length <= REPLY_BACKLOG_BYTES + volley.length * 4, "replies queued past the cap: ${written.length}")
        assertTrue(written.endsWith("typed"), "typed input must never be dropped")
        scope.cancel()
    }
}

private const val ESC = "\u001b"

/** Session whose first write waits for [gate]; records every write. */
private class GatedSession : TerminalSession {
    val gate = CompletableDeferred<Unit>()
    val sent = mutableListOf<String>()
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = chunks.receiveAsFlow()
    override suspend fun send(data: ByteArray) {
        if (sent.isEmpty()) gate.await()
        sent += data.decodeToString()
    }
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}
