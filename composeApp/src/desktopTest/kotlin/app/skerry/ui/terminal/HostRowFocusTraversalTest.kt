package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for "Tab needs two presses to reach the next host": every catalog host row
 * carries a trailing "⋮" menu button, and if that button participates in focus traversal the Tab
 * order becomes row -> its "⋮" -> next row. RECENT rows have no menu button, which is why they
 * moved in one press — host rows must match: one Tab per row, the menu stays mouse-only.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class HostRowFocusTraversalTest {

    @Test
    fun tabMovesFocusToNextHostRowInOnePress() {
        var firstHasFocus = false
        var secondHasFocus = false
        ImageComposeScene(width = 400, height = 300, density = Density(1f)).use { scene ->
            scene.setContent {
                SkerryTheme {
                    CompositionLocalProvider(
                        LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    ) {
                        Column {
                            FocusTrackedRow(label = "alpha") { firstHasFocus = it }
                            FocusTrackedRow(label = "beta") { secondHasFocus = it }
                        }
                    }
                }
            }
            var timeNanos = 0L
            fun frame() {
                Snapshot.sendApplyNotifications()
                timeNanos += 16_666_667L
                scene.render(timeNanos)
            }
            frame()
            fun tab() {
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
                frame()
            }

            tab()
            assertTrue(firstHasFocus, "first Tab should focus the first host row")
            assertFalse(secondHasFocus, "second row must not be focused yet")

            tab()
            assertTrue(
                secondHasFocus,
                "one Tab from a host row must land on the NEXT row — not on the row's ⋮ menu button",
            )
            assertFalse(firstHasFocus, "focus must have left the first row entirely")
        }
    }

    @Composable
    private fun FocusTrackedRow(label: String, onFocus: (Boolean) -> Unit) {
        Box(Modifier.onFocusChanged { onFocus(it.hasFocus) }) {
            HostEntryRow(
                label = label, selected = false, dot = Color.Green, badge = null,
                onClick = {}, mono = FontFamily.Monospace, icon = "dns",
                onEdit = {}, onDelete = {},
            )
        }
    }
}
