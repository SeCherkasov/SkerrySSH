package app.skerry.ui.teams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Teams failure arrives after the dialog that caused it is gone — invite closes on the click, and
 * the refusal #316 introduced lands a round-trip later. The sidebar line is the only report of it,
 * so it has to be spoken as well as drawn (WCAG 4.1.3), by a node that was already there when the
 * message changed.
 */
@OptIn(ExperimentalTestApi::class)
class TeamsErrorAnnouncementTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `a failure that lands after the dialog closed is announced`() {
        var failure by mutableStateOf<TeamsFailure?>(null)
        runForm({ TeamsErrorLine(failure, size = 11.sp) }) {
            onNode(polite).assertContentDescriptionEquals("")
            failure = TeamsFailure.RecipientKeyChanged
            waitForIdle()
            // The same node, a new description: a status change, not a node appearing.
            val spoken = onNode(polite).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].first()
            assertTrue(spoken.isNotBlank(), "the refusal is drawn but never spoken")
            onNodeWithText(spoken).assertExists()
        }
    }

    /** Every failure has something to say: an unspoken one reads as the operation having done nothing. */
    @Test
    fun `every teams failure announces something`() {
        val spoken = mutableMapOf<TeamsFailure, String>()
        runForm({ TeamsFailure.entries.forEach { spoken[it] = teamsFailureText(it) } }) { waitForIdle() }
        for (failure in TeamsFailure.entries) {
            assertTrue(spoken[failure].orEmpty().isNotBlank(), "$failure announces nothing")
        }
        val heard = spoken.values.toList()
        assertTrue(heard.size == heard.toSet().size, "two failures say the same sentence: $heard")
    }
}
