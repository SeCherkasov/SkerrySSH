package app.skerry.ui.vnc

import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The wheel trace exists to decide issue #265, so the line must carry every number the issue's
 * hypotheses differ in: the device type (hypothesis 1 — delta units per device), the raw fractional
 * deltas and the notches they produced (a ±0.5 tick shows as delta 0.5 → 0 notches), the mask
 * count, and the held buttons. Dropped-over-letterbox scrolls (hypothesis 3) get their own line.
 */
class WheelTraceTest {

    @Test
    fun trace_line_carries_device_deltas_notches_and_masks() {
        val line = formatWheelTrace(
            WheelSample(PointerType.Mouse, deltaX = 0f, deltaY = -0.5f),
            notchesX = 0,
            notchesY = 0,
            masks = 0,
            held = 1,
        )
        assertTrue("Mouse" in line, line)
        assertTrue("-0.5" in line, line)
        // The labelled segment, not a bare digit: "0" already occurs in the delta text, and
        // notches is THE column hypotheses 1/2 of #265 turn on.
        assertTrue("notches=0,0" in line, line)
        assertTrue("masks=0" in line, line)
        assertTrue("held=1" in line, line)
    }

    @Test
    fun trace_line_shows_whole_notches_and_their_masks() {
        val line = formatWheelTrace(
            WheelSample(PointerType.Touch, deltaX = 1.25f, deltaY = 3f),
            notchesX = 1,
            notchesY = 3,
            masks = 8,
            held = 0,
        )
        assertTrue("Touch" in line, line)
        assertTrue("1.25" in line, line)
        assertTrue("notches=1,3" in line, line)
        assertTrue("masks=8" in line, line)
    }

    @Test
    fun negative_notches_keep_their_sign() {
        val line = formatWheelTrace(
            WheelSample(PointerType.Mouse, deltaX = -1f, deltaY = 0f),
            notchesX = -1,
            notchesY = 0,
            masks = 2,
            held = 0,
        )
        assertTrue("notches=-1,0" in line, line)
    }

    @Test
    fun letterbox_drop_names_itself_and_the_device() {
        val line = formatWheelDrop(WheelSample(PointerType.Mouse, deltaX = 0f, deltaY = 2f))
        assertTrue("letterbox" in line, line)
        assertTrue("Mouse" in line, line)
        assertTrue("2" in line, line)
    }
}
