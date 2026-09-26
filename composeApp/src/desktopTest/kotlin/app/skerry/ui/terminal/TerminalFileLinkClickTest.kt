package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import app.skerry.shared.terminal.TerminalPos
import app.skerry.ui.desktop.runForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ctrl+click on an OSC 8 `file://` link (`ls --hyperlink`) reveals the link's path in the file panel;
 * the URI never reaches the platform handler, which refuses file: anyway.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalFileLinkClickTest {

    @Test
    fun `ctrl+click on a file link opens its path`() = withFileLinkScreen { opened ->
        onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        onRoot().performMouseInput { click(center) }
        onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        waitUntil("Ctrl+click on the link opened nothing") { opened.isNotEmpty() }
        assertEquals(listOf("/srv/data/report.txt"), opened)
    }

    @Test
    fun `a plain click on a file link opens nothing`() = withFileLinkScreen { opened ->
        onRoot().performMouseInput { click(center) }
        waitForIdle()
        assertEquals(emptyList(), opened)
    }
}

/** A live [TerminalScreen] whose every visible cell belongs to one `file://` link. */
@OptIn(ExperimentalTestApi::class)
private fun withFileLinkScreen(body: ComposeUiTest.(opened: List<String>) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = ScriptedSession()
    val terminal = TerminalScreenState(session, scope)
    val opened = mutableListOf<String>()
    try {
        runForm({
            TerminalScreen(terminal, Modifier.fillMaxSize(), onOpenPath = { opened += it })
        }) {
            // Long enough to wrap over the whole screen, so the pointer lands on the link wherever it is.
            session.print("\u001b]8;;file://web-01/srv/data/report.txt\u001b\\" + "x".repeat(20_000) + "\u001b]8;;\u001b\\")
            waitUntil("the link never reached the screen") {
                terminal.screen.getOrNull(0)?.getOrNull(0)?.hyperlink != null
            }
            waitForIdle()
            body(opened)
        }
    } finally {
        scope.cancel()
    }
}
