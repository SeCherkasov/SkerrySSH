package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermSnapshotRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-fit machine (issue #180): wide soft-wrapped output on a phone shrinks the font in steps
 * until it fits, converges once per session, then only the manual buttons move it. The machine is
 * pure state — [TerminalScreen] feeds it one settled screen at a time — so the convergence rules
 * are tested here without composition.
 */
class TerminalAutoFitTest {

    private val tolerance = 1e-4f

    // -- convergence ------------------------------------------------------------------------------

    @Test
    fun `without a wide line the scale never moves`() {
        val fit = TerminalAutoFitState()
        repeat(5) { fit.onScreenSettled(wrapped = false, floor = 0.5f) }
        assertEquals(1f, fit.scale)
        assertFalse(fit.converged)
        assertFalse(fit.active)
    }

    @Test
    fun `wide output shrinks step by step, takes one margin step, then locks in`() {
        val fit = TerminalAutoFitState()
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertEquals(0.9f, fit.scale, tolerance)
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertEquals(0.9f * 0.9f, fit.scale, tolerance)
        // Fits now — one extra margin step so nothing sits right at the edge…
        fit.onScreenSettled(wrapped = false, floor = 0.5f)
        assertEquals(0.9f * 0.9f * 0.9f, fit.scale, tolerance)
        assertFalse(fit.converged)
        // …and the settled margin screen locks it in.
        fit.onScreenSettled(wrapped = false, floor = 0.5f)
        assertTrue(fit.converged)
        // A later wide line must not move a converged scale — that is the whole point.
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertEquals(0.9f * 0.9f * 0.9f, fit.scale, tolerance)
    }

    @Test
    fun `shrinking clamps at the floor and still converges once output fits`() {
        val fit = TerminalAutoFitState()
        repeat(10) { fit.onScreenSettled(wrapped = true, floor = 0.8f) }
        assertEquals(0.8f, fit.scale)
        fit.onScreenSettled(wrapped = false, floor = 0.8f) // margin step is a no-op at the floor
        assertEquals(0.8f, fit.scale)
        fit.onScreenSettled(wrapped = false, floor = 0.8f)
        assertTrue(fit.converged)
    }

    @Test
    fun `a re-wrap during the margin step resumes shrinking`() {
        val fit = TerminalAutoFitState()
        fit.onScreenSettled(wrapped = true, floor = 0.5f) // 0.9
        fit.onScreenSettled(wrapped = false, floor = 0.5f) // margin -> 0.81
        fit.onScreenSettled(wrapped = true, floor = 0.5f) // wider line arrived -> keep shrinking
        assertEquals(0.9f * 0.9f * 0.9f, fit.scale, tolerance)
        assertFalse(fit.converged)
        fit.onScreenSettled(wrapped = false, floor = 0.5f) // margin again
        fit.onScreenSettled(wrapped = false, floor = 0.5f)
        assertTrue(fit.converged)
    }

    // -- manual control ---------------------------------------------------------------------------

    @Test
    fun `a manual nudge locks auto convergence out for the session`() {
        val fit = TerminalAutoFitState()
        fit.nudgeDown(floor = 0.5f)
        assertTrue(fit.locked)
        assertEquals(0.9f, fit.scale, tolerance)
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertEquals(0.9f, fit.scale, tolerance)
    }

    @Test
    fun `nudges stop at the floor and at the user's own size`() {
        val fit = TerminalAutoFitState()
        repeat(20) { fit.nudgeDown(floor = 0.7f) }
        assertEquals(0.7f, fit.scale)
        repeat(20) { fit.nudgeUp() }
        assertEquals(1f, fit.scale)
    }

    @Test
    fun `controls engage only after the scale first moves`() {
        val fit = TerminalAutoFitState()
        assertFalse(fit.active)
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertTrue(fit.active)
    }

    @Test
    fun `a manual nudge back to full size keeps the controls engaged`() {
        val fit = TerminalAutoFitState()
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        fit.nudgeUp()
        fit.nudgeUp()
        assertEquals(1f, fit.scale)
        // locked at 100% — auto-fit stays handed over, and the controls must not vanish under
        // the finger that just tapped them.
        assertTrue(fit.active)
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertEquals(1f, fit.scale)
    }

    @Test
    fun `restore-full returns to the user's size in one step and locks`() {
        val fit = TerminalAutoFitState()
        repeat(5) { fit.onScreenSettled(wrapped = true, floor = 0.5f) }
        fit.restoreFull()
        assertEquals(1f, fit.scale)
        assertTrue(fit.locked)
        assertTrue(fit.active)
        fit.onScreenSettled(wrapped = true, floor = 0.5f)
        assertEquals(1f, fit.scale)
    }

    // -- line height ------------------------------------------------------------------------------

    @Test
    fun `the shrunk line height tightens to 1_2 but never past the user's own ratio`() {
        assertEquals(1.2f, autoFitLineHeightRatio(18f / 13f), tolerance) // default 1.385 tightens
        assertEquals(1.05f, autoFitLineHeightRatio(1.05f), tolerance) // denser user ratio wins
        assertEquals(1.2f, autoFitLineHeightRatio(2f), tolerance)
    }

    // -- floor ------------------------------------------------------------------------------------

    @Test
    fun `the floor is the 6px equivalent of the user's font size`() {
        assertEquals(6f / 13f, autoFitFloor(13), tolerance)
        assertEquals(0.75f, autoFitFloor(8), tolerance)
        // Degenerate sizes never produce a floor above 1 (no auto-*grow*).
        assertTrue(autoFitFloor(4) <= 1f)
        assertTrue(autoFitFloor(0) <= 1f)
    }

    // -- wrapped-row detection --------------------------------------------------------------------

    private fun row(text: String = " ", wrapped: Boolean = false): List<TermCell> =
        TermSnapshotRow(text.map { TermCell(it) }, wrapped)

    @Test
    fun `a wrapped row in the live grid asks to shrink`() {
        val screen = listOf(row(), row(wrapped = true), row(), row())
        assertTrue(gridNeedsShrink(screen, rows = 4, cursorRow = 3))
    }

    @Test
    fun `a wrapped row that lives only in scrollback does not`() {
        // 2 scrollback rows (the first wrapped) + a clean 3-row grid: history must not drive the
        // font to the floor over a line that long since scrolled away.
        val screen = listOf(row(wrapped = true), row(), row(), row(), row())
        assertFalse(gridNeedsShrink(screen, rows = 3, cursorRow = 4))
    }

    @Test
    fun `the line being typed under the cursor does not shrink the font`() {
        // The user's own command line soft-wraps while being typed: rows 2-3 are one logical line
        // with the cursor on its tail. Shrinking mid-typing would yank the font under the finger.
        val screen = listOf(row(), row(), row(wrapped = true), row())
        assertFalse(gridNeedsShrink(screen, rows = 4, cursorRow = 3))
    }

    @Test
    fun `the cursor sitting mid-line excludes the whole logical line`() {
        // One logical line spread over rows 1-3 (two wrap cuts), cursor parked on the middle row.
        val screen = listOf(row(), row(wrapped = true), row(wrapped = true), row())
        assertFalse(gridNeedsShrink(screen, rows = 4, cursorRow = 2))
    }

    @Test
    fun `a wide line above the cursor's own wrapped line still shrinks`() {
        val screen = listOf(row(wrapped = true), row(), row(wrapped = true), row())
        assertTrue(gridNeedsShrink(screen, rows = 4, cursorRow = 3))
    }

    @Test
    fun `empty screens and an out-of-range cursor are safe`() {
        assertFalse(gridNeedsShrink(emptyList(), rows = 4, cursorRow = 0))
        // The cursor index clamps into the buffer; the wide line sits apart from either edge row,
        // so it still counts after clamping in both directions.
        val screen = listOf(row(), row(wrapped = true), row(), row())
        assertTrue(gridNeedsShrink(screen, rows = 4, cursorRow = 99))
        assertTrue(gridNeedsShrink(screen, rows = 4, cursorRow = -5))
    }
}
