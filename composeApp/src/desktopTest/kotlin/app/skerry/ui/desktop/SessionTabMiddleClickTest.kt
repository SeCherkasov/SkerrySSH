package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.session.TabDragState
import app.skerry.ui.session.draggableTab
import app.skerry.ui.session.tabBoundsAnchor
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Middle-click (mouse wheel button) on a session tab must close it, like a browser tab: armed on
 * the tertiary press, committed on its release while still over the chip (moving off first aborts
 * an accidental wheel-button bump). The close must fire regardless of the chip being active or
 * hovered (the ✕ button is hidden on resting tabs), and a middle click must not also activate the
 * tab.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SessionTabMiddleClickTest {

    private fun runChipScene(
        active: Boolean,
        onClick: () -> Unit,
        onClose: () -> Unit,
        modifier: Modifier = Modifier,
        body: ImageComposeScene.() -> Unit,
    ) {
        ImageComposeScene(width = 300, height = 60, density = Density(1f)).use { scene ->
            scene.setContent { ChipUnderTest(active, onClick, onClose, modifier) }
            scene.render(16_666_667L)
            scene.body()
            scene.render(33_333_334L)
        }
    }

    @Composable
    private fun ChipUnderTest(active: Boolean, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
            ) {
                SessionTabChip(
                    name = "Orchestrator", dot = Color.Green, active = active,
                    onClick = onClick, onClose = onClose, modifier = modifier,
                )
            }
        }
    }

    /** One middle-button click at [at]: press with the tertiary button held, then release. */
    private fun ImageComposeScene.middleClick(at: Offset) {
        sendPointerEvent(
            PointerEventType.Press, at, timeMillis = 16,
            buttons = PointerButtons(isTertiaryPressed = true), button = PointerButton.Tertiary,
        )
        sendPointerEvent(
            PointerEventType.Release, at, timeMillis = 32,
            buttons = PointerButtons(), button = PointerButton.Tertiary,
        )
    }

    @Test
    fun middleClickClosesInactiveTab() {
        var clicks = 0
        var closes = 0
        runChipScene(active = false, onClick = { clicks++ }, onClose = { closes++ }) {
            middleClick(Offset(40f, 14f))
        }
        assertEquals(1, closes, "middle click on a resting tab must close it")
        assertEquals(0, clicks, "middle click must not also activate the tab")
    }

    @Test
    fun middleClickClosesActiveTab() {
        var closes = 0
        runChipScene(active = true, onClick = {}, onClose = { closes++ }) {
            middleClick(Offset(40f, 14f))
        }
        assertEquals(1, closes, "middle click on the active tab must close it")
    }

    @Test
    fun primaryClickStillActivatesAndDoesNotClose() {
        var clicks = 0
        var closes = 0
        runChipScene(active = false, onClick = { clicks++ }, onClose = { closes++ }) {
            sendPointerEvent(PointerEventType.Press, Offset(40f, 14f), timeMillis = 16)
            sendPointerEvent(PointerEventType.Release, Offset(40f, 14f), timeMillis = 32)
        }
        assertEquals(1, clicks, "a left click must still activate the tab")
        assertEquals(0, closes, "a left click must not close the tab")
    }

    @Test
    fun middlePressReleasedOffTabDoesNotClose() {
        // The browser escape hatch: press the wheel button on the tab, drag the pointer off it,
        // release — the close must be aborted (the chip is 28px tall; y=50 is well below it).
        var closes = 0
        runChipScene(active = false, onClick = {}, onClose = { closes++ }) {
            sendPointerEvent(
                PointerEventType.Press, Offset(40f, 14f), timeMillis = 16,
                buttons = PointerButtons(isTertiaryPressed = true), button = PointerButton.Tertiary,
            )
            sendPointerEvent(
                PointerEventType.Move, Offset(40f, 50f), timeMillis = 24,
                buttons = PointerButtons(isTertiaryPressed = true),
            )
            sendPointerEvent(
                PointerEventType.Release, Offset(40f, 50f), timeMillis = 32,
                buttons = PointerButtons(), button = PointerButton.Tertiary,
            )
        }
        assertEquals(0, closes, "releasing the wheel button off the tab must abort the close")
    }

    @Test
    fun middleClickMidDragClosesAndResetsDragState() {
        // Production modifier chain: the chip also carries tabBoundsAnchor + draggableTab. Middle-
        // click is independent of the primary button holding a drag, so closing the dragged tab
        // must also abort the drag (TabDragState.tabClosed) — otherwise the insert line lingers.
        val drag = TabDragState()
        var closes = 0
        val chain = Modifier
            .tabBoundsAnchor(drag, "t1")
            .draggableTab(drag, "t1", ids = { listOf("t1") }) { _, _ -> }
        runChipScene(
            active = true, onClick = {},
            onClose = { drag.tabClosed("t1"); closes++ },
            modifier = chain,
        ) {
            // Primary drag well past slop…
            sendPointerEvent(PointerEventType.Press, Offset(40f, 14f), timeMillis = 16)
            sendPointerEvent(PointerEventType.Move, Offset(70f, 14f), timeMillis = 24)
            sendPointerEvent(PointerEventType.Move, Offset(90f, 14f), timeMillis = 32)
            // …then a middle click on the same chip while the primary button is still down.
            sendPointerEvent(
                PointerEventType.Press, Offset(90f, 14f), timeMillis = 40,
                buttons = PointerButtons(isPrimaryPressed = true, isTertiaryPressed = true),
                button = PointerButton.Tertiary,
            )
            sendPointerEvent(
                PointerEventType.Release, Offset(90f, 14f), timeMillis = 48,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Tertiary,
            )
            // Assert while the scene is still live and the primary button still held: in
            // production the close removes the chip and cancels the drag coroutine without
            // onDragEnd, so only tabClosed can reset the state at this point. After the scene
            // closes (or on a trailing primary release) the gesture's own end() would fire and
            // mask a missing abort.
            assertNull(drag.draggingTabId, "closing the dragged tab must abort the drag")
            assertNull(drag.insertLineIndex, "the insert line must not linger after the dragged tab closes")
        }
        assertEquals(1, closes, "a middle click during a drag of the same tab must still close it")
    }
}
