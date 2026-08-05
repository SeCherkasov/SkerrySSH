package app.skerry.ui.vault

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a trash row puts Restore / Delete forever. Inline they are unweighted children of the row,
 * so on a narrow screen they take the whole width and leave the label none — which rendered the
 * label one character per line.
 */
class TrashRowLayoutTest {

    @Test
    fun a_phone_row_stacks_the_actions_under_the_label() {
        // 390dp screen − 18dp gutters − 12dp card padding ≈ 330dp of row.
        assertTrue(trashActionsStacked(330.dp))
    }

    @Test
    fun a_desktop_settings_panel_keeps_them_on_one_line() {
        assertFalse(trashActionsStacked(720.dp))
    }

    @Test
    fun the_threshold_leaves_the_label_a_readable_column() {
        // Just under the limit still stacks; at it, the label has room beside both buttons.
        assertTrue(trashActionsStacked(TRASH_ROW_INLINE_MIN_WIDTH - 1.dp))
        assertFalse(trashActionsStacked(TRASH_ROW_INLINE_MIN_WIDTH))
    }

    @Test
    fun a_degenerate_width_stacks_rather_than_squeezing() {
        // A row measured at zero (first frame of a subcomposition) must not pick the inline layout.
        assertTrue(trashActionsStacked(0.dp))
    }
}
