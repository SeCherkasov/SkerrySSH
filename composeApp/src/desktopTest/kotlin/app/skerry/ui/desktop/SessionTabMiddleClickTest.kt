package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
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
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Middle-click (mouse wheel button) on a session tab must close it, like a browser tab. The close
 * must fire regardless of the chip being active or hovered (the ✕ button is hidden on resting
 * tabs), and a middle click must not also activate the tab.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SessionTabMiddleClickTest {

    private fun runChipScene(
        active: Boolean,
        onClick: () -> Unit,
        onClose: () -> Unit,
        body: ImageComposeScene.() -> Unit,
    ) {
        ImageComposeScene(width = 300, height = 60, density = Density(1f)).use { scene ->
            scene.setContent { ChipUnderTest(active, onClick, onClose) }
            scene.render(16_666_667L)
            scene.body()
            scene.render(33_333_334L)
        }
    }

    @Composable
    private fun ChipUnderTest(active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
            ) {
                SessionTabChip(name = "Orchestrator", dot = Color.Green, active = active, onClick = onClick, onClose = onClose)
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
}
