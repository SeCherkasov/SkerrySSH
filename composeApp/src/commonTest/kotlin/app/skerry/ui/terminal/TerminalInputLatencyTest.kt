package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuardPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Input feedback is paced per terminal without changing PTY ordering or synchronized frames. */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalInputLatencyTest {
    private class Fixture(val scope: CoroutineScope, val session: BackgroundTestSession, val terminal: TerminalScreenState)

    private fun TestScope.fixture(visible: Boolean = true): Fixture {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = BackgroundTestSession()
        val terminal = TerminalScreenState(session, scope, backgroundWhenUnobserved = true,
            nowMillis = { testScheduler.currentTime })
        if (visible) terminal.attachRenderer()
        session.chunks.trySend("head".encodeToByteArray())
        testScheduler.runCurrent()
        return Fixture(scope, session, terminal)
    }

    private fun TestScope.feed(fixture: Fixture, text: String) {
        fixture.session.chunks.trySend(text.encodeToByteArray())
        testScheduler.runCurrent()
    }

    private fun TestScope.tick(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    @Test
    fun `typed feedback is published within four milliseconds during output`() = runTest {
        val f = fixture()
        try {
            tick(1)
            f.terminal.typeInput("x", mirror = false)
            testScheduler.runCurrent()
            feed(f, "echo")
            tick(3)
            assertEquals("headecho", f.terminal.output)
            assertEquals(listOf("x"), f.session.sent)
        } finally { f.scope.cancel() }
    }

    @Test
    fun `snippet paste and shared input receive the same feedback pacing`() = runTest {
        for (send in listOf<(TerminalScreenState) -> Unit>(
            { it.sendUserInput("x") }, { it.paste("x", mirror = false) }, { it.sendSharedInput("x".encodeToByteArray()) },
        )) {
            val f = fixture()
            try {
                tick(1)
                send(f.terminal)
                testScheduler.runCurrent()
                feed(f, "echo")
                tick(3)
                assertEquals("headecho", f.terminal.output)
            } finally { f.scope.cancel() }
            tick(20)
        }
    }

    @Test
    fun `typing in one visible pane does not accelerate another pane`() = runTest {
        val typed = fixture()
        val other = fixture()
        try {
            tick(1)
            typed.terminal.typeInput("x", mirror = false)
            testScheduler.runCurrent()
            feed(typed, "echo")
            feed(other, "tail")
            tick(3)
            assertEquals("headecho", typed.terminal.output)
            assertEquals("head", other.terminal.output)
            tick(12)
            assertEquals("headtail", other.terminal.output)
        } finally { typed.scope.cancel(); other.scope.cancel() }
    }

    @Test
    fun `feedback window expires and delayed remote output keeps normal pacing`() = runTest {
        val f = fixture()
        try {
            f.terminal.typeInput("x", mirror = false)
            testScheduler.runCurrent()
            tick(100)
            feed(f, "late")
            tick(1)
            feed(f, "tail")
            tick(3)
            assertEquals("headlate", f.terminal.output)
            tick(12)
            assertEquals("headlatetail", f.terminal.output)
        } finally { f.scope.cancel() }
    }

    @Test
    fun `hidden input does not accelerate full render publication`() = runTest {
        val f = fixture(visible = false)
        try {
            tick(1)
            f.terminal.typeInput("x", mirror = false)
            testScheduler.runCurrent()
            feed(f, "echo")
            tick(20)
            assertEquals("head", f.terminal.output)
            tick(230)
            assertEquals("headecho", f.terminal.output)
        } finally { f.scope.cancel() }
    }

    @Test
    fun `programmatic writes and terminal replies do not accelerate publication`() = runTest {
        val f = fixture()
        try {
            tick(1)
            f.terminal.send("probe")
            feed(f, "tail\u001b[6n")
            tick(3)
            assertEquals("head", f.terminal.output)
            tick(12)
            assertEquals("headtail", f.terminal.output)
        } finally { f.scope.cancel() }
    }

    @Test
    fun `held production command cannot start a feedback window`() = runTest {
        val f = fixture()
        try {
            tick(20)
            feed(f, "\r\u001b[Kuser@host$ rm -rf /srv/prod")
            val before = f.terminal.output
            f.terminal.guardPolicy = ProductionGuardPolicy(production = true)
            tick(1)
            f.terminal.typeInput("\r", mirror = false)
            assertNotNull(f.terminal.pendingGuarded)
            feed(f, "tail")
            tick(3)
            assertEquals(before, f.terminal.output)
            assertEquals(emptyList(), f.session.sent)
        } finally { f.scope.cancel() }
    }

    @Test
    fun `input does not tear a synchronized output frame`() = runTest {
        val f = fixture()
        try {
            tick(1)
            f.terminal.typeInput("x", mirror = false)
            testScheduler.runCurrent()
            feed(f, "\u001b[?2026hecho")
            tick(20)
            assertEquals("head", f.terminal.output)
            feed(f, "\u001b[?2026l")
            assertEquals("headecho", f.terminal.output)
        } finally { f.scope.cancel() }
    }

    @Test
    fun `cancellation still flushes input feedback waiting for its publication edge`() = runTest {
        val f = fixture()
        tick(1)
        f.terminal.typeInput("x", mirror = false)
        testScheduler.runCurrent()
        feed(f, "echo")
        f.scope.cancel()
        testScheduler.runCurrent()
        assertEquals("headecho", f.terminal.output)
    }
}
