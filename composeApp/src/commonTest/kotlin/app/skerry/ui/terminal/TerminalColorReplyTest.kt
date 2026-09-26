package app.skerry.ui.terminal

import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The theme on screen is what a color query hears back — and a theme switch changes the answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalColorReplyTest {

    private val esc = 27.toChar().toString()
    private val query = "$esc]11;?$esc\\"

    private val dark = TerminalThemes.DEFAULT.copy(background = Color(0x10, 0x20, 0x30))
    private val light = TerminalThemes.DEFAULT.copy(background = Color(0xf0, 0xf1, 0xf2))

    @Test
    fun `a background query is answered with the applied theme and follows a switch`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = RecordingSession()
        val state = TerminalScreenState(session, scope)

        session.chunks.trySend(query.encodeToByteArray())
        testScheduler.runCurrent()
        assertTrue(session.sent.isEmpty())

        state.applyTerminalTheme(dark)
        session.chunks.trySend(query.encodeToByteArray())
        testScheduler.runCurrent()
        state.applyTerminalTheme(light)
        session.chunks.trySend(query.encodeToByteArray())
        testScheduler.runCurrent()

        assertEquals(
            listOf("$esc]11;rgb:1010/2020/3030$esc\\", "$esc]11;rgb:f0f0/f1f1/f2f2$esc\\"),
            session.sent.map { it.decodeToString() },
        )
        scope.cancel()
    }

    @Test
    fun `a viewer of someone else's session answers nothing`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = RecordingSession()
        val state = TerminalScreenState(session, scope, answersQueries = false)
        state.applyTerminalTheme(dark)

        session.chunks.trySend("$query$esc[c$esc[6n".encodeToByteArray())
        testScheduler.runCurrent()
        assertTrue(session.sent.isEmpty())

        state.send("typed")
        testScheduler.runCurrent()
        assertEquals(listOf("typed"), session.sent.map { it.decodeToString() })
        scope.cancel()
    }

    @Test
    fun `theme colors convert channel for channel`() {
        val colors = dark.reportedColors()
        assertEquals(0x10, colors.background.r)
        assertEquals(0x20, colors.background.g)
        assertEquals(0x30, colors.background.b)
        assertEquals(16, colors.ansi.size)
    }
}

/** Session fed chunk by chunk, keeping what the terminal wrote back. */
private class RecordingSession : TerminalSession {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    val sent = mutableListOf<ByteArray>()
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = chunks.receiveAsFlow()
    override suspend fun send(data: ByteArray) { sent += data }
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}
