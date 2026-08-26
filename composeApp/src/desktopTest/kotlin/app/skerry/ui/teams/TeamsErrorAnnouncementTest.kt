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

    /**
     * Issue #324: a removal can fail two rotations and the reason names one of them. The count of
     * keys the removed member kept is said in the same line and the same announcement — a second
     * live region on one screen is heard as a second, unrelated failure.
     */
    @Test
    fun `keys a removal left un-rotated are drawn and spoken with the reason`() {
        runForm({ TeamsErrorLine(TeamsFailure.Network, size = 11.sp, unrotatedKeys = 2) }) {
            waitForIdle()
            val spoken = onNode(polite).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].first()
            assertTrue(spoken.contains("2"), "the second key nobody rotated is not reported: $spoken")
            onNodeWithText(spoken).assertExists()
        }
    }

    /** Nothing to add when every key rotated: the reason stands on its own. */
    @Test
    fun `a failure with no un-rotated keys says only the reason`() {
        val reason = mutableListOf<String>()
        runForm({ reason += teamsFailureText(TeamsFailure.Network) }) { waitForIdle() }
        runForm({ TeamsErrorLine(TeamsFailure.Network, size = 11.sp) }) {
            waitForIdle()
            val spoken = onNode(polite).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].first()
            assertTrue(spoken == reason.first(), "an empty count still changed the line: $spoken")
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
