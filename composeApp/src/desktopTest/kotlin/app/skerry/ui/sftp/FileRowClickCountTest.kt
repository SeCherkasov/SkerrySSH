package app.skerry.ui.sftp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import androidx.compose.runtime.CompositionLocalProvider
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How many times a run of clicks on a file row opens it.
 *
 * The row counts presses by hand (`uptimeMillis` deltas) rather than through `detectTapGestures`,
 * which lost relaxed double clicks to Compose's 300ms up→down window. Hand-counting means the value
 * the counter starts from — and resets to — is load-bearing, and `0L` is the wrong one: a clock that
 * starts near zero puts the very first press inside the double-click window of it. A single click
 * then enters a directory the user only meant to select, and a third click enters a second one.
 *
 * Driven through [ImageComposeScene] with the event times written by hand, because that is the only
 * way to put the clock where the bug lives — a shell test's clock is whatever the run gives it.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FileRowClickCountTest {

    @Test
    fun `the first click on a fresh clock selects rather than opens`() = runRowScene { opens ->
        click(at = ROW_CENTER, timeMillis = 0)
        assertEquals(0, opens(), "a single click opened the directory")
    }

    @Test
    fun `two clicks inside the window open it once`() = runRowScene { opens ->
        click(at = ROW_CENTER, timeMillis = 0)
        click(at = ROW_CENTER, timeMillis = 60)
        assertEquals(1, opens())
    }

    /** The third click starts a new pair; it must not complete the previous one a second time. */
    @Test
    fun `three clicks inside the window still open it once`() = runRowScene { opens ->
        click(at = ROW_CENTER, timeMillis = 0)
        click(at = ROW_CENTER, timeMillis = 60)
        click(at = ROW_CENTER, timeMillis = 120)
        assertEquals(1, opens(), "the triple click opened a second directory")
    }

    @Test
    fun `two clicks further apart than the window do not open it`() = runRowScene { opens ->
        click(at = ROW_CENTER, timeMillis = 0)
        click(at = ROW_CENTER, timeMillis = 900)
        assertEquals(0, opens())
    }

    private fun ImageComposeScene.click(at: Offset, timeMillis: Long) {
        sendPointerEvent(PointerEventType.Press, at, timeMillis = timeMillis)
        sendPointerEvent(PointerEventType.Release, at, timeMillis = timeMillis + 8)
    }

    /** One directory row on its own, with a counter of how often it was opened. */
    private fun runRowScene(body: ImageComposeScene.(opens: () -> Int) -> Unit) {
        var opens = 0
        ImageComposeScene(width = SCENE_WIDTH, height = SCENE_HEIGHT, density = Density(1f)).use { scene ->
            scene.setContent {
                SkerryTheme {
                    CompositionLocalProvider(
                        LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    ) {
                        Box(Modifier) {
                            LiveFileRow(
                                icon = "folder",
                                iconColor = Color.Cyan,
                                name = "html",
                                columns = FileRowColumns(permissions = null, modified = null, size = ""),
                                isSelected = false,
                                cursored = false,
                                active = true,
                                mono = FontFamily.Monospace,
                                onPress = {},
                                onDoubleClick = { opens++ },
                                directory = true,
                                rubberBand = null,
                            )
                        }
                    }
                }
            }
            Snapshot.sendApplyNotifications()
            scene.render(FIRST_FRAME_NANOS)
            scene.body { opens }
            Snapshot.sendApplyNotifications()
            scene.render(SECOND_FRAME_NANOS)
        }
    }
}

private const val SCENE_WIDTH = 400
private const val SCENE_HEIGHT = 120
private val ROW_CENTER = Offset(200f, 12f)
private const val FIRST_FRAME_NANOS = 16_666_667L
private const val SECOND_FRAME_NANOS = 33_333_334L
