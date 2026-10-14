package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import app.skerry.ui.remote.RemoteDesktopScreenState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RemoteDesktopBackgroundTest {
    @Test
    fun `desktop background colors letterboxing without modifying remote pixels`() = checkBackground { VncSurface(it) }

    @Test
    fun `touch background colors letterboxing without modifying remote pixels`() = checkBackground { VncTouchSurface(it) }

    private fun checkBackground(surface: @Composable (RemoteDesktopScreenState) -> Unit) {
        withVncSurface(backgroundRgb = 0x112233, surface = surface) { session, screen ->
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(Color(0xFF112233), pixels[10, 100])
            assertEquals(Color(0xFF112233), pixels[290, 100])
            assertEquals(Color.Green, pixels[100, 100], "the remote image keeps its own pixels")
            assertEquals(0xFF00FF00.toInt(), session.framebuffer.pixels[50 * 100 + 50], "the source framebuffer is untouched")
            assertEquals(Color(0xFF112233), screen.backgroundColor)
        }
    }
}
