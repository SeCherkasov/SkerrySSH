package app.skerry.ui.vnc

import kotlin.math.abs

/**
 * Accumulates fractional wheel deltas into whole notches (F-14): a trackpad reports fractions that
 * used to round to one-notch-or-nothing, and a three-line wheel step used to scroll one. The
 * fraction left over is carried into the next sample; whole notches over the per-event bound are
 * dropped rather than carried — see [add].
 */
internal class WheelCarry {
    private var carry = 0f

    /**
     * Add a raw delta; returns the whole notches now due (sign = direction), keeping the fraction.
     *
     * Bounded per event: one fling on a high-resolution trackpad can accumulate a large delta, and
     * every notch is two writes the input actor sends without pacing — an unbounded burst would sit
     * in front of the click or keystroke that follows it. What is over the bound is dropped, not
     * banked: a remainder outlives the gesture that made it, and the next scroll — in the opposite
     * direction as easily as the same one — would spend it first and go the wrong way.
     */
    fun add(delta: Float): Int {
        carry += delta
        val whole = carry.toInt()
        // Only the fraction is kept, whether or not the bound bit.
        carry -= whole.toFloat()
        return whole.coerceIn(-MAX_STEPS_PER_EVENT, MAX_STEPS_PER_EVENT)
    }

    private companion object {
        /** More than any real wheel reports in one event, less than a burst that blocks the queue. */
        const val MAX_STEPS_PER_EVENT = 8
    }
}

/**
 * The pointer masks one wheel gesture sends: a press+release pair per notch, with [buttons] — the
 * buttons currently held — riding on every mask. RFB's mask is absolute, so ending the pair with a
 * bare 0 released whatever drag was in progress (F-38).
 */
internal fun wheelMasks(buttons: Int, steps: Int, negative: Int, positive: Int): List<Int> {
    if (steps == 0) return emptyList()
    val bit = if (steps < 0) negative else positive
    return buildList {
        repeat(abs(steps)) {
            add(buttons or bit)
            add(buttons)
        }
    }
}
