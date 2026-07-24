package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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

        // "П" (U+041F) in UTF-8 = 0xD0 0x9F, split across two chunks.
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
    fun `typed input and paste bump inputVersion but programmatic sends do not`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)

        val v0 = state.inputVersion
        state.send("ls\n")
        state.sendBytes(byteArrayOf(0x1b))
        assertEquals(v0, state.inputVersion)

        state.typeInput("l")
        assertEquals(v0 + 1, state.inputVersion)
        state.paste("echo hi")
        assertEquals(v0 + 2, state.inputVersion)
        scope.cancel()
    }

    @Test
    fun `sendUserInput forwards to the session and bumps inputVersion`() = runTest {
        // Keybar keys, snippet runs, and AI-confirmed commands are user-initiated: they must snap
        // a scrolled-up viewport back to the live screen (unlike programmatic send), but must not
        // feed autocomplete (unlike typeInput).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val v0 = state.inputVersion
        state.sendUserInput("uptime\r")

        assertContentEquals("uptime\r".encodeToByteArray(), session.sent.single())
        assertEquals(v0 + 1, state.inputVersion)
        scope.cancel()
    }

    // --- lastOutput / lastCommandBlock (for "explain this output") ---

    @Test
    fun `lastCommandBlock returns the last command and its output, not the login banner`() {
        // Regression: with nothing selected, explaining sent the whole screen, so a long MOTD banner
        // drowned out the actual last command (uptime). The block must be just that command + output.
        val screen = listOf(
            "Welcome to Ubuntu 22.04.5 LTS",
            "",
            " * Documentation:  https://help.ubuntu.com",
            "This system has been minimized by removing packages.",
            "Last login: Fri Jul 24 11:51:03 2026 from 178.205.96.77",
            "root@140722:~# uptime",
            " 12:21:14 up 129 days, 18:42,  1 user,  load average: 0.48, 0.14, 0.05",
            "root@140722:~#",
        ).joinToString("\n")

        val block = lastCommandBlock(screen)

        assertEquals(
            "root@140722:~# uptime\n 12:21:14 up 129 days, 18:42,  1 user,  load average: 0.48, 0.14, 0.05",
            block,
        )
        // The banner and unrelated history are excluded.
        assertEquals(false, block!!.contains("Documentation"))
        assertEquals(false, block.contains("Last login"))
    }

    @Test
    fun `lastCommandBlock returns null when the prompt does not repeat`() {
        // Only one prompt on screen: no earlier command line to bound the block, so fall back to the
        // whole screen at the call site rather than mis-slicing.
        assertEquals(null, lastCommandBlock("some free-form output\nwith no repeated prompt\nroot@140722:~#"))
    }

    @Test
    fun `lastCommandBlock ignores a too-short prompt that would match everything`() {
        // A bare "$" prompt would start-with-match unrelated lines; reject it.
        assertEquals(null, lastCommandBlock("total output here\n$ ls\n$"))
    }

    @Test
    fun `lastCommandBlock keeps a command that produced no output`() {
        assertEquals("root@140722:~# cd /var/log", lastCommandBlock("root@140722:~# cd /var/log\nroot@140722:~#"))
    }

    @Test
    fun `lastOutput reads the last command block from the live screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit(
            ("Last login: Fri Jul 24 11:51:03 2026 from 178.205.96.77\r\n" +
                "root@140722:~# uptime\r\n" +
                " 12:21:14 up 129 days, 18:42,  1 user,  load average: 0.48, 0.14, 0.05\r\n" +
                "root@140722:~#").encodeToByteArray(),
        )

        val last = state.lastOutput()
        assertEquals(true, last != null && last.contains("uptime") && last.contains("load average"))
        assertEquals(false, last!!.contains("Last login"))
        scope.cancel()
    }

    @Test
    fun `preloaded history feeds autosuggestion`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("git push origin main"),
        )
        state.typeInput("git pu")
        assertEquals("sh origin main", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `committed command triggers history persist callback`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val snapshots = mutableListOf<List<String>>()
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            onHistoryChanged = { snapshots += it },
        )
        state.typeInput("uptime\n")
        assertEquals(listOf("uptime"), snapshots.last())
        scope.cancel()
    }

    @Test
    fun `cycle suggestion advances the ghost tail`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("backupdb", "backupfiles"),
        )
        state.typeInput("back")
        assertEquals("updb", state.suggestionTail)
        state.cycleSuggestion()
        assertEquals("upfiles", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `reverse search selects a matching command and closes on accept`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("docker ps", "git status"),
        )
        state.openReverseSearch()
        state.reverseSearchAppend("git")
        assertEquals("git status", state.reverseSearchSelection)
        state.reverseSearchAccept()
        assertEquals(null, state.reverseSearchQuery) // overlay closed after accepting
        scope.cancel()
    }

    @Test
    fun `delete removes selected command from history and persists`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val snapshots = mutableListOf<List<String>>()
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("gti status", "git status"),
            onHistoryChanged = { snapshots += it },
        )
        state.openReverseSearch()
        state.reverseSearchAppend("gti") // pick the typo entry
        assertEquals("gti status", state.reverseSearchSelection)
        state.reverseSearchDeleteSelected()
        assertEquals(emptyList(), state.reverseSearchResults) // "gti" is no longer found
        assertEquals(listOf("git status"), snapshots.last()) // persisted without the typo
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

        // Narrow 5x3 grid: autowrap breaks the line at width 5.
        state.resize(PtySize(cols = 5, rows = 3))
        session.emit("abcdefgh".encodeToByteArray())

        assertEquals("abcde\nfgh", state.output)
        assertEquals(3, state.screen.size) // grid is now exactly 3 rows
        scope.cancel()
    }

    @Test
    fun `exposes the live grid size to the status bar`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // Default 80x24 before the first layout, then the emulator's live size.
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
        state.resize(PtySize(cols = 90, rows = 25)) // same size — dedup, don't nudge the PTY

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

        // Defaults: cursor visible, block, blinking.
        assertEquals(true, state.cursorVisible)
        assertEquals(CursorShape.Block, state.cursorShape)
        assertEquals(true, state.cursorBlink)

        session.emit("$esc[?25l".encodeToByteArray())   // hide cursor
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
        state.selectWordAt(TerminalPos(0, 8)) // tap lands on "world"

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
        state.selectWordAt(TerminalPos(0, 0)) // tap lands on "h"

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
    fun `capturePrimarySelection stores the current selection text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.selectWordAt(TerminalPos(0, 8)) // "world"
        val captured = state.capturePrimarySelection()

        assertEquals("world", captured)
        assertEquals("world", state.primarySelection)
        scope.cancel()
    }

    @Test
    fun `capturePrimarySelection is a no-op without a selection`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        val captured = state.capturePrimarySelection()

        assertEquals(null, captured)
        assertEquals(null, state.primarySelection)
        scope.cancel()
    }

    @Test
    fun `tracks mouse pixel mode 1016 from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(false, state.mousePixels)
        session.emit("$esc[?1016h".encodeToByteArray())
        assertEquals(true, state.mousePixels)
        scope.cancel()
    }

    @Test
    fun `reportMouse uses pixel coordinates when 1016 is active`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        session.emit("$esc[?1002h$esc[?1016h".encodeToByteArray()) // ButtonEvent + SGR-Pixels
        val handled = state.reportMouse(
            MouseButton.Left, MouseEventType.Press, TerminalPos(2, 3), pixelX = 49, pixelY = 99,
        )

        assertEquals(true, handled)
        // Coordinates are pixels (49+1 / 99+1), not cells.
        assertContentEquals("$esc[<0;50;100M".encodeToByteArray(), session.sent.single())
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
    fun `a recoverable resize failure keeps the command handler alive`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // A recoverable PTY resize failure: the handler must not die — feed still works after it.
        session.resizeError = { IllegalStateException("pty broke") }
        state.resize(PtySize(cols = 10, rows = 4))
        session.resizeError = null
        session.emit("ok".encodeToByteArray())

        assertEquals("ok", state.output)
        scope.cancel()
    }

    @Test
    fun `a cancellation during resize tears down the command handler`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // CancellationException must not be swallowed as a "recoverable failure": it must tear
        // down the handler coroutine (structured concurrency), or feed would keep working after cancellation.
        session.resizeError = { CancellationException("scope cancelled") }
        state.resize(PtySize(cols = 10, rows = 4))
        session.emit("ignored".encodeToByteArray())

        assertEquals("", state.output) // handler torn down — feed never applied
        scope.cancel()
    }

    @Test
    fun `a burst of sends reaches the session in FIFO order`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        repeat(50) { state.send(it.toString()) }

        val order = session.sent.map { it.decodeToString() }
        assertEquals(List(50) { it.toString() }, order)
        scope.cancel()
    }

    @Test
    fun `snapshotVersion advances on every feed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val before = state.snapshotVersion
        session.emit("a".encodeToByteArray())
        session.emit("b".encodeToByteArray())

        assertEquals(before + 2, state.snapshotVersion)
        scope.cancel()
    }

    @Test
    fun `osc 52 clipboard write is dropped when the gate is off by default`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        val copies = mutableListOf<String>()
        val collector = scope.launch { state.clipboardCopies.collect { copies += it } }

        session.emit("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray()) // base64 "hello"
        assertEquals(emptyList(), copies) // gated off — no clipboard write reaches the UI
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `osc 52 clipboard write reaches the UI once the gate is enabled`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope, clipboardWriteEnabled = true)
        val esc = 27.toChar().toString()

        val copies = mutableListOf<String>()
        val collector = scope.launch { state.clipboardCopies.collect { copies += it } }

        session.emit("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray())
        assertEquals(listOf("hello"), copies)
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `applyClipboardWriteEnabled toggles the gate on an open session`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope) // starts off
        val esc = 27.toChar().toString()

        val copies = mutableListOf<String>()
        val collector = scope.launch { state.clipboardCopies.collect { copies += it } }

        session.emit("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray())
        assertEquals(emptyList(), copies)

        state.applyClipboardWriteEnabled(true) // live settings change
        session.emit("$esc]52;c;d29ybGQ=$esc\\".encodeToByteArray()) // base64 "world"
        assertEquals(listOf("world"), copies)
        collector.cancel()
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
        // Focus was never moved — nothing to select.

        assertEquals(null, state.selectedText())
        scope.cancel()
    }
}

/** Fake session: manual output emission, captures send/resize calls. */
private class FakeTerminalSession : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = flow {
        for (chunk in emissions) emit(chunk)
    }

    val sent = mutableListOf<ByteArray>()
    val resizes = mutableListOf<PtySize>()

    /** When set, `resize` throws this before recording the call (simulates a PTY error/cancellation). */
    var resizeError: (() -> Throwable)? = null

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    override suspend fun send(data: ByteArray) {
        sent += data
    }

    override suspend fun resize(size: PtySize) {
        resizeError?.let { throw it() }
        resizes += size
    }

    override suspend fun close() {
        _state.value = TerminalState.Closed()
        emissions.close()
    }
}
