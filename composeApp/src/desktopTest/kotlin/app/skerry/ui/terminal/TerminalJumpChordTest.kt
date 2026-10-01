package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_marks_strip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shell-integration jump chord (Ctrl+Shift+Z/X, OSC 133): with command marks on screen it is
 * ours — consumed even at the end of the jump list, where a fall-through would reach the PTY as
 * 0x1A and suspend the foreground job — and it jumps TO the mark and selects its output. Without
 * marks, or on the alternate screen (a TUI's rows, not the marked ones), the chord is the
 * shell's, exactly as it was before integration existed.
 *
 * The negative assertions race the outbound drain: the session's consumer runs on
 * Dispatchers.Default, invisible to Compose's waitForIdle, so a 0x1A that did leak could still
 * sit in the channel at assert time and the test would false-pass. Every negative check is
 * therefore ordered behind a printable-key sentinel: the sentinel lands in the same FIFO after
 * anything the chord could have sent, so "the first byte is the sentinel" proves nothing came
 * before it.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalJumpChordTest {

    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    @Test
    fun `without marks the chord still reaches the pty`() = withChordSession { session, _ ->
        session.print("plain terminal, no integration\r\n")
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        waitUntil("the suspend byte reached the pty") { session.sent.any { it.isNotEmpty() } }
        assertEquals(listOf<Byte>(0x1A), session.sent.first().toList())
    }

    @Test
    fun `with marks the chord never reaches the pty`() = withChordSession { session, terminal ->
        // One prompt mark on row 0 and the viewport at the top: the jump list has nothing above,
        // which is the end-of-list case the consumption exists for.
        session.print("$esc]133;A$bel$ ")
        waitUntil("the mark appeared") { terminal.commandMarks.isNotEmpty() }
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        // Sentinel: a printable key drains after anything the chord could have sent.
        onRoot().performKeyInput { pressKey(Key.A) }
        waitUntil("the sentinel reached the pty") { session.sent.isNotEmpty() }
        assertEquals("a", session.sent.first().decodeToString(), "the chord reached the pty as ${session.sent}")
    }

    @Test
    fun `the forward chord jumps and selects the command's output`() = withChordSession { session, terminal ->
        session.print("top\r\n")
        session.print(
            "$esc]133;A$bel" + "$ echo" + "$esc]133;B$bel" + "echo\r\n" + "$esc]133;C$bel" +
                "out1\r\n" + "$esc]133;D;0$bel",
        )
        waitUntil("the mark appeared") { terminal.commandMarks.isNotEmpty() }
        val mark = terminal.commandMarks.single()
        // Viewport at the top (row 0 is "top"), the mark is below it: forward must find it.
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.X) } } }
        waitUntil("the jump never selected the output") { terminal.selectedText() == "out1" }
        assertEquals(mark.outputRow, terminal.selection?.start?.row)
        // And the chord itself never reached the pty (sentinel ordering, see class doc).
        onRoot().performKeyInput { pressKey(Key.A) }
        waitUntil("the sentinel reached the pty") { session.sent.isNotEmpty() }
        assertEquals("a", session.sent.first().decodeToString())
    }

    @Test
    fun `on the alternate screen the chord is the shell's again`() = withChordSession { session, terminal ->
        // Marks still exist from the primary buffer, but the viewport shows a TUI (vim/htop): its
        // rows are not the marked rows, so jumping there would point at foreign text. The chord
        // must fall through to the PTY exactly as it does without integration — and the gutter
        // itself must be gone (the markers live in primary-buffer coordinates).
        session.print("$esc]133;A$bel$ ")
        waitUntil("the mark appeared") { terminal.commandMarks.isNotEmpty() }
        val stripLabel = runBlocking { getString(Res.string.term_marks_strip) }
        waitUntil("the strip never appeared") {
            onAllNodesWithContentDescription(stripLabel).fetchSemanticsNodes().isNotEmpty()
        }
        session.print("$esc[?1049h")
        waitUntil("the alternate screen engaged") { terminal.altScreen }
        waitUntil("the strip stayed on the TUI screen") {
            onAllNodesWithContentDescription(stripLabel).fetchSemanticsNodes().isEmpty()
        }
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        waitUntil("the suspend byte reached the pty") { session.sent.any { it.isNotEmpty() } }
        assertEquals(listOf<Byte>(0x1A), session.sent.first().toList())
    }
}

/** A live [TerminalScreen] over a scripted session that records what is typed into it. */
@OptIn(ExperimentalTestApi::class)
private fun withChordSession(body: ComposeUiTest.(ScriptedSession, TerminalScreenState) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = ScriptedSession()
    val terminal = TerminalScreenState(session, scope)
    try {
        runForm({
            CompositionLocalProvider(LocalSystemClipboard provides FakeSystemClipboard()) {
                TerminalScreen(terminal, Modifier.fillMaxSize())
            }
        }) {
            body(session, terminal)
        }
    } finally {
        scope.cancel()
    }
}
