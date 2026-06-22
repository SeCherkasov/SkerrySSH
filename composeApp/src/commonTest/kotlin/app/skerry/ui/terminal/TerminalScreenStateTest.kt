package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TerminalPos
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalScreenStateTest {

    @Test
    fun `output accumulates decoded session output`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("ab".encodeToByteArray())
        session.emit("cd".encodeToByteArray())

        assertEquals("abcd", state.output)
        scope.cancel()
    }

    @Test
    fun `output decodes utf-8 split across chunks`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // "П" (U+041F) в UTF-8 = 0xD0 0x9F, разрезанная между двумя чанками
        session.emit(byteArrayOf(0xD0.toByte()))
        session.emit(byteArrayOf(0x9F.toByte()))

        assertEquals("П", state.output)
        scope.cancel()
    }

    @Test
    fun `send forwards encoded input to session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.send("ls -la\n")

        assertContentEquals("ls -la\n".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `resize forwards to session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.resize(PtySize(cols = 100, rows = 30))

        assertEquals(PtySize(cols = 100, rows = 30), session.resizes.single())
        scope.cancel()
    }

    @Test
    fun `resize applies the new grid to the emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // Узкая сетка 5×3: автоперенос рвёт строку на ширине 5.
        state.resize(PtySize(cols = 5, rows = 3))
        session.emit("abcdefgh".encodeToByteArray())

        assertEquals("abcde\nfgh", state.output)
        assertEquals(3, state.screen.size) // сетка теперь ровно 3 строки
        scope.cancel()
    }

    @Test
    fun `exposes the live grid size to the status bar`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // Дефолт 80×24 до первого лэйаута, затем живой размер из эмулятора.
        assertEquals(80, state.cols)
        assertEquals(24, state.rows)
        state.resize(PtySize(cols = 132, rows = 43))
        assertEquals(132, state.cols)
        assertEquals(43, state.rows)
        scope.cancel()
    }

    @Test
    fun `repeated resize with the same size forwards once`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.resize(PtySize(cols = 90, rows = 25))
        state.resize(PtySize(cols = 90, rows = 25)) // тот же размер — дубль гасим, PTY не дёргаем

        assertEquals(listOf(PtySize(cols = 90, rows = 25)), session.resizes)
        scope.cancel()
    }

    @Test
    fun `exposes session state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        assertEquals(TerminalState.Open, state.state.value)
        scope.cancel()
    }

    @Test
    fun `tracks application cursor keys mode from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(false, state.applicationCursorKeys)
        session.emit("$esc[?1h".encodeToByteArray()) // DECCKM on (vim/less)
        assertEquals(true, state.applicationCursorKeys)
        session.emit("$esc[?1l".encodeToByteArray()) // DECCKM off
        assertEquals(false, state.applicationCursorKeys)
        scope.cancel()
    }

    @Test
    fun `tracks cursor visibility shape and blink from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        // Дефолты: курсор виден, блок, мигает.
        assertEquals(true, state.cursorVisible)
        assertEquals(CursorShape.Block, state.cursorShape)
        assertEquals(true, state.cursorBlink)

        session.emit("$esc[?25l".encodeToByteArray())   // скрыть курсор
        assertEquals(false, state.cursorVisible)

        session.emit("$esc[6 q".encodeToByteArray())     // DECSCUSR: steady bar
        assertEquals(CursorShape.Bar, state.cursorShape)
        assertEquals(false, state.cursorBlink)
        scope.cancel()
    }

    @Test
    fun `selection over screen yields the spanned text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 5))

        assertEquals("hello", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `clearing selection drops the highlight and text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 3))
        state.clearSelection()

        assertEquals(null, state.selection)
        assertEquals(null, state.selectedText())
        scope.cancel()
    }

    @Test
    fun `selecting a word grabs the whole run under the position`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.selectWordAt(TerminalPos(0, 8)) // палец на "world"

        assertEquals("world", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `selecting a word from its first char still grabs the whole word`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.selectWordAt(TerminalPos(0, 0)) // палец на "h"

        assertEquals("hello", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `moving the end handle extends the selection keeping the start`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 5)) // "hello"
        state.moveSelectionEnd(TerminalPos(0, 11))

        assertEquals("hello world", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `moving the start handle shrinks the selection keeping the end`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 11)) // "hello world"
        state.moveSelectionStart(TerminalPos(0, 6))

        assertEquals("world", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `moving a handle with no selection is a no-op`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        state.moveSelectionStart(TerminalPos(0, 1))
        state.moveSelectionEnd(TerminalPos(0, 3))

        assertEquals(null, state.selection)
        scope.cancel()
    }

    @Test
    fun `tracks mouse and bracketed-paste modes from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(MouseTracking.Off, state.mouseTracking)
        session.emit("$esc[?1002h$esc[?1006h$esc[?2004h".encodeToByteArray())
        assertEquals(MouseTracking.ButtonEvent, state.mouseTracking)
        assertEquals(true, state.mouseSgr)
        assertEquals(true, state.bracketedPaste)
        scope.cancel()
    }

    @Test
    fun `tracks any-event mouse mode and alt-screen from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(MouseTracking.Off, state.mouseTracking)
        assertEquals(false, state.altScreen)
        session.emit("$esc[?1003h$esc[?1049h".encodeToByteArray()) // AnyEvent + alt-screen
        assertEquals(MouseTracking.AnyEvent, state.mouseTracking)
        assertEquals(true, state.altScreen)
        scope.cancel()
    }

    @Test
    fun `reportMouse sends an sgr report and signals it handled the event`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        session.emit("$esc[?1000h$esc[?1006h".encodeToByteArray()) // Normal + SGR
        val handled = state.reportMouse(MouseButton.Left, MouseEventType.Press, TerminalPos(0, 0))

        assertEquals(true, handled)
        assertContentEquals("$esc[<0;1;1M".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `reportMouse is a no-op when mouse tracking is off`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val handled = state.reportMouse(MouseButton.Left, MouseEventType.Press, TerminalPos(0, 0))

        assertEquals(false, handled)
        assertEquals(0, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `paste wraps in bracketed markers when the mode is enabled`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        session.emit("$esc[?2004h".encodeToByteArray()) // bracketed paste on
        state.paste("hi")

        assertContentEquals("$esc[200~hi$esc[201~".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `paste passes text through when bracketed mode is off`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.paste("hi")

        assertContentEquals("hi".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `empty selection produces no copyable text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 2))
        // фокус не сдвигали — выделять нечего

        assertEquals(null, state.selectedText())
        scope.cancel()
    }
}

/** Фейк-сессия: ручная эмиссия вывода, перехват send/resize. */
private class FakeTerminalSession : TerminalSession {
    private val _state = MutableStateFlow(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = flow {
        for (chunk in emissions) emit(chunk)
    }

    val sent = mutableListOf<ByteArray>()
    val resizes = mutableListOf<PtySize>()

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    override suspend fun send(data: ByteArray) {
        sent += data
    }

    override suspend fun resize(size: PtySize) {
        resizes += size
    }

    override suspend fun close() {
        _state.value = TerminalState.Closed
        emissions.close()
    }
}
