package app.skerry.ui.terminal

import androidx.compose.ui.graphics.Color
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.ShellCommandMark
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shell integration marks as the UI layer consumes them (OSC 133/OSC 7 → [TerminalScreenState]):
 * what is published, what "the last command's output" means with and without integration, and the
 * selection a gutter click makes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellMarksUiTest {

    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    // Second copy of the session fake in this package (TerminalScreenStateTest holds the first);
    // the third makes it shared, per the duplication rule.
    private class FakeSession : TerminalSession {
        private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = _state
        private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }
        suspend fun emit(chunk: ByteArray) { emissions.send(chunk) }
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(size: PtySize) {}
        override suspend fun close() { emissions.close() }
    }

    /** A fed session with marks on it: one ok command with output, one failing command with none. */
    private suspend fun TestScope.marksSession(body: suspend (TerminalScreenState) -> Unit) {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        session.emit(
            (
                "$esc]133;A$bel" + "user@h $ " + "$esc]133;B$bel" + "ls\r\n" + "$esc]133;C$bel" +
                    "file1\r\n" + "$esc]133;D;0$bel" +
                    "$esc]133;A$bel" + "user@h $ " + "$esc]133;B$bel" + "false\r\n" + "$esc]133;C$bel" +
                    "$esc]133;D;1$bel" +
                    "$esc]7;file://web-1/home/deploy$bel"
                ).encodeToByteArray(),
        )
        try {
            body(state)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `marks and the working directory are published`() = runTest {
        marksSession { state ->
            assertEquals(2, state.commandMarks.size)
            assertEquals(0, state.commandMarks[0].exitCode)
            assertEquals(1, state.commandMarks[1].exitCode)
            assertEquals<String?>("/home/deploy", state.workingDirectory)
        }
    }

    @Test
    fun `a silent command is quoted as its bare exit code`() = runTest {
        marksSession { state ->
            // `false` printed nothing — its own emptiness is the fact, not the previous command's output.
            assertEquals("Exit code: 1", state.lastOutputWithExit())
        }
    }

    @Test
    fun `a command with output is quoted together with its exit code`() = runTest {
        marksSession { state ->
            val withOutput = state.commandMarks[0]
            assertEquals("file1", state.commandOutputSelection(withOutput)?.extract(state.screen))
        }
    }

    @Test
    fun `the ai quote pairs the exit code with its own command's output`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            session.emit(
                (
                    "$esc]133;A$bel" + "user@h $ " + "$esc]133;B$bel" + "ls\r\n" + "$esc]133;C$bel" +
                        "file1\r\n" + "$esc]133;D;0$bel"
                    ).encodeToByteArray(),
            )
            // End to end through the published marks, not a direct selection: this is the exact
            // string AssistantPanel/MobileAiBar hand the model.
            assertEquals("Exit code: 0\nfile1", state.lastOutputWithExit())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `marker colour and shape follow the exit code`() {
        val colors = MarkColors(ok = Color.Green, fail = Color.Red, dim = Color.Gray)
        val ok = ShellCommandMark(0, 0, null, null, null, null, null, null, 0)
        assertEquals(Color.Green, colors.of(ok))
        assertEquals(MarkerShape.CAPSULE, colors.shape(ok))
        val failed = ok.copy(exitCode = 1)
        assertEquals(Color.Red, colors.of(failed))
        assertEquals(MarkerShape.SQUARE, colors.shape(failed))
        val running = ok.copy(exitCode = null)
        assertEquals(Color.Gray, colors.of(running))
        assertEquals(MarkerShape.HOLLOW, colors.shape(running))
    }

    @Test
    fun `without integration the heuristic output carries no exit code`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val session = FakeSession()
            val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
            session.emit("user@h $ ls\r\nfile1\r\nuser@h $ ".encodeToByteArray())
            assertTrue(state.commandMarks.isEmpty())
            assertNull(state.lastFinishedCommand)
            assertEquals("user@h $ ls\nfile1", state.lastOutputWithExit())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a gutter click selects the command's output`() = runTest {
        marksSession { state ->
            val mark = state.commandMarks[0]
            state.selectCommandOutput(mark)
            assertEquals("file1", state.selectedText())
            assertNotNull(state.selection)
        }
    }

    @Test
    fun `a running command's selection reaches the live bottom row`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val session = FakeSession()
            val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
            session.emit(
                (
                    "$esc]133;A$bel" + "$ " + "$esc]133;B$bel" + "tail -f log\r\n" + "$esc]133;C$bel" +
                        "line one\r\nline two\r\n"
                    ).encodeToByteArray(),
            )
            // No D yet: the command is running, and its newest printed line is part of the output.
            val mark = state.commandMarks.single()
            state.selectCommandOutput(mark)
            assertEquals("line one\nline two", state.selectedText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `jumps find the nearest command above and below`() = runTest {
        marksSession { state ->
            val first = state.commandMarks[0].promptRow
            val second = state.commandMarks[1].promptRow
            assertEquals(first, state.commandMarkBefore(second)?.promptRow)
            assertEquals(second, state.commandMarkBefore(second + 1)?.promptRow)
            assertEquals(second, state.commandMarkAfter(first)?.promptRow)
            assertNull(state.commandMarkBefore(first))
            assertNull(state.commandMarkAfter(second))
        }
    }
}
