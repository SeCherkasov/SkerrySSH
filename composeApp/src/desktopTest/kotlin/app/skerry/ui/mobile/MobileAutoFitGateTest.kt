package app.skerry.ui.mobile

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_autofit_grow
import app.skerry.ui.terminal.autoFitFloor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The Appearance switch governs the whole shrink-to-fit feature on the phone: whether the scale
 * reaches the glyphs, whether the −/+ chip is on screen, and what is left behind when it goes off.
 *
 * Everything here is invisible from the state machine's own unit tests, because the fit's state
 * lives on the session ([app.skerry.ui.terminal.TerminalScreenState]) and outlives the switch:
 * a gate that only stopped the *stepping* would leave a chip over a terminal that no longer
 * scales, and a switch that left `locked` behind would read as freshly enabled while no wide line
 * could ever shrink the font again. The engaged state is forced by hand — the honest convergence
 * path needs a live reflow and is covered by TerminalAutoFitLiveTest.
 */
@OptIn(ExperimentalTestApi::class)
class MobileAutoFitGateTest {

    /** The scale announcer for screen readers (see StatusAnnouncer's insertion-vs-change contract). */
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `the switch governs the scale, the chip and what the fit remembers`() = runMobileShell(withSessions = true) { shell ->
        shell.state.push(MobileRoute.Terminal)
        waitForIdle()
        val ui = shell.sessions?.active?.focusedPane?.controller?.uiState
        val connected = assertNotNull(ui as? ConnectionUiState.Connected, "the seeded session is not connected")
        // The grid the terminal settles on at the user's own font size — read after the first
        // layout resize has landed, not before it: the default 80x24 is not what is on screen.
        settle()
        val unscaled = connected.terminal.cols
        onNodeWithContentDescription(string(Res.string.term_autofit_grow)).assertDoesNotExist()

        shell.state.toggleTerminalAutoFit()
        // Applied as a snapshot: a bare write from the test thread reaches the state but not the
        // composition until something else happens to send apply notifications.
        Snapshot.withMutableSnapshot { connected.terminal.autoFit.nudgeDown(autoFitFloor(13)) }
        // The scale reaches the glyphs or it does not: a smaller font measures a wider grid, which
        // is the one effect of the switch observable without reading pixels. The wait covers the
        // resize debounce between the font change and the regrown grid.
        waitUntil("the shrunken font widens the grid", timeoutMillis = 10_000) {
            connected.terminal.cols > unscaled
        }
        onNodeWithContentDescription(string(Res.string.term_autofit_grow)).assertExists()
        val liveRegions = onAllNodes(polite).fetchSemanticsNodes().size

        shell.state.toggleTerminalAutoFit()
        settle()

        onNodeWithContentDescription(string(Res.string.term_autofit_grow)).assertDoesNotExist()
        assertEquals(unscaled, connected.terminal.cols, "the terminal kept a scale the switch had turned off")
        // The announcer keeps its mount and goes silent instead of disappearing: a live region
        // re-inserted on the next enable would arrive carrying its value rather than changing into
        // it, and Android announces only changes. Counted rather than matched by name — the screen
        // carries other polite regions, and what this pins is that none of them went away and none
        // is still reading out a scale.
        assertEquals(liveRegions, onAllNodes(polite).fetchSemanticsNodes().size, "a live region was unmounted with the chip")
        val announced = onAllNodes(polite).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .filter { it.isNotBlank() }
        assertEquals(emptyList(), announced, "a live region still announces a scale after the switch went off")
        // And the fit forgets, so the next enable is a fresh one rather than a locked no-op.
        assertEquals(1f, connected.terminal.autoFit.scale, "the fit kept its scale across the switch")
        assertFalse(connected.terminal.autoFit.locked, "a re-enable would start locked out")
    }

    /**
     * Renders past the terminal's resize debounce (a real 150ms, off the test clock) so "nothing
     * changed" is asserted over an observation window rather than a single frame.
     */
    private fun ComposeUiTest.settle() {
        repeat(40) {
            Thread.sleep(16)
            // Driven by hand: `waitForIdle` renders nothing when the tree already reads as idle,
            // and a composition left behind by a write from the test thread is exactly that.
            mainClock.advanceTimeByFrame()
        }
    }
}
