package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.shared.terminal.TerminalPos
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.terminal_copied
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Copying a selection by hand (Ctrl+Shift+C, the same call the context menu and the touch menu
 * make). Two facts this covers and nothing else does: the text goes to the platform's own clipboard
 * rather than Compose/AWT, which under Wayland nothing pastes from, and the "Copied" banner stands
 * for a copy that actually landed — where the direct path owns the clipboard a refused write leaves
 * nothing behind, so a banner drawn before the write would be a lie (#282).
 */
@OptIn(ExperimentalTestApi::class)
class TerminalCopySelectionTest {

    @Test
    fun `the selection goes to the system clipboard and the banner follows`() = withSelection() { clipboard ->
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.C) } } }
        waitUntil("nothing reached the clipboard") { clipboard.writes.isNotEmpty() }
        assertEquals(listOf(SELECTED_LINE), clipboard.writes.map { it.trim() })
        waitUntil("the copy landed but the banner never appeared") { copiedBannerShown() }
    }

    @Test
    fun `a refused copy draws no banner`() = withSelection(refuses = true) { clipboard ->
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.C) } } }
        waitUntil("the copy never even reached the clipboard") { clipboard.writes.isNotEmpty() }
        waitForIdle()
        assertTrue(!copiedBannerShown(), "\"Copied\" was drawn over a clipboard that refused the text")
    }
}

private const val SELECTED_LINE = "select me"

/** Whether the self-dismissing "Copied" pill is on screen right now. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.copiedBannerShown(): Boolean =
    onAllNodesWithText(runBlocking { getString(Res.string.terminal_copied) }).fetchSemanticsNodes().isNotEmpty()

/**
 * A live [TerminalScreen] holding a selection over one printed line, its clipboard replaced by a
 * recorder that either keeps the text or refuses it the way `wl-copy` can.
 */
@OptIn(ExperimentalTestApi::class)
private fun withSelection(
    refuses: Boolean = false,
    body: ComposeUiTest.(FakeSystemClipboard) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = ScriptedSession()
    val terminal = TerminalScreenState(session, scope)
    val clipboard = FakeSystemClipboard(refuseWrites = if (refuses) 1 else 0)
    try {
        runForm({
            CompositionLocalProvider(LocalSystemClipboard provides clipboard) {
                TerminalScreen(terminal, Modifier.fillMaxSize())
            }
        }) {
            session.print(SELECTED_LINE)
            waitUntil("the printed line never reached the screen") { terminal.selectableLine() }
            terminal.selectLineAt(TerminalPos(0, 0))
            waitForIdle()
            body(clipboard)
        }
    } finally {
        scope.cancel()
    }
}

/** Whether the first row carries the printed text yet (the emulator drains on its own thread). */
private fun TerminalScreenState.selectLineProbe(): String? {
    selectLineAt(TerminalPos(0, 0))
    val text = selectedText()
    clearSelection()
    return text
}

private fun TerminalScreenState.selectableLine(): Boolean = selectLineProbe()?.contains(SELECTED_LINE) == true
