package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A frame the application brackets with mode 2026 is published whole: a TUI redrawing the screen
 * in several writes must not be drawn half old, half new.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSynchronizedOutputTest {

    private val esc = 27.toChar().toString()

    private fun TerminalScreenState.text(row: Int): String =
        screen.getOrNull(row)?.joinToString("") { it.text }?.trimEnd().orEmpty()

    private fun TestScope.setUp(): Triple<CoroutineScope, ChunkSession, TerminalScreenState> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = ChunkSession()
        val state = TerminalScreenState(session, scope, nowMillis = { testScheduler.currentTime })
        return Triple(scope, session, state)
    }

    private fun TestScope.emit(session: ChunkSession, text: String) {
        session.chunks.trySend(text.encodeToByteArray())
        testScheduler.runCurrent()
    }

    @Test
    fun `a frame is not published until the application closes it`() = runTest {
        val (scope, session, state) = setUp()
        emit(session, "old")
        assertEquals("old", state.text(0))

        testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS * 2)
        emit(session, "$esc[?2026h$esc[H")
        testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS * 2)
        emit(session, "new")
        testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS * 2)
        testScheduler.runCurrent()
        assertEquals("old", state.text(0))

        emit(session, "$esc[?2026l")
        testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS)
        testScheduler.runCurrent()
        assertEquals("new", state.text(0))
        scope.cancel()
    }

    @Test
    fun `a frame never closed is published after the timeout`() = runTest {
        val (scope, session, state) = setUp()
        emit(session, "$esc[?2026hstuck")
        testScheduler.advanceTimeBy(SYNCHRONIZED_OUTPUT_TIMEOUT_MS - 1)
        testScheduler.runCurrent()
        assertEquals("", state.text(0))

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals("stuck", state.text(0))
        scope.cancel()
    }

    @Test
    fun `frames reopened back to back are still published within the timeout`() = runTest {
        val (scope, session, state) = setUp()
        // A dashboard redrawing every 50 ms, each frame closed and the next opened in one write.
        emit(session, "$esc[?2026h")
        for (frame in 1..8) {
            testScheduler.advanceTimeBy(50)
            emit(session, "$esc[H$frame$esc[?2026l$esc[?2026h")
        }
        testScheduler.runCurrent()
        // 400 ms of back-to-back frames: the screen must not still show nothing.
        assertEquals(true, state.text(0).isNotEmpty(), "a frame should have been published by now")
        scope.cancel()
    }

    @Test
    fun `output after a timed-out frame is drawn at the usual pace`() = runTest {
        val (scope, session, state) = setUp()
        emit(session, "$esc[?2026ha")
        testScheduler.advanceTimeBy(SYNCHRONIZED_OUTPUT_TIMEOUT_MS + 1)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS)
        emit(session, "b")
        testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS)
        testScheduler.runCurrent()
        assertEquals("ab", state.text(0))
        scope.cancel()
    }
}

/** Session fed chunk by chunk from the test. */
private class ChunkSession : TerminalSession {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = chunks.receiveAsFlow()
    override suspend fun send(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}
