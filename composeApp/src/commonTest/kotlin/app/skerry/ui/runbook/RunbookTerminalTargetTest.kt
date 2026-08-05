package app.skerry.ui.runbook

import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.STEP_MARK_OSC
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.terminal.TerminalScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam between a run and a live terminal. Everything else in the runbook tests fakes
 * [RunbookTarget]; here the real bindings of [runbookTarget] are driven by a real
 * [TerminalScreenState], so a probe declared to the wrong function — or a report the terminal never
 * hands over — is caught by something other than a live session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookTerminalTargetTest {

    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    @Test
    fun `a step declared through the target is reported back through it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val terminal = TerminalScreenState(session, scope)
        val target = runbookTarget("tab-1", terminal, controller = null)
        val token = RunbookMarker.token("run", 0)

        target.expectStep(token, RunbookMarker.echoFragments("ls", token))
        val before = target.outputVersion()
        session.emit(
            RunbookMarker.probeLine("ls", token) + "\r\n" +
                "$esc]$STEP_MARK_OSC;$token;$bel" + "total 4\r\n" + "$esc]$STEP_MARK_OSC;$token;0$bel",
        )

        val mark = target.takeMark(token)
        assertEquals(0, mark?.exitCode)
        assertEquals("total 4", mark?.output)
        assertNull(target.takeMark(token), "a report is handed over once")
        assertTrue(target.outputVersion() > before, "the watchdog must see the host talking")
        assertTrue(terminal.output.contains("ls"), terminal.output)
        assertTrue(!terminal.output.contains("printf"), terminal.output)
        scope.cancel()
    }
}

/** Minimal session: output on demand, nothing else this test needs. */
private class FakeSession : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = flow {
        for (chunk in emissions) emit(chunk)
    }

    suspend fun emit(text: String) {
        emissions.send(text.encodeToByteArray())
    }

    override suspend fun send(data: ByteArray) {}

    override suspend fun resize(size: PtySize) {}

    override suspend fun close() {
        _state.value = TerminalState.Closed()
        emissions.close()
    }
}
