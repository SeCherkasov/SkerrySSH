package app.skerry.ui.design

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.allText
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hover note a list row shows: the host sidebar, the snippet library and the terminal snippet
 * palette all reach it through [rememberRowNote], so what it does is asserted once, here, rather
 * than three times over three rows.
 */
@OptIn(ExperimentalTestApi::class)
class RowNoteTest {

    private val note = "drains the canary pool first"

    @Composable
    private fun Row(text: String?, suppressed: Boolean = false) {
        val row = rememberRowNote(text)
        Box {
            RowNoteTooltip(row, suppressed = suppressed)
            Box(Modifier.testTag(ROW).size(60.dp).hoverable(row.interaction))
        }
    }

    @Test
    fun `the note waits for the pointer to rest before it pops up`() {
        runForm({ Row(note) }) {
            hoverTheRow()
            // Straight after the pointer arrives: sweeping down a list must not flash a tooltip over
            // every row on the way.
            assertFalse(drawsNote(), "the tooltip appeared without a dwell, was ${allDrawnText()}")
            mainClock.advanceTimeBy(DWELL_MS)
            waitForIdle()
            assertTrue(drawsNote(), "the tooltip never appeared, was ${allDrawnText()}")
        }
    }

    @Test
    fun `no pointer, no note`() {
        runForm({ Row(note) }) {
            mainClock.advanceTimeBy(DWELL_MS)
            waitForIdle()
            assertFalse(drawsNote(), "was ${allDrawnText()}")
        }
    }

    @Test
    fun `a note that filters away to nothing never pops up an empty box`() {
        // Escapes, not the raw glyphs: an invisible character in source is unreviewable.
        runForm({ Row("\u200B\u202E") }) {
            hoverTheRow()
            mainClock.advanceTimeBy(DWELL_MS)
            waitForIdle()
            // By root count, not by drawn text: an empty tooltip is a popup drawing an empty string,
            // which reads as "nothing" to any text assertion.
            assertEquals(1, roots(), "an empty tooltip popped up, drew ${allDrawnText()}")
        }
    }

    @Test
    fun `a row with its own popup up keeps the note out of the way`() {
        runForm({ Row(note, suppressed = true) }) {
            hoverTheRow()
            mainClock.advanceTimeBy(DWELL_MS)
            waitForIdle()
            assertFalse(drawsNote(), "the note landed on top of the row's own popup, was ${allDrawnText()}")
        }
    }

    private fun ComposeUiTest.hoverTheRow() {
        onNodeWithTag(ROW).performMouseInput { moveTo(Offset(30f, 30f)) }
        waitForIdle()
    }

    /** Every root, not the first: the tooltip is a Popup, and a popup is a root of its own. */
    private fun ComposeUiTest.allDrawnText(): List<String> =
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.allText() }

    private fun ComposeUiTest.roots(): Int = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.drawsNote(): Boolean = allDrawnText().any { it.contains(note) }

    private companion object {
        const val ROW = "row-note-test-row"

        /** Longer than the dwell in [rememberRowNote], so the test asserts "it came up", not "when". */
        const val DWELL_MS = 900L
    }
}
