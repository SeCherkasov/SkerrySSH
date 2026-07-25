package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The host row's "⋮" menu sizes itself to its longest label, so in a language with short verbs the
 * card collapses to a sliver of text with barely any click target, and the same menu changes width
 * per row (Edit/Duplicate/Delete vs Edit only). It must never render narrower than the floor,
 * whatever the labels say.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HostMenuWidthTest {

    /**
     * Item floor (140dp) plus the card's 4dp padding on both sides, less the 1dp border that paints
     * over the fill on each edge — the run of background pixels the scan below can actually see, at
     * density 1. Without a floor the same card measures ~92px (its two labels).
     */
    private val minimumCardWidthPx = 146

    private val sceneWidth = 400f

    /** The trailing "⋮" IconBtn's box size in HostEntryRow, at density 1. */
    private val MENU_BUTTON_BOX = 22f

    // Captured from the live theme rather than hardcoded, so a change of default theme doesn't turn
    // this into a colour-literal test. Only the menu card paints surface2 in this scene: the row
    // itself is unselected (transparent), and Color equality includes alpha.
    private var cardColor = Color.Unspecified

    @Composable
    private fun RowUnderTest() {
        SkerryTheme {
            cardColor = Skerry.colors.surface2
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    HostEntryRow(
                        label = "alpha", selected = false, dot = Color.Green, badge = null,
                        onClick = {}, mono = FontFamily.Monospace, icon = "dns",
                        onEdit = {}, onDelete = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the row menu never renders narrower than its floor`() {
        ImageComposeScene(width = sceneWidth.toInt(), height = 300, density = Density(1f)).use { scene ->
            scene.setContent { RowUnderTest() }
            Snapshot.sendApplyNotifications()
            scene.render(16_666_667L)

            // Open the menu from the trailing "⋮" button. It is pinned to the row's right edge, so
            // aim half a button-box in from there rather than at a fixed coordinate — this survives
            // padding changes in HostEntryRow. If the row's trailing layout ever changes so much
            // that the click misses, the "menu didn't open" assertion below says so outright.
            val menuButton = Offset(sceneWidth - MENU_BUTTON_BOX / 2f, 13f)
            scene.sendPointerEvent(PointerEventType.Press, menuButton, timeMillis = 0)
            scene.sendPointerEvent(PointerEventType.Release, menuButton, timeMillis = 16)
            Snapshot.sendApplyNotifications()
            scene.render(33_333_334L)
            Snapshot.sendApplyNotifications()
            val pixels = scene.render(50_000_000L).toComposeImageBitmap().toPixelMap()

            // The menu card is the widest run of its background colour in the frame.
            var widest = 0
            for (y in 0 until pixels.height) {
                var run = 0
                for (x in 0 until pixels.width) {
                    if (pixels[x, y] == cardColor) run++ else { widest = maxOf(widest, run); run = 0 }
                }
                widest = maxOf(widest, run)
            }
            assertTrue(widest > 0, "the menu didn't open — the click missed the row's \"⋮\" button")
            assertTrue(
                widest >= minimumCardWidthPx,
                "the menu card must be at least ${minimumCardWidthPx}px wide, was ${widest}px",
            )
        }
    }
}
