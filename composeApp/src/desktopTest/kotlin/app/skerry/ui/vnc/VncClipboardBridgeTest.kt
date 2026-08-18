package app.skerry.ui.vnc

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.ui.design.FakeClipboard
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.desktop.runForm
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.terminal.SystemClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge is the only clipboard path a session has that the user does not press a button for, and
 * it has to go through the platform's own clipboard — under Wayland the AWT/XWayland one it used to
 * talk to reads back nothing and writes where no native app can paste from (#282).
 */
@OptIn(ExperimentalTestApi::class)
class VncClipboardBridgeTest {

    /** local -> remote: what the system clipboard holds when the session opens is pushed to the host. */
    @Test
    fun `the text the session opens over comes from the system clipboard`() {
        val system = FakeSystemClipboard(content = "local text")
        withBridge(system) { session, _ ->
            assertEquals(
                listOf("local text"),
                session.clipboard,
                "the bridge pushed nothing the host can paste — it read a clipboard of its own",
            )
        }
    }

    /** remote -> local: a host's cut-text lands on the system clipboard, not on the AWT one beside it. */
    @Test
    fun `what the host copies lands on the system clipboard`() {
        val system = FakeSystemClipboard()
        val awt = FakeClipboard()
        withBridge(system, awt, remote = "remote text") { _, _ ->
            assertEquals(
                listOf("remote text"),
                system.writes,
                "the host's clipboard never reached the system one, so no native app can paste it",
            )
            assertNull(awt.text, "the bridge wrote straight to the Compose/AWT clipboard, bypassing the platform path")
        }
    }

    /**
     * And the pull happens once: the bridge's own write puts the host's text on the system clipboard,
     * so a bridge that re-read it would hand the host back what the host had just sent — a loop with
     * one side's text going round it.
     */
    @Test
    fun `the local text is pushed once and the host's own text never comes back`() {
        val system = FakeSystemClipboard(content = "local text")
        withBridge(system, remote = "remote text") { session, _ ->
            assertEquals(
                listOf("local text"),
                session.clipboard,
                "the bridge sent the host something other than the one local clipboard it opened over",
            )
        }
    }

    /**
     * A clipboard that refuses is a clipboard: on Wayland `wl-paste` can time out, and a session
     * whose first read threw used to be a session with no clipboard at all.
     */
    @Test
    fun `a clipboard that refuses the read still lets the host's text through`() {
        val system = FakeSystemClipboard(refusesRead = true)
        withBridge(system, remote = "remote text") { session, _ ->
            assertEquals(listOf("remote text"), system.writes, "one failed read killed the other direction")
            assertTrue(session.clipboard.isEmpty(), "a clipboard that could not be read was sent to the host anyway")
        }
    }
}

/**
 * The bridge alone over a fake session, with [system] as the platform clipboard and [awt] as the
 * Compose one it must not reach for. [remote] is replayed into the session before the first frame,
 * so a clipboard the host sent has landed by the time the bridge composes.
 */
@OptIn(ExperimentalTestApi::class)
private fun withBridge(
    system: SystemClipboard,
    awt: FakeClipboard = FakeClipboard(),
    remote: String? = null,
    body: ComposeUiTest.(FakeRemoteDesktop, RemoteDesktopScreenState) -> Unit,
) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val updates = MutableSharedFlow<RemoteDesktopUpdate>(replay = 1)
    remote?.let { updates.tryEmit(RemoteDesktopUpdate.ClipboardText(it)) }
    val session = FakeRemoteDesktop(updates = updates)
    val screen = RemoteDesktopScreenState(session, scope)
    try {
        runForm({
            CompositionLocalProvider(LocalClipboard provides awt) {
                VncClipboardBridge(screen, system)
            }
        }) {
            body(session, screen)
        }
    } finally {
        scope.cancel()
    }
}
