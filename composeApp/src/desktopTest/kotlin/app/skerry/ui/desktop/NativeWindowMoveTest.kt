package app.skerry.ui.desktop

import java.awt.Point
import java.awt.geom.AffineTransform
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeWindowMoveTest {

    @Test
    fun `unscaled session puts the pointer on the wire as is`() {
        val request = NativeWindowMove.moveRequest(Point(1319, 102), AffineTransform(), button = 1)
        assertEquals(1319L, request.rootX)
        assertEquals(102L, request.rootY)
    }

    @Test
    fun `scaled session puts the pointer on the wire in device pixels`() {
        // Measured on GNOME 50 Wayland with Xft.dpi=192: AWT reports the pointer at 1319,102 while
        // XQueryPointer puts it at ~2632,204 — the same place, in the X11 pixels the protocol wants.
        // (The X reading lags the AWT one by a frame, hence "~".)
        val request = NativeWindowMove.moveRequest(
            Point(1319, 102),
            AffineTransform.getScaleInstance(2.0, 2.0),
            button = 1,
        )
        assertEquals(2638L, request.rootX)
        assertEquals(204L, request.rootY)
    }

    @Test
    fun `fractional scale rounds to the nearest device pixel`() {
        val request = NativeWindowMove.moveRequest(
            Point(1319, 102),
            AffineTransform.getScaleInstance(1.25, 1.25),
            button = 1,
        )
        assertEquals(1649L, request.rootX)
        assertEquals(128L, request.rootY)
    }

    @Test
    fun `axes scale independently`() {
        val request = NativeWindowMove.moveRequest(
            Point(1319, 102),
            AffineTransform.getScaleInstance(2.0, 1.0),
            button = 1,
        )
        assertEquals(2638L, request.rootX)
        assertEquals(102L, request.rootY)
    }

    @Test
    fun `a monitor left of the primary keeps its negative coordinates`() {
        val request = NativeWindowMove.moveRequest(
            Point(-640, -200),
            AffineTransform.getScaleInstance(2.0, 2.0),
            button = 1,
        )
        assertEquals(-1280L, request.rootX)
        assertEquals(-400L, request.rootY)
    }

    @Test
    fun `a negative coordinate under a fractional scale rounds towards zero at the half`() {
        // Math.round is half-up, so -961.5 lands on -961, not -962. A one-pixel drift is harmless
        // here (Mutter allows 64), but pinning it keeps a rounding swap from going unnoticed.
        val request = NativeWindowMove.moveRequest(
            Point(-641, -200),
            AffineTransform.getScaleInstance(1.5, 1.5),
            button = 1,
        )
        assertEquals(-961L, request.rootX)
        assertEquals(-300L, request.rootY)
    }

    @Test
    fun `an unknown scale leaves the pointer alone instead of collapsing it`() {
        // No transform (window not on a screen yet) or a degenerate one would otherwise send the
        // drag to 0,0 — the WM would either ignore it or jump the window to the corner.
        for (transform in listOf(null, AffineTransform.getScaleInstance(0.0, 0.0))) {
            val request = NativeWindowMove.moveRequest(Point(1319, 102), transform, button = 1)
            assertEquals(1319L, request.rootX, "transform=$transform")
            assertEquals(102L, request.rootY, "transform=$transform")
        }
    }

    @Test
    fun `one degenerate axis does not disarm the other`() {
        val request = NativeWindowMove.moveRequest(
            Point(1319, 102),
            AffineTransform.getScaleInstance(0.0, 2.0),
            button = 1,
        )
        assertEquals(1319L, request.rootX)
        assertEquals(204L, request.rootY)
    }

    @Test
    fun `the request asks for a move with the held button`() {
        val request = NativeWindowMove.moveRequest(Point(10, 20), AffineTransform(), button = 3)
        assertEquals(8L, request.direction) // _NET_WM_MOVERESIZE_MOVE
        assertEquals(3L, request.button)
        assertEquals(1L, request.source) // source indication: normal application
        assertEquals(listOf(10L, 20L, 8L, 3L, 1L), request.words())
    }
}
