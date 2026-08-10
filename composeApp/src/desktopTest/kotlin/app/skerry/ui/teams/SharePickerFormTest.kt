package app.skerry.ui.teams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The picker that chooses which of the account's own records to hand to a team.
 *
 * Picking the wrong row publishes the wrong secret to everyone in the team, so the one thing worth
 * pinning is that the row pressed is the item reported — the list is built from vault records whose
 * labels can look alike, and an off-by-one here is a disclosure, not a glitch.
 */
@OptIn(ExperimentalTestApi::class)
class SharePickerFormTest {

    @Test
    fun `the row pressed is the item handed over`() {
        var picked: ShareItem? = null
        runForm({ SharePickerDialog(TITLE, ITEMS, EMPTY, onPick = { picked = it }, onDismiss = {}) }) {
            onNodeWithText(ITEMS[1].label).performClick()
            waitForIdle()
        }
        assertEquals(ITEMS[1], picked)
    }

    @Test
    fun `an empty list picks nothing and says so`() {
        var picked: ShareItem? = null
        runForm({ SharePickerDialog(TITLE, emptyList(), EMPTY, onPick = { picked = it }, onDismiss = {}) }) {
            onNodeWithText(EMPTY).assertExists()
        }
        assertNull(picked)
    }
}

private const val TITLE = "Share a secret"
private const val EMPTY = "Nothing left to share"

/** Deliberately similar labels: the press has to resolve by row, not by a prefix match. */
private val ITEMS = listOf(
    ShareItem(id = "c1", label = "prod-access", detail = "certificate"),
    ShareItem(id = "c2", label = "prod-access-old", detail = "password"),
)
