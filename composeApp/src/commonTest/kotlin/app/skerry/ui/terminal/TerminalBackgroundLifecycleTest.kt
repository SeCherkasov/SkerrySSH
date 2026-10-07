package app.skerry.ui.terminal

import app.skerry.shared.terminal.STEP_MARK_OSC
import app.skerry.shared.terminal.TerminalStepMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalBackgroundLifecycleTest {
    @Test
    fun `hidden step reports and modes advance before the render tail`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope,
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            terminal.expectStepMark("test")
            session.chunks.send("head".encodeToByteArray())
            testScheduler.runCurrent()
            val version = terminal.snapshotVersion
            testScheduler.advanceTimeBy(20)
            session.chunks.send(("\u001b]$STEP_MARK_OSC;test;\u0007result\r\n" +
                "\u001b]$STEP_MARK_OSC;test;0\u0007\u001b[?2004h\u001b[?1h").encodeToByteArray())
            testScheduler.runCurrent()
            assertEquals(version, terminal.snapshotVersion)
            assertTrue(terminal.bracketedPaste)
            assertTrue(terminal.applicationCursorKeys)
            assertEquals(TerminalStepMark("test", 0, "result"), terminal.takeStepMark("test"))
            assertEquals(2L, terminal.outputVersion)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a saved password is never offered by an invisible surface`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope, sudo = SudoPasswordOffer("test", "test@host", "private"),
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            session.chunks.send("[sudo] password for test: ".encodeToByteArray())
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(1_000)
            assertFalse(terminal.sudoOffer)
            terminal.answerSudoPrompt()
            testScheduler.runCurrent()
            assertTrue(session.sent.isEmpty())
            terminal.attachRenderer()
            testScheduler.runCurrent()
            assertTrue(terminal.sudoOffer)
            terminal.answerSudoPrompt()
            testScheduler.runCurrent()
            assertTrue(session.sent.isEmpty(), "invisible dwell cannot authorize a saved password")
            testScheduler.advanceTimeBy(OFFER_DWELL_MS)
            terminal.answerSudoPrompt()
            testScheduler.runCurrent()
            assertEquals(listOf("private\r"), session.sent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancellation flushes a hidden render tail and releases recording awaiters`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = BackgroundTestSession()
        val terminal = TerminalScreenState(session, scope,
            backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
        terminal.startRecording("test")
        session.chunks.send("head".encodeToByteArray())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(20)
        session.chunks.send("tail".encodeToByteArray())
        testScheduler.runCurrent()
        assertEquals("head", terminal.output)
        val export = async(start = CoroutineStart.UNDISPATCHED) { terminal.stopRecording() }
        try {
            scope.cancel()
            testScheduler.runCurrent()
            assertEquals("headtail", terminal.output)
            assertTrue(export.isCompleted, "a recording command received during cancellation must release its waiter")
            val take = export.await()
            assertTrue(take == null || take.contains("tail"))
            assertNull(terminal.stopRecording())
        } finally {
            export.cancel()
        }
    }
}
