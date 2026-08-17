package app.skerry.ui.vnc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import app.skerry.ui.remote.FakeRemoteDesktop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pointer loop of [VncSurface], through real mouse input: what reaches the session when the
 * mouse leaves the picture mid-drag (F-37), when a click starts on the letterbox, and what a wheel
 * step sends while a button is held (F-14/F-38). The surface and its geometry come from
 * [withVncSurface].
 */
@OptIn(ExperimentalTestApi::class)
class VncSurfaceInputTest {

    @Test
    fun `a drag that ends on the letterbox still releases the button, clamped onto the image`() =
        withVncSurface { session, _ ->
            onRoot().performMouseInput {
                moveTo(Offset(150f, 100f)) // fb (50,50)
                press()
                moveTo(Offset(10f, 100f)) // off the image, button still down
                release()
            }
            session.awaitPointer { it.third == 0 && it.first == 0 }
            val release = session.pointerSnapshot().last { it.third == 0 }
            assertEquals(0, release.first, "the release lands on the image's nearest edge, not nowhere")
        }

    @Test
    fun `a fresh click on the letterbox is dead space, not a click on the desktop's edge`() =
        withVncSurface { session, _ ->
            onRoot().performMouseInput {
                moveTo(Offset(10f, 100f))
                press()
                release()
            }
            waitForIdle()
            Thread.sleep(100) // give the actor time to (wrongly) deliver anything queued
            val sent = session.pointerSnapshot()
            assertTrue(
                sent.none { it.third != 0 },
                "clicking the black bar must not press anything on the remote desktop: $sent",
            )
        }

    @Test
    fun `a wheel step keeps the button a drag is holding`() =
        withVncSurface { session, _ ->
            onRoot().performMouseInput {
                moveTo(Offset(150f, 100f))
                press()
                scroll(-1f, ScrollWheel.Vertical)
            }
            session.awaitPointer { it.third and WHEEL_BITS != 0 }
            onRoot().performMouseInput { release() }
            val wheels = session.pointerSnapshot().filter { it.third and WHEEL_BITS != 0 }
            assertTrue(wheels.isNotEmpty(), "the wheel notch reached the session")
            assertTrue(
                wheels.all { it.third and VncButton.LEFT != 0 },
                "the wheel mask dropped the held button — that releases the drag on RFB: $wheels",
            )
        }

    private companion object {
        const val WHEEL_BITS =
            VncButton.WHEEL_UP or VncButton.WHEEL_DOWN or VncButton.WHEEL_LEFT or VncButton.WHEEL_RIGHT
    }
}

/**
 * A stable copy of the recorded pointers. The actor appends from another thread (the pacing delay
 * resumes on a scheduler thread), so a plain iteration can hit a ConcurrentModificationException —
 * retried here instead of surfacing as a flake.
 */
private fun FakeRemoteDesktop.pointerSnapshot(): List<Triple<Int, Int, Int>> {
    while (true) {
        try {
            return pointers.toList()
        } catch (_: ConcurrentModificationException) {
        }
    }
}

/** Polls until [condition] matches a recorded pointer — the actor drains on a real dispatcher. */
private fun FakeRemoteDesktop.awaitPointer(condition: (Triple<Int, Int, Int>) -> Boolean) {
    val deadline = System.currentTimeMillis() + 2_000
    while (System.currentTimeMillis() < deadline) {
        if (pointerSnapshot().any(condition)) return
        Thread.sleep(10)
    }
    throw AssertionError("no pointer event matched within 2s: ${pointerSnapshot()}")
}
