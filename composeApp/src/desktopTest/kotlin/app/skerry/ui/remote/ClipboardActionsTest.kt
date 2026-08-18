package app.skerry.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.desktop.runForm
import app.skerry.ui.terminal.SystemClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two buttons of the clipboard menu move text by hand, and they have to move it through the same
 * clipboard the automatic bridge uses: a Compose/AWT write would land in XWayland while "Send mine"
 * read back through `wl-paste`, so the menu would hand the remote host older text than the one it
 * had just been told it copied (#282).
 */
@OptIn(ExperimentalTestApi::class)
class ClipboardActionsTest {

    @Test
    fun `Copy here puts the remote string on the system clipboard`() = withActions { _, actions, system, _ ->
        actions.copyHere("remote text")
        assertEquals(listOf("remote text"), system.writes, "the remote string went somewhere else")
    }

    @Test
    fun `Send mine reads the system clipboard, not the Compose one`() = withActions { session, actions, _, _ ->
        actions.sendMine()
        assertEquals(listOf("local text"), session.clipboard, "the host was sent a clipboard from elsewhere")
    }

    /**
     * A refused write is what the platform clipboard does where the direct path owns it and `wl-copy`
     * fails — the press has to say so instead of leaving the user with a copy that never happened.
     */
    @Test
    fun `a refused copy is reported to the user`() =
        withActions(FakeSystemClipboard(content = "local text", refuseWrites = 1)) { _, actions, _, noted ->
            actions.copyHere("remote text")
            waitForIdle()
            assertTrue(noted(), "the failed copy was swallowed — the menu drew no note")
        }

    /**
     * A clipboard that cannot answer is not an empty clipboard: "Send mine" has to say so, or the
     * user walks away believing the host has their text.
     */
    @Test
    fun `a clipboard that cannot be read is reported, not passed off as empty`() =
        withActions(FakeSystemClipboard(refusesRead = true)) { session, actions, _, noted ->
            actions.sendMine()
            waitForIdle()
            assertTrue(session.clipboard.isEmpty(), "a clipboard that never answered was sent anyway")
            assertTrue(noted(), "the failed read was swallowed — the menu drew no note")
        }
}

/**
 * The menu's actions over a fake session and a fake system clipboard. The actions are pressed from
 * the test and their coroutines drained by `waitForIdle` — the scope they launch in is the
 * composition's, which is why they outlive the popup that opened them. `noted` answers whether the
 * failure note was ever up: it clears itself on a timer, so a single read after the fact would race
 * that timer instead of observing the press.
 */
@OptIn(ExperimentalTestApi::class)
private fun withActions(
    system: FakeSystemClipboard = FakeSystemClipboard(content = "local text"),
    body: ComposeUiTest.(FakeRemoteDesktop, ClipboardActions, FakeSystemClipboard, () -> Boolean) -> Unit,
) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val session = FakeRemoteDesktop()
    val screen = RemoteDesktopScreenState(session, scope)
    var noted = false
    lateinit var actions: ClipboardActions
    try {
        runForm({
            actions = Actions(screen, system)
            if (actions.failed) noted = true
        }) {
            body(session, actions, system) { noted }
            waitForIdle()
        }
    } finally {
        scope.cancel()
    }
}

@Composable
private fun Actions(screen: RemoteDesktopScreenState, system: SystemClipboard): ClipboardActions =
    rememberClipboardActions(screen, system)
