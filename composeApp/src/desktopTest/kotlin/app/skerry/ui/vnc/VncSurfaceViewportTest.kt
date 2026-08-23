package app.skerry.ui.vnc

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the surface reports about the space it was given, which is what the server is resized to. */
@OptIn(ExperimentalTestApi::class)
class VncSurfaceViewportTest {

    @Test
    fun `the surface reports the display scaling it is drawn at, not just its pixels`() {
        // The viewport goes out in physical pixels. A surface that reported the size without the
        // scaling would leave the server drawing a 96 dpi desktop into a HiDPI screen — sharp, and
        // every glyph a fraction of the size of the client's own UI.
        withVncSurface(density = 1.5f, remoteResizeSupported = true) { session, screen ->
            assertTrue(screen.canResizeRemote, "the server's announcement never reached the screen")

            screen.toggleRemoteResize()
            waitUntil(timeoutMillis = 10_000) { session.desktopSizes.isNotEmpty() }

            // 300x200 dp at 150% is 450x300 physical pixels, and the scale travels beside them.
            assertEquals(450 to 300, session.desktopSizes.first())
            assertEquals(1.5f, session.desktopScales.first())
        }
    }
}
