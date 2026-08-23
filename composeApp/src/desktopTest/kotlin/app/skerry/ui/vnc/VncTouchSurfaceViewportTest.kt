package app.skerry.ui.vnc

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The touch surface owes the same viewport report as [VncSurface] — a phone is the HiDPI case. */
@OptIn(ExperimentalTestApi::class)
class VncTouchSurfaceViewportTest {

    @Test
    fun `the touch surface reports the display scaling it is drawn at, not just its pixels`() {
        // Every phone this runs on is scaled 2x or more, so a surface that dropped the scaling here
        // would hand the server a desktop drawn for a 96 dpi monitor on the one device where the
        // pixels are smallest.
        withVncSurface(density = 3f, remoteResizeSupported = true, surface = { VncTouchSurface(it) }) { session, screen ->
            assertTrue(screen.canResizeRemote, "the server's announcement never reached the screen")

            screen.toggleRemoteResize()
            waitUntil(timeoutMillis = 10_000) { session.desktopSizes.isNotEmpty() }

            // 300x200 dp at 300% is 900x600 physical pixels, and the scale travels beside them.
            assertEquals(900 to 600, session.desktopSizes.first())
            assertEquals(3f, session.desktopScales.first())
        }
    }
}
