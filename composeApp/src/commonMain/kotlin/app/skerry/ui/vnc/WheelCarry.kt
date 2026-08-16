package app.skerry.ui.vnc

import kotlin.math.abs

/**
 * Accumulates fractional wheel deltas into whole notches (F-14): a trackpad reports fractions that
 * used to round to one-notch-or-nothing, and a three-line wheel step used to scroll one. The
 * remainder is carried, never dropped.
 */
internal class WheelCarry {
    private var carry = 0f

    /** Add a raw delta; returns the whole notches now due (sign = direction), keeping the rest. */
    fun add(delta: Float): Int {
        carry += delta
        val steps = carry.toInt()
        carry -= steps
        return steps
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
