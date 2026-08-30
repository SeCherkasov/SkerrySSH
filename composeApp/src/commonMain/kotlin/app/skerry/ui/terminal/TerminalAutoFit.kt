package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.wrapsToNextRow
import app.skerry.ui.design.GlyphButton
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_autofit_grow
import app.skerry.ui.generated.resources.term_autofit_restore
import app.skerry.ui.generated.resources.term_autofit_shrink
import app.skerry.ui.theme.Skerry
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/** One auto/manual step of the fit scale (×0.9 ≈ 1px of font at the default 13px size). */
private const val AUTOFIT_STEP = 0.9f

/**
 * Never auto-shrink below this font size (px). Deliberately under the Appearance slider's 8px
 * minimum: the shrunken view exists to *read wide output* on a phone, which needs smaller than
 * a size anyone would type at all day.
 */
private const val AUTOFIT_MIN_PX = 6f

private const val AUTOFIT_LINE_HEIGHT_RATIO = 1.2f

/**
 * Line-height ratio to use while auto-shrunk: tightened toward the glyph height, Termux-style —
 * the user's ratio (18/13 by default) is tuned for a reading size and leaves visually huge gaps
 * at 7-8px. Never looser than the user's own Appearance ratio though, which would *gain* row gap
 * the moment the fit engages for anyone who configured a ratio denser than 1.2. Restored together
 * with the font size.
 */
fun autoFitLineHeightRatio(userRatio: Float): Float = minOf(userRatio, AUTOFIT_LINE_HEIGHT_RATIO)

/** Smallest allowed auto-fit scale for [baseFontSizeSp] — the [AUTOFIT_MIN_PX] floor. */
fun autoFitFloor(baseFontSizeSp: Int): Float =
    (AUTOFIT_MIN_PX / baseFontSizeSp.coerceAtLeast(1)).coerceAtMost(1f)

/**
 * Auto-shrink for narrow screens, Termux "shrink to fit"-style (issue #180): when the live grid
 * holds soft-wrapped rows, the font scales down in steps until the output fits. Unlike Termux's
 * live loop this converges **once per session and then sticks** — a continuous restore loop would
 * re-wrap at the boundary and oscillate. After convergence only [nudgeDown]/[nudgeUp] (the manual
 * −/+ buttons) move the scale, and the first tap hands control over for good ([locked]).
 *
 * One instance lives on [TerminalScreenState] — it must survive tab switches (composition-local
 * state resets when the active session changes and back) and die with the session.
 *
 * The machine itself is pure: [TerminalScreen] calls [onScreenSettled] once per *settled* snapshot
 * — one whose grid width already reflects the current scale. Feeding it mid-reflow snapshots would
 * double-step past the fit, which is why the caller gates on the expected column count.
 */
@Stable
class TerminalAutoFitState {

    private enum class Phase {
        /** No wide line seen yet — the font stays at the user's own size. */
        Waiting,

        /** Wide rows on screen — stepping down until they fit. */
        Shrinking,

        /** Output fits and the one extra margin step is applied — the next settled screen locks in. */
        Overshot,

        /** Done for this session: only the manual buttons move the scale now. */
        Converged,
    }

    private var phase = Phase.Waiting

    /** Current font scale, 1 = the user's own size. Applied by [TerminalScreen] while auto-fit is on. */
    var scale: Float by mutableStateOf(1f)
        private set

    /** True once a manual button was tapped — auto convergence is out for the rest of the session. */
    var locked: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the manual controls should be visible: the scale has moved, or the user already took
     * over. A session that never printed a wide line keeps its terminal free of chrome.
     */
    val active: Boolean get() = scale < 1f || locked

    /** True once auto-fit finished for this session (visible for tests/diagnostics). */
    val converged: Boolean get() = phase == Phase.Converged

    /**
     * Advance on a settled screen: [wrapped] — whether the live grid still holds a soft-wrapped
     * row (see [gridNeedsShrink]), [floor] — [autoFitFloor] of the user's font size.
     */
    fun onScreenSettled(wrapped: Boolean, floor: Float) {
        if (locked || phase == Phase.Converged) return
        if (wrapped) {
            // Still (or again) too wide: step down. Re-entering from Overshot restarts the margin
            // logic — a wider line arrived while the previous fit was settling.
            phase = Phase.Shrinking
            scale = stepDown(scale, floor)
            return
        }
        // Exhaustive on purpose: a future phase must choose its fits-now behavior explicitly
        // instead of falling into a silent default.
        when (phase) {
            Phase.Waiting -> Unit // nothing wide has appeared yet
            Phase.Shrinking -> {
                // Fits now — one extra margin step so a line ending at the very edge (or a slightly
                // wider one arriving next) doesn't sit on the wrap boundary.
                phase = Phase.Overshot
                scale = stepDown(scale, floor)
            }
            Phase.Overshot -> phase = Phase.Converged
            Phase.Converged -> Unit // unreachable — filtered by the early return above
        }
    }

    /** Manual "−": one step down. The first tap takes convergence away from the machine. */
    fun nudgeDown(floor: Float) {
        locked = true
        scale = stepDown(scale, floor)
    }

