package app.skerry.ui.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.ui.app.LocalUserActivity
import app.skerry.ui.desktop.runForm
import app.skerry.ui.mobile.VncImeField
import app.skerry.ui.terminal.ANCHOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the remote desktop's soft-keyboard funnel is allowed to keep, and what it owes the rest of
 * the app.
 *
 * Everything typed here goes to a remote machine, and on a Windows or VNC login screen that is a
 * password. The field held the last 64 characters of it in its own `TextFieldValue` — which is
 * `EditableText` in the semantics tree, readable by any accessibility service. The terminal's
 * funnel has diffed against a constant anchor since it was written
 * ([app.skerry.ui.terminal.imeDeltaToPty]); this one now does the same, and reports typing to the
 * idle auto-lock the same way (issue #291's policy: every kind of input defers the lock).
 */
@OptIn(ExperimentalTestApi::class)
class VncImeFunnelTest {

    @Test
    fun `typing reaches the session without being retained in the field`() = withFunnel { session, _ ->
        onNode(hasSetTextAction()).performTextInput(TYPED)

        assertEquals(
            TYPED.toList(),
            session.keys.filter { it.second }.mapNotNull { it.first.codePoint.takeIf { c -> c != 0 }?.toChar() },
            "the characters typed did not reach the remote desktop",
        )
        assertEquals(
            ANCHOR,
            editableText(),
            "the funnel kept what was typed — on a login screen that is the password",
        )
    }

    /**
     * The deletion the anchor exists for: nothing has been typed since the last reset, so without
     * it the field is empty, there is nothing for the IME to delete, and the Backspace a user just
     * pressed at a login prompt never reaches the server.
     */
    @Test
    fun `a backspace on an untouched field still reaches the session`() = withFunnel { session, _ ->
        onNode(hasSetTextAction()).performTextClearance()

        val backspace = remoteKeyEvent(Key.Backspace, 0)
        assertTrue(
            session.keys.any { it.first.keySym == backspace?.keySym && it.second },
            "clearing the field sent ${session.keys.size} events, none of them a backspace",
        )
        assertEquals(ANCHOR, editableText(), "the field did not return to the anchor after a deletion")
    }

    /**
     * The soft keyboard is its own window: it produces neither a key event nor a pointer event, so
     * `Modifier.idleActivity` sees nothing and the vault locks on top of a session being typed into.
     */
    @Test
    fun `typing through the funnel defers the idle auto-lock`() {
        var reported = false
        withFunnel(onActivity = { reported = true }) { _, _ ->
            onNode(hasSetTextAction()).performTextInput("a")
        }
        assertTrue(reported, "typing through the soft keyboard never reached the idle timer")
    }

    private fun ComposeUiTest.editableText(): String? =
        onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun withFunnel(
        onActivity: () -> Unit = {},
        body: ComposeUiTest.(FakeRemoteDesktop, RemoteDesktopScreenState) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(4, 4))
        val screen = RemoteDesktopScreenState(session, scope)
        try {
            runForm({
                CompositionLocalProvider(LocalUserActivity provides onActivity) { VncImeField(screen) {} }
            }) {
                waitForIdle()
                body(session, screen)
            }
        } finally {
            scope.cancel()
        }
    }
}

private const val TYPED = "hunter2"
