package app.skerry.ui.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCompletionLatencyTest {
    @Test
    fun `accepted completion renews feedback after the typing window expires`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = BackgroundTestSession()
        val terminal = TerminalScreenState(session, scope, initialHistory = listOf("git status"),
            backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
        try {
            terminal.attachRenderer()
            session.chunks.trySend("$ ".encodeToByteArray())
            testScheduler.runCurrent()
            terminal.typeInput("git", mirror = false)
            session.chunks.trySend("git".encodeToByteArray())
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            // A new output batch starts the ordinary publication window after typing expires.
            session.chunks.trySend("\u001b]2;LOAD\u0007".encodeToByteArray())
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertTrue(terminal.acceptSuggestion())
            testScheduler.runCurrent()
            session.chunks.trySend(" status".encodeToByteArray())
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(3)
            testScheduler.runCurrent()
            assertEquals("$ git status", terminal.output)
            assertEquals(listOf("git", " status"), session.sent)
        } finally {
            scope.cancel()
        }
    }
}
