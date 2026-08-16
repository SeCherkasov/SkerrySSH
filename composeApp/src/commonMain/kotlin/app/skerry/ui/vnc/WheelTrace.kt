package app.skerry.ui.vnc

import androidx.compose.ui.input.pointer.PointerType

/**
 * Wheel-input trace for issue #265 (inconsistent scrolling): raw `scrollDelta` per device →
 * emitted notches, plus scrolls dropped over the letterbox. The issue's three hypotheses differ
 * exactly in these numbers, so the fix is gated on this trace naming the culprit on a live
 * machine. Off unless `-Dskerry.wheelTrace=1` (desktop; same switch style as
 * `skerry.remote.statsTrace`), so the line is built lazily.
 */
internal expect fun wheelTrace(line: () -> String)

/** One raw scroll event: the device it came from and its unprocessed per-axis deltas. */
internal class WheelSample(val type: PointerType, val deltaX: Float, val deltaY: Float)

/** One scroll event as a trace line: device, raw deltas, notches due, masks sent, held buttons. */
internal fun formatWheelTrace(sample: WheelSample, notchesX: Int, notchesY: Int, masks: Int, held: Int): String =
    "${sample.type} delta=${sample.deltaX},${sample.deltaY} notches=$notchesX,$notchesY masks=$masks held=$held"

/** A scroll that never reached the server because the pointer sat outside the fitted image. */
internal fun formatWheelDrop(sample: WheelSample): String =
    "${sample.type} delta=${sample.deltaX},${sample.deltaY} dropped over letterbox"
