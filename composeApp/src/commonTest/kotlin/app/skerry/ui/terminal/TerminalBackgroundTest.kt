package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalBackgroundTest {
    @Test
    fun `hidden terminal parses every chunk but publishes fewer full snapshots`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope,
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            repeat(100) {
                session.chunks.send("line-$it\r\n".encodeToByteArray())
                testScheduler.advanceTimeBy(10)
                testScheduler.runCurrent()
            }
            testScheduler.advanceUntilIdle()
            assertEquals(100L, terminal.outputVersion)
            assertTrue(terminal.snapshotVersion <= 5, "full publishes=${terminal.snapshotVersion}")
            assertTrue(terminal.output.contains("line-99"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `showing a quiet hidden terminal refreshes its tail without more output`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope,
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            session.chunks.send("head".encodeToByteArray())
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(20)
            session.chunks.send("tail".encodeToByteArray())
            testScheduler.runCurrent()
            assertEquals("head", terminal.output)
            terminal.attachRenderer()
            testScheduler.advanceTimeBy(PUBLISH_MIN_INTERVAL_MS)
            testScheduler.runCurrent()
            assertEquals("headtail", terminal.output)
            terminal.detachRenderer()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `one remaining renderer keeps a split terminal at foreground cadence`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope,
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            terminal.attachRenderer()
            terminal.attachRenderer()
            terminal.detachRenderer()
            testScheduler.runCurrent()
            val before = terminal.snapshotVersion
            repeat(10) {
                testScheduler.advanceTimeBy(20)
                session.chunks.send("x".encodeToByteArray())
                testScheduler.runCurrent()
            }
            assertEquals(before + 10, terminal.snapshotVersion)
            terminal.detachRenderer()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `hidden password and production guard use fresh wrapped input rather than stale render`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope,
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            session.chunks.send("user@host$ ".encodeToByteArray())
            testScheduler.runCurrent()
            val rendered = terminal.output
            testScheduler.advanceTimeBy(20)
            session.chunks.send("\r\u001b[KPassword: ".encodeToByteArray())
            testScheduler.runCurrent()
            assertEquals(rendered, terminal.output)
            assertTrue(terminal.awaitingSecret)
            terminal.typeInput("private\r", mirror = false)
            assertFalse(terminal.hasSuggestion)
            testScheduler.advanceTimeBy(20)
            terminal.guardPolicy = ProductionGuardPolicy(production = true)
            session.chunks.send(("\r\u001b[Kuser@host$ " + " ".repeat(65) + "rm -rf /srv/prod").encodeToByteArray())
            testScheduler.runCurrent()
            assertFalse(terminal.awaitingSecret)
            terminal.typeInput("\r", mirror = false)
            assertNotNull(terminal.pendingGuarded)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `hidden recording query replies and final output survive EOF`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val session = BackgroundTestSession()
            val terminal = TerminalScreenState(session, scope,
                backgroundWhenUnobserved = true, nowMillis = { testScheduler.currentTime })
            terminal.startRecording("test")
            session.chunks.send("head".encodeToByteArray())
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(20)
            session.chunks.send("tail\u001b[6n".encodeToByteArray())
            testScheduler.runCurrent()
            assertTrue(session.sent.any { it.endsWith("R") })
            val take = terminal.stopRecording()
            assertTrue(take?.contains("tail") == true)
            session.chunks.close()
            testScheduler.runCurrent()
            assertEquals("headtail", terminal.output)
            terminal.attachRenderer() // closed owner: no hanging command/awaiter
        } finally {
            scope.cancel()
        }
    }
}

internal class BackgroundTestSession : TerminalSession {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    override val output = chunks.receiveAsFlow()
    override val state = MutableStateFlow<TerminalState>(TerminalState.Open)
    val sent = mutableListOf<String>()
    override suspend fun send(data: ByteArray) { sent += data.decodeToString() }
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() { chunks.close() }
}
