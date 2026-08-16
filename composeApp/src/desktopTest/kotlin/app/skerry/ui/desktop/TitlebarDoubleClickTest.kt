package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.doubleClick
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The titlebar's empty-space gestures (issue #176): a double-click must toggle maximize, and it must
 * keep doing so with the window-drag handler sharing the same box — the drag consumes events once it
 * arms, and must not eat the presses a double-click is made of.
 *
 * The scene mounts [titlebarDragArea] — the very modifier chain [rememberSkerryWindowChrome]'s
 * dragArea uses, not a copy — wired to a real [WindowState].
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TitlebarDoubleClickTest {

    private class Chrome(initial: WindowPlacement = WindowPlacement.Floating) {
        val state = WindowState(placement = initial)
        var toggles = 0
        val toggleMaximize: () -> Unit = {
            toggles++
            state.placement =
                if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                else WindowPlacement.Maximized
        }
    }

    private fun runTitlebarScene(
        useNativeMove: Boolean = false,
        startMove: (java.awt.Window, Int, Int) -> Boolean = { _, _, _ -> false },
        initial: WindowPlacement = WindowPlacement.Floating,
        body: ImageComposeScene.(Chrome) -> Unit,
    ): Chrome {
        val chrome = Chrome(initial)
        val awtWindow = Frame()
        try {
            ImageComposeScene(width = 600, height = 44, density = Density(1f)).use { scene ->
                scene.setContent {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .titlebarDragArea(awtWindow, chrome.state, useNativeMove, chrome.toggleMaximize, startMove),
                    )
                }
                scene.render(0L)
                scene.body(chrome)
                scene.render(1_000_000_000L)
            }
        } finally {
            awtWindow.dispose()
        }
        return chrome
    }

    @Test
    fun doubleClickOnEmptyTitlebarTogglesMaximize() {
        val chrome = runTitlebarScene {
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100)
            sendPointerEvent(PointerEventType.Release, Offset(300f, 22f), timeMillis = 160)
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 300)
            sendPointerEvent(PointerEventType.Release, Offset(300f, 22f), timeMillis = 360)
        }
        assertEquals(1, chrome.toggles, "press-release-press within the double-tap timeout must toggle maximize")
        assertEquals(WindowPlacement.Maximized, chrome.state.placement)
    }

    @Test
    fun doubleClickWithClickJitterStillTogglesMaximize() {
        // A physical mouse click is rarely two events at one point: the pointer drifts a pixel or
        // two between press and release, and between the clicks. Stay well inside touch slop.
        val chrome = runTitlebarScene {
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100)
            sendPointerEvent(PointerEventType.Move, Offset(301f, 22f), timeMillis = 130)
            sendPointerEvent(PointerEventType.Release, Offset(301f, 23f), timeMillis = 160)
            sendPointerEvent(PointerEventType.Move, Offset(302f, 23f), timeMillis = 220)
            sendPointerEvent(PointerEventType.Press, Offset(302f, 23f), timeMillis = 300)
            sendPointerEvent(PointerEventType.Move, Offset(303f, 23f), timeMillis = 330)
            sendPointerEvent(PointerEventType.Release, Offset(303f, 24f), timeMillis = 360)
        }
        assertEquals(1, chrome.toggles, "pixel-level jitter inside touch slop must not break the double-click")
        assertEquals(WindowPlacement.Maximized, chrome.state.placement)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun doubleClickOnEmptyTabStripTogglesMaximizeInFullShell() {
        // The full desktop shell with the production chrome chain wired in. This test asserts only
        // that the double-click REACHES the handler through the real titlebar (chips, "+", drag
        // handlers don't swallow it); the placement outcome itself is covered by the scene tests.
        var toggles = 0
        val awtWindow = Frame()
        val state = WindowState(placement = WindowPlacement.Floating)
        try {
            val chrome = WindowChrome(
                isMaximized = { false },
                onMinimize = {},
                onToggleMaximize = { toggles++ },
                onClose = {},
                dragArea = { content ->
                    Box(
                        Modifier.titlebarDragArea(awtWindow, state, useNativeMove = false, toggleMaximize = { toggles++ }),
                    ) { content() }
                },
            )
            runDesktopShell(windowChrome = chrome) {
                onNodeWithTag(app.skerry.ui.app.UiTags.SESSION_TABS).performMouseInput {
                    // Empty strip space, right of the "+" button.
                    doubleClick(Offset(width - 20f, height / 2f))
                }
                waitForIdle()
            }
            assertEquals(1, toggles, "double-click on empty tab-strip space must toggle maximize")
        } finally {
            awtWindow.dispose()
        }
    }

    @Test
    fun maximizeFromDoubleClickSurvivesTheWindowJumpUnderThePointer() {
        // The heart of issue #176. The double-click toggles maximize on its SECOND press, while the
        // button is still down. The WM then maximizes the window under the held pointer, the window
        // origin moves, and the pointer's window-local position jumps by that same offset (observed
        // live as a (+120,+88) jump on GNOME). To the drag handler still watching this press, the
        // jump reads as a slop-crossing drag on a maximized window — and #151's restore-on-drag
        // instantly un-maximizes the window the double-click just maximized.
        val chrome = runTitlebarScene {
            val down = PointerButtons(isPrimaryPressed = true)
            val up = PointerButtons()
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100, buttons = down, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Release, Offset(300f, 22f), timeMillis = 160, buttons = up, button = PointerButton.Primary)
            // Second press: the double-click detector fires here and the window starts maximizing.
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 260, buttons = down, button = PointerButton.Primary)
            // The maximize lands while the button is still held: the next events arrive with the
            // local position shifted by the moved window origin, though the mouse never moved.
            sendPointerEvent(PointerEventType.Move, Offset(420f, 110f), timeMillis = 280, buttons = down)
            sendPointerEvent(PointerEventType.Release, Offset(420f, 110f), timeMillis = 320, buttons = up, button = PointerButton.Primary)
        }
        assertEquals(1, chrome.toggles, "the double-click must toggle exactly once")
        assertEquals(
            WindowPlacement.Maximized, chrome.state.placement,
            "the maximize must survive the window jumping under the held pointer — " +
                "the jump is not a drag and must not trigger restore-on-drag",
        )
    }

    @Test
    fun restoreFromDoubleClickSurvivesTheWindowJumpUnderThePointer() {
        // The symmetric direction: a double-click on a maximized window restores it, the shrink
        // moves the origin under the held pointer, and the local-coordinate jump must not arm a
        // drag on the now-floating window (which would hand it to the WM stuck to the cursor).
        var moves = 0
        val chrome = runTitlebarScene(
            useNativeMove = true,
            startMove = { _, _, _ -> moves++; true },
            initial = WindowPlacement.Maximized,
        ) {
            val down = PointerButtons(isPrimaryPressed = true)
            val up = PointerButtons()
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100, buttons = down, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Release, Offset(300f, 22f), timeMillis = 160, buttons = up, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 260, buttons = down, button = PointerButton.Primary)
            // The restore lands while the button is still held: local coordinates jump back.
            sendPointerEvent(PointerEventType.Move, Offset(180f, 12f), timeMillis = 280, buttons = down)
            sendPointerEvent(PointerEventType.Release, Offset(180f, 12f), timeMillis = 320, buttons = up, button = PointerButton.Primary)
        }
        assertEquals(1, chrome.toggles, "the double-click must toggle exactly once")
        assertEquals(WindowPlacement.Floating, chrome.state.placement, "the restore must survive the window jump")
        assertEquals(0, moves, "the coordinate jump must not start a window-manager move")
    }

    @Test
    fun dragOnMaximizedWindowStillRestoresIt() {
        // #151's restore-on-drag, driven through the real titlebarDrag gesture: a press that
        // crosses touch slop on a maximized window (placement unchanged since the down) must still
        // restore it — the mid-press placement guard fires only on a placement change it did not
        // cause, never on an ordinary drag.
        val chrome = runTitlebarScene(initial = WindowPlacement.Maximized) {
            val down = PointerButtons(isPrimaryPressed = true)
            val up = PointerButtons()
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100, buttons = down, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Move, Offset(340f, 24f), timeMillis = 140, buttons = down)
            sendPointerEvent(PointerEventType.Release, Offset(340f, 24f), timeMillis = 400, buttons = up, button = PointerButton.Primary)
        }
        assertEquals(0, chrome.toggles, "a drag is not a double-click")
        assertEquals(WindowPlacement.Floating, chrome.state.placement, "dragging a maximized window must restore it")
    }

    @Test
    fun doubleClickAfterWmHandedDragStillTogglesMaximize() {
        // A titlebar drag on X11 is handed to the window manager (issue #176's hint): the WM takes
        // the pointer grab, so the button release ending the drag never reaches the app — the
        // pointer stream resumes with the button seemingly still down. The next double-click must
        // still toggle maximize.
        var moves = 0
        val chrome = runTitlebarScene(useNativeMove = true, startMove = { _, _, _ -> moves++; true }) {
            val down = PointerButtons(isPrimaryPressed = true)
            val up = PointerButtons()
            // Drag: press, cross touch slop → handed to the WM. Its release is never delivered.
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100, buttons = down, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Move, Offset(340f, 22f), timeMillis = 130, buttons = down)
            // The WM move ended elsewhere; the pointer comes back hovering, button up.
            sendPointerEvent(PointerEventType.Move, Offset(310f, 22f), timeMillis = 900, buttons = up)
            // Double-click on empty titlebar space.
            sendPointerEvent(PointerEventType.Press, Offset(310f, 22f), timeMillis = 1_000, buttons = down, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Release, Offset(310f, 22f), timeMillis = 1_060, buttons = up, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Press, Offset(310f, 22f), timeMillis = 1_150, buttons = down, button = PointerButton.Primary)
            sendPointerEvent(PointerEventType.Release, Offset(310f, 22f), timeMillis = 1_210, buttons = up, button = PointerButton.Primary)
        }
        assertEquals(1, moves, "the drag must be handed to the WM exactly once")
        assertEquals(1, chrome.toggles, "a double-click after a WM-handed drag must still toggle maximize")
        assertEquals(WindowPlacement.Maximized, chrome.state.placement)
    }

    @Test
    fun twoSlowClicksDoNotToggle() {
        val chrome = runTitlebarScene {
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 100)
            sendPointerEvent(PointerEventType.Release, Offset(300f, 22f), timeMillis = 160)
            sendPointerEvent(PointerEventType.Press, Offset(300f, 22f), timeMillis = 2_100)
            sendPointerEvent(PointerEventType.Release, Offset(300f, 22f), timeMillis = 2_160)
        }
        assertEquals(0, chrome.toggles, "clicks slower than the double-tap timeout must not maximize")
        assertEquals(WindowPlacement.Floating, chrome.state.placement)
    }
}
