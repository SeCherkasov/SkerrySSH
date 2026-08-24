package app.skerry.ui.render

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.fail
import org.jetbrains.skia.Image

/**
 * How long a render test waits for the screen to show what it is waiting for.
 *
 * These scenes are driven by hand, one 60 Hz tick at a time, but what they wait for is not on the
 * frame clock: a recomposition, a font loaded on [kotlinx.coroutines.Dispatchers.Default], the first
 * bytes of a fake connection. Spending a fixed number of frames on that is a latency assertion
 * nobody meant to make — on an idle machine five frames are plenty, on a loaded CI runner they are
 * not, and the assert reads the frame before the one that would have passed (issue #330).
 *
 * The budget is therefore not a latency claim: it only has to be large enough that anything slower
 * is a screen that never repaints rather than a busy machine. Waits that assert a *period* — a
 * cursor blinking off within its own half-period — pass their own small [timeoutMillis], and that
 * budget is the assertion.
 */
private const val RENDER_WAIT_BUDGET_MS = 10_000L

/** One 60 Hz tick, in nanoseconds — what each frame advances the scene's clock by. */
private const val FRAME_NANOS = 16_666_667L

/** How long a wait sleeps between attempts: one frame period, so a wait costs frames, not a spin. */
private const val POLL_MILLIS = 16L

/**
 * Drives an [ImageComposeScene] frame by frame for the offscreen render tests: publishes pending
 * snapshot writes, advances the frame clock by one tick and renders.
 *
 * The five render tests that wait for a repaint used to carry their own copy of this — ten in all.
 * Tests that render a scene only to lay it out or to shoot a screenshot still drive their own frames;
 * they wait for nothing, so they are not the issue #330 class. [pauseMillis] is the wall clock each frame gives back to work
 * that does not run on the frame clock — async fonts and the fake connection; a scene driven on
 * [kotlinx.coroutines.Dispatchers.Unconfined] does its work at the emit point and needs none.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class SceneFrames(
    private val scene: ImageComposeScene,
    private val pauseMillis: Long = 0,
) {
    private var timeNanos = 0L

    /** One frame. */
    fun next(): PixelMap {
        val pixels = draw().toComposeImageBitmap().toPixelMap()
        if (pauseMillis > 0) Thread.sleep(pauseMillis)
        return pixels
    }

    /** Drive one frame without reading it back — for a test that renders only to move the composition on. */
    fun advance() {
        draw()
        if (pauseMillis > 0) Thread.sleep(pauseMillis)
    }

    private fun draw(): Image {
        Snapshot.sendApplyNotifications()
        timeNanos += FRAME_NANOS
        return scene.render(timeNanos)
    }

    /**
     * [count] frames, the last one returned. For settling a scene that has nothing to wait *for* —
     * a fresh layout before the first emit — and for the negative assertions, where "still not on
     * screen after N frames" is the whole claim and no condition can be waited on.
     */
    fun settle(count: Int): PixelMap {
        var pixels = next()
        repeat(count - 1) { pixels = next() }
        return pixels
    }

    /**
     * Renders until [condition] holds, and fails naming [what] when [timeoutMillis] passes without
     * it — so a screen that never followed reads as a sentence about the screen, and never as a
     * colour assertion that a slow runner could have produced just as well.
     */
    fun awaitFrame(
        what: String,
        timeoutMillis: Long = RENDER_WAIT_BUDGET_MS,
        condition: (PixelMap) -> Boolean,
    ): PixelMap {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var pixels = next()
        while (!condition(pixels)) {
            if (System.nanoTime() > deadline) {
                fail("timed out after ${timeoutMillis}ms waiting for $what")
            }
            // [next] already paused a scene that asked for one; sleeping again here would halve the
            // frame rate the budget is counted in.
            if (pauseMillis == 0L) Thread.sleep(POLL_MILLIS)
            pixels = next()
        }
        return pixels
    }
}
