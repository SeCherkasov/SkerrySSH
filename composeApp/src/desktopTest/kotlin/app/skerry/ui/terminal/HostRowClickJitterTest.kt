package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.app.LocalHostClickConnectMode
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.host.HostDragState
import app.skerry.ui.host.HostDrop
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.draggableHostRow
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test for "host row double-click connects only every other time": a catalog row carries
 * both a click handler (connect) and a drag-reorder gesture. Compose's drag start slop for a MOUSE
 * pointer is ~0.125dp — sub-pixel — so the 1–2px of jitter inside a normal click starts a "drag"
 * that consumes the move and cancels the row's tap/double-tap. Whether a click lands then depends
 * on how still the hand/mouse is, which is why it varied between sessions. The drag gesture must
 * tolerate a small dead zone before it claims the pointer.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class HostRowClickJitterTest {

    /** One mouse click at [at] whose pointer drifts by [jitterPx] between press and release. */
    private fun ImageComposeScene.jitteryClick(at: Offset, jitterPx: Float, timeMillis: Long) {
        sendPointerEvent(PointerEventType.Press, at, timeMillis = timeMillis)
        val drifted = at + Offset(jitterPx, 0f)
        sendPointerEvent(PointerEventType.Move, drifted, timeMillis = timeMillis + 8)
        sendPointerEvent(PointerEventType.Release, drifted, timeMillis = timeMillis + 16)
    }

    private fun runRowScene(
        mode: HostClickConnectMode,
        onConnect: () -> Unit,
        onDrop: (HostDrop) -> Unit = {},
        body: ImageComposeScene.(HostDragState) -> Unit,
    ) {
        val dragState = HostDragState()
        // The whole scene is one drop-capable folder, so a genuine drag has a target to land on.
        val folders = listOf(HostFolder("srv", emptyList()))
        dragState.setFolderRange("srv", Rect(0f, 0f, 400f, 300f))
        ImageComposeScene(width = 400, height = 300, density = Density(1f)).use { scene ->
            scene.setContent {
                RowUnderTest(mode, dragState, folders, onConnect, onDrop)
            }
            Snapshot.sendApplyNotifications()
            scene.render(16_666_667L)
            scene.body(dragState)
            Snapshot.sendApplyNotifications()
            scene.render(33_333_334L)
        }
    }

    @Composable
    private fun RowUnderTest(
        mode: HostClickConnectMode,
        dragState: HostDragState,
        folders: List<HostFolder>,
        onConnect: () -> Unit,
        onDrop: (HostDrop) -> Unit,
    ) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                LocalHostClickConnectMode provides mode,
            ) {
                // Same layering as HostRow: the drag gesture wraps the clickable row. onEdit gives
                // the row its trailing "⋮" menu button, like the live catalog.
                Box(Modifier.draggableHostRow(dragState, "h1", { folders }, onDrop = onDrop)) {
                    HostEntryRow(
                        label = "alpha", selected = false, dot = Color.Green, badge = null,
                        onClick = onConnect, mono = FontFamily.Monospace, icon = "dns",
                        onEdit = {},
                    )
                }
            }
        }
    }

    @Test
    fun doubleClickWithMouseJitterConnects() {
        var connects = 0
        runRowScene(HostClickConnectMode.DoubleClick, onConnect = { connects++ }) {
            // Two clicks, each drifting 2px while the button is down; 64ms apart (over the 40ms
            // double-tap minimum, under the timeout).
            jitteryClick(Offset(50f, 13f), jitterPx = 2f, timeMillis = 0)
            jitteryClick(Offset(52f, 13f), jitterPx = 2f, timeMillis = 80)
        }
        assertEquals(1, connects, "a double click with 2px of mouse jitter must still connect")
    }

    @Test
    fun singleClickWithMouseJitterConnects() {
        var connects = 0
        runRowScene(HostClickConnectMode.SingleClick, onConnect = { connects++ }) {
            jitteryClick(Offset(50f, 13f), jitterPx = 2f, timeMillis = 0)
        }
        assertEquals(1, connects, "a single click with 2px of mouse jitter must still connect")
    }

    @Test
    fun relaxedDoubleClickWithinSystemConventionConnects() {
        // Desktop Compose's built-in double-tap window is 300ms from first UP to second DOWN
        // (EmptyViewConfiguration) — tighter than OS double-click conventions (GNOME 400ms,
        // Windows 500ms press-to-press). A relaxed double click within those conventions must
        // still connect. Real sleep: the built-in window is a real-time withTimeout.
        var connects = 0
        runRowScene(HostClickConnectMode.DoubleClick, onConnect = { connects++ }) {
            sendPointerEvent(PointerEventType.Press, Offset(50f, 13f), timeMillis = 16)
            sendPointerEvent(PointerEventType.Release, Offset(50f, 13f), timeMillis = 32)
            Thread.sleep(380)
            sendPointerEvent(PointerEventType.Press, Offset(50f, 13f), timeMillis = 396)
            sendPointerEvent(PointerEventType.Release, Offset(50f, 13f), timeMillis = 412)
        }
        assertEquals(1, connects, "a 380ms press-to-press double click must still connect")
    }

    @Test
    fun menuButtonClickThenRowClickDoesNotConnect() {
        // The "⋮" menu button is a descendant of the row: a click on it followed by a quick click
        // on the row body must not read as a row double-click. The button's press is consumed by
        // its own clickable and must be ignored by the row's double-click detection.
        var connects = 0
        runRowScene(HostClickConnectMode.DoubleClick, onConnect = { connects++ }) {
            // IconBtn: box 22px at the row's right edge (padding end = 2) → center ≈ x 387.
            sendPointerEvent(PointerEventType.Press, Offset(387f, 13f), timeMillis = 16)
            sendPointerEvent(PointerEventType.Release, Offset(387f, 13f), timeMillis = 32)
            sendPointerEvent(PointerEventType.Press, Offset(50f, 13f), timeMillis = 80)
            sendPointerEvent(PointerEventType.Release, Offset(50f, 13f), timeMillis = 96)
        }
        assertEquals(0, connects, "⋮ click + row click must not count as a row double-click")
    }

    @Test
    fun middleClickDoesNotCountTowardDoubleClick() {
        // The press counter must only count the primary button: a left click chased by a middle
        // click (X11 paste reflex), or two middle clicks, must not read as a row double-click —
        // middle-click is its own gesture elsewhere (tab close), never a stand-in for primary.
        var connects = 0
        runRowScene(HostClickConnectMode.DoubleClick, onConnect = { connects++ }) {
            sendPointerEvent(PointerEventType.Press, Offset(50f, 13f), timeMillis = 16)
            sendPointerEvent(PointerEventType.Release, Offset(50f, 13f), timeMillis = 32)
            middleClick(Offset(50f, 13f), timeMillis = 80)
        }
        assertEquals(0, connects, "left click + middle click must not count as a double-click")
        runRowScene(HostClickConnectMode.DoubleClick, onConnect = { connects++ }) {
            middleClick(Offset(50f, 13f), timeMillis = 16)
            middleClick(Offset(50f, 13f), timeMillis = 80)
        }
        assertEquals(0, connects, "two middle clicks must not count as a double-click")
    }

    private fun ImageComposeScene.middleClick(at: Offset, timeMillis: Long) {
        sendPointerEvent(
            PointerEventType.Press, at, timeMillis = timeMillis,
            buttons = PointerButtons(isTertiaryPressed = true), button = PointerButton.Tertiary,
        )
        sendPointerEvent(
            PointerEventType.Release, at, timeMillis = timeMillis + 16,
            buttons = PointerButtons(), button = PointerButton.Tertiary,
        )
    }

    @Test
    fun genuineDragStillReordersAndDoesNotConnect() {
        var connects = 0
        var drop: HostDrop? = null
        runRowScene(HostClickConnectMode.SingleClick, onConnect = { connects++ }, onDrop = { drop = it }) {
            sendPointerEvent(PointerEventType.Press, Offset(50f, 13f), timeMillis = 0)
            // Well past any reasonable dead zone, in several steps like a real drag.
            var y = 13f
            repeat(5) { step ->
                y += 20f
                sendPointerEvent(PointerEventType.Move, Offset(50f, y), timeMillis = 8L + step * 8)
            }
            sendPointerEvent(PointerEventType.Release, Offset(50f, y), timeMillis = 56)
        }
        assertNotNull(drop, "a genuine 100px drag must still commit a drop")
        assertEquals(0, connects, "a drag must not also connect")
    }
}
