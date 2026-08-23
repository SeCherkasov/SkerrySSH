package app.skerry.shared.rdp

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the client tells the server about its display scaling: the monitor's physical size and the
 * two scale factors that MS-RDPBCGR 2.2.1.3.2 (connect time) and MS-RDPEDISP 2.2.2.2.1 (a running
 * session) carry side by side.
 *
 * They exist because a resolution alone does not say how large a pixel is. A viewport measured on a
 * HiDPI screen is the desktop in *physical* pixels, and a server told only that number renders a
 * 96 dpi desktop into it — the session comes out sharp and every glyph in it half the size of the
 * client's own UI. Sending the scale is what makes the remote side draw at the local DPI instead.
 *
 * All four fields travel together on purpose: both specs say the factors are ignored unless the
 * physical size is inside 10..10000 mm, so a layout with the factors alone changes nothing. [NONE]
 * is the "say nothing" value — four zeros, which every server ignores by the same rule.
 */
data class RdpDisplayScale(
    val physicalWidthMm: Int,
    val physicalHeightMm: Int,
    val desktopScaleFactor: Int,
    val deviceScaleFactor: Int,
) {
    companion object {
        /** Every field zero: the server keeps its own DPI, which is what an unscaled client wants. */
        val NONE = RdpDisplayScale(0, 0, 0, 0)

        /**
         * The scaling fields for a [widthPx]×[heightPx] viewport drawn at [scale] (1.0 = 100%, the
         * value the platform reports for its display), or [NONE] when there is nothing worth
         * saying — an unscaled display, a nonsensical scale, or a physical size the server would
         * refuse.
         *
         * The scale is capped so the desktop the remote side lays out never falls below
         * [MIN_LOGICAL_WIDTH]×[MIN_LOGICAL_HEIGHT]: a phone reports a density near 3, and a Windows
         * desktop of 780×360 logical pixels cannot hold the windows it is asked to show. Better a
         * smaller scale than a session no dialog fits in.
         */
        fun of(widthPx: Int, heightPx: Int, scale: Float): RdpDisplayScale {
            if (widthPx <= 0 || heightPx <= 0 || !scale.isFinite()) return NONE
            val room = min(widthPx.toDouble() / MIN_LOGICAL_WIDTH, heightPx.toDouble() / MIN_LOGICAL_HEIGHT)
            // The protocol's ceiling is applied here, not to the factor alone: the millimetres below
            // are derived from the same number, and a size that disagrees with the factor it travels
            // with is a DPI neither side meant.
            val capped = min(min(scale.toDouble(), room), MAX_FACTOR / 100.0)
            if (capped < MIN_SCALE) return NONE
            // Down to the whole percent the protocol carries, never up: rounding up would push the
            // desktop back under the minimum the cap above just protected.
            val factor = floor(capped * 100).toInt().coerceIn(MIN_FACTOR, MAX_FACTOR)
            val applied = factor / 100.0
            // The size a screen of these pixels would have at the DPI this factor implies. It is a
            // derived number rather than a measured one, and it has to be: the platforms report a
            // scale, not millimetres, and without a plausible size the factors are discarded. It is
            // derived from [applied] so the size and the factor state the same DPI.
            val widthMm = (widthPx * MM_PER_INCH / (BASE_DPI * applied)).roundToInt()
            val heightMm = (heightPx * MM_PER_INCH / (BASE_DPI * applied)).roundToInt()
            if (widthMm !in MIN_PHYSICAL_MM..MAX_PHYSICAL_MM) return NONE
            if (heightMm !in MIN_PHYSICAL_MM..MAX_PHYSICAL_MM) return NONE
            return RdpDisplayScale(widthMm, heightMm, factor, deviceFactor(applied))
        }

        /** The nearest of the three values the protocol allows; anything else is ignored outright. */
        private fun deviceFactor(scale: Double): Int = when {
            scale < 1.2 -> DEVICE_100
            scale < 1.6 -> DEVICE_140
            else -> DEVICE_180
        }

        /** Below this the client is not scaled at all and has nothing to report. */
        private const val MIN_SCALE = 1.01

        /** MS-RDPBCGR 2.2.1.3.2: outside 100..500 percent the factor is ignored. */
        const val MIN_FACTOR = 100
        const val MAX_FACTOR = 500

        /** The only three values DeviceScaleFactor may take. */
        const val DEVICE_100 = 100
        const val DEVICE_140 = 140
        const val DEVICE_180 = 180

        /** Outside 10..10000 mm the physical size — and with it both factors — is ignored. */
        const val MIN_PHYSICAL_MM = 10
        const val MAX_PHYSICAL_MM = 10_000

        /** The smallest desktop, in the remote side's logical pixels, a scale may leave. */
        const val MIN_LOGICAL_WIDTH = 1024
        const val MIN_LOGICAL_HEIGHT = 640

        /** Windows counts a logical pixel as 1/96 inch; the client's scale is relative to that. */
        private const val BASE_DPI = 96.0
        private const val MM_PER_INCH = 25.4
    }
}
