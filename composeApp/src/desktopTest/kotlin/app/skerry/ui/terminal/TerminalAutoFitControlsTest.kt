package app.skerry.ui.terminal

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_autofit_grow
import app.skerry.ui.generated.resources.term_autofit_shrink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manual −/+ nudge over the mobile terminal: stays out of the way until auto-fit engages,
 * then actually moves the machine — a row that renders but taps into nothing is what a render
 * test cannot see.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalAutoFitControlsTest {

    private val floor = autoFitFloor(13)

    /** The scale announcer for screen readers (see StatusAnnouncer's insertion-vs-change contract). */
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `nothing is drawn until the fit engages`() {
        val fit = TerminalAutoFitState()
        runForm({ TerminalAutoFitControls(fit, floor) }) {
            onNodeWithText("100%").assertDoesNotExist()
            onNodeWithContentDescription(string(Res.string.term_autofit_grow)).assertDoesNotExist()
            onNodeWithContentDescription(string(Res.string.term_autofit_shrink)).assertDoesNotExist()
            // The announcer must already be composed, silent: appearing together with its first
            // value would be an insertion, and Android announces only changes.
            onNode(polite).assertContentDescriptionEquals("")
        }
    }

    @Test
    fun `the engaged scale reaches the screen-reader announcer`() {
        val fit = TerminalAutoFitState()
        fit.onScreenSettled(wrapped = true, floor = floor)
        runForm({ TerminalAutoFitControls(fit, floor) }) {
            onNode(polite).assertContentDescriptionEquals("90%")
        }
    }

    @Test
    fun `a tap on plus steps the scale up and takes over from the machine`() {
        val fit = TerminalAutoFitState()
        fit.onScreenSettled(wrapped = true, floor = floor)
        runForm({ TerminalAutoFitControls(fit, floor) }) {
            onNodeWithText("90%").assertExists()
            onNodeWithContentDescription(string(Res.string.term_autofit_grow)).performClick()
            waitForIdle()
            assertEquals(1f, fit.scale)
            assertTrue(fit.locked)
            // Back at 100% there is nothing to grow into — the "+" hides rather than sit dead.
            onNodeWithText("100%").assertExists()
            onNodeWithContentDescription(string(Res.string.term_autofit_grow)).assertDoesNotExist()
        }
    }

    @Test
    fun `a long press on plus restores the user's size in one gesture`() {
        val fit = TerminalAutoFitState()
        repeat(4) { fit.onScreenSettled(wrapped = true, floor = floor) }
        runForm({ TerminalAutoFitControls(fit, floor) }) {
            onNodeWithContentDescription(string(Res.string.term_autofit_grow))
                .performTouchInput { longClick() }
            waitForIdle()
            assertEquals(1f, fit.scale)
            assertTrue(fit.locked)
        }
    }

    @Test
    fun `a tap on minus steps the scale down and the button hides at the floor`() {
        val fit = TerminalAutoFitState()
        fit.onScreenSettled(wrapped = true, floor = floor)
        runForm({ TerminalAutoFitControls(fit, floor) }) {
            onNodeWithContentDescription(string(Res.string.term_autofit_shrink)).performClick()
            waitForIdle()
            assertEquals(0.9f * 0.9f, fit.scale, absoluteTolerance = 1e-4f)
            assertTrue(fit.locked)
            // Drive it to the floor: the "−" must disappear, "+" must stay.
            while (fit.scale > floor) fit.nudgeDown(floor)
            waitForIdle()
            onNodeWithContentDescription(string(Res.string.term_autofit_shrink)).assertDoesNotExist()
            onNodeWithContentDescription(string(Res.string.term_autofit_grow)).assertExists()
        }
    }
}