    /** Manual "+": one step back up, never past the user's own size. */
    fun nudgeUp() {
        locked = true
        scale = (scale / AUTOFIT_STEP).coerceAtMost(1f)
    }

    /**
     * Long-press on "+": straight back to the user's own size in one gesture — from a deep floor
     * the single-step button takes a dozen taps, a real cost under a motor impairment. Stays
     * [locked]: an explicit restore is the strongest "hands off" signal there is.
     */
    fun restoreFull() {
        locked = true
        scale = 1f
    }

    /**
     * Back to what a fresh session has: the user's own size, nothing locked, nothing converged.
     * Called when the Appearance switch turns the fit off — the machine's memory is only meaningful
     * while the feature is on, and carrying [locked] across an off/on cycle would leave a switch
     * that reads as freshly enabled driving a fit that can never engage again for that session.
     */
    fun reset() {
        phase = Phase.Waiting
        scale = 1f
        locked = false
    }

    private fun stepDown(value: Float, floor: Float): Float =
        (value * AUTOFIT_STEP).coerceAtLeast(floor.coerceAtMost(1f))
}

/**
 * Whether the live grid asks for a smaller font: any soft-wrapped row among the last [rows] rows
 * of [screen] (the visible grid — scrollback above it must not drive the font to the floor over a
 * line that long since scrolled away), excluding the logical line the cursor sits on — the user's
 * own command line soft-wraps while being typed, and shrinking mid-keystroke would yank the font
 * under their fingers.
 */
fun gridNeedsShrink(screen: List<List<TermCell>>, rows: Int, cursorRow: Int): Boolean {
    if (screen.isEmpty()) return false
    val gridStart = (screen.size - rows).coerceAtLeast(0)
    // The cursor's logical line: walk to the first row of the wrap chain, then to its last cut.
    // Rows lineStart..lineEnd-1 carry the chain's wrapped flags and are the ones to ignore.
    // Both the clamp and the walk stop at gridStart: the scan below never looks above it, and
    // a host that streams one endless line chains wrap flags through the whole scrollback —
    // an unbounded walk on every snapshot (the same trap MAX_JOINED_WRAP_ROWS exists for).
    val cursor = cursorRow.coerceIn(gridStart, screen.lastIndex)
    var lineStart = cursor
    while (lineStart > gridStart && screen[lineStart - 1].wrapsToNextRow()) lineStart--
    var lineEnd = cursor
    while (lineEnd < screen.lastIndex && screen[lineEnd].wrapsToNextRow()) lineEnd++
    for (i in gridStart until screen.size) {
        if (i >= lineStart && i < lineEnd) continue
        if (screen[i].wrapsToNextRow()) return true
    }
    return false
}

/**
 * Manual −/+ nudge over the mobile terminal, with the current scale as a percentage. Nothing is
 * drawn while the feature is off ([enabled]) or until the fit first engages ([TerminalAutoFitState.active]) — a session that never printed
 * a wide line keeps its screen clean. Buttons that can do nothing are hidden, not disabled: "−"
 * disappears at the floor and "+" at the user's own size, since a permanently dead button under
 * the thumb reads as broken. Long-pressing "+" restores the user's size in one gesture.
 *
 * The chip background is 0.95 alpha with fully opaque text — the overlay floats over arbitrary
 * terminal pixels, and stacking a translucent text on a translucent chip fell under AA contrast
 * in the light themes (the RemoteDesktopBar precedent).
 */
@Composable
fun TerminalAutoFitControls(
    fit: TerminalAutoFitState,
    floor: Float,
    /**
     * The Appearance switch. Passed in rather than gating the call site, so the announcer below
     * keeps its mount while the feature is off: a caller that skips this composable entirely
     * re-inserts the live region on re-enable, which is the one thing it must never do.
     */
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // The announcer is composed from screen mount, BEFORE the active gate: a live region that
    // appears together with its first value is an insertion, not a change, and Android announces
    // only changes — the auto-engage moment (the one change the user did not cause themselves)
    // would stay silent. StatusAnnouncer also carries the size workaround a 0dp node needs.
    val on = enabled && fit.active
    StatusAnnouncer(if (on) "${(fit.scale * 100).roundToInt()}%" else "")
    if (!on) return
    val chip = Skerry.colors.surface2.copy(alpha = 0.95f)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            "${(fit.scale * 100).roundToInt()}%",
            color = Skerry.colors.textBright,
            size = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(chip)
                .padding(horizontal = 8.dp, vertical = 9.dp),
        )
        if (fit.scale > floor) {
            GlyphButton(
                icon = "remove",
                label = stringResource(Res.string.term_autofit_shrink),
                onClick = { fit.nudgeDown(floor) },
                box = 44.dp,
                iconSize = 20.sp,
                iconColor = Skerry.colors.text,
                background = chip,
            )
        }
        if (fit.scale < 1f) {
            GlyphButton(
                icon = "add",
                label = stringResource(Res.string.term_autofit_grow),
                onClick = { fit.nudgeUp() },
                box = 44.dp,
                iconSize = 20.sp,
                iconColor = Skerry.colors.text,
                background = chip,
                onLongClick = { fit.restoreFull() },
                onLongClickLabel = stringResource(Res.string.term_autofit_restore),
            )
        }
    }
}
