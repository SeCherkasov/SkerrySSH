package app.skerry.ui.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AWT floor handed to the window manager and to the resize strips: [MIN_WINDOW] in AWT units,
 * capped by the screen the app started on.
 */
class MinimumWindowDimensionTest {

    @Test
    fun large_screen_yields_the_layout_minimum() {
        assertEquals(Dimension(1100, 720), minimumWindowDimension(DpSize(3840.dp, 2160.dp)))
    }

    @Test
    fun small_screen_caps_the_floor_at_the_screen() {
        assertEquals(Dimension(1024, 600), minimumWindowDimension(DpSize(1024.dp, 600.dp)))
    }

    @Test
    fun a_drag_cannot_shrink_below_the_screen_capped_floor() {
        // The floor the strips clamp at comes from this very function, so a netbook-sized screen
        // resizes down to its own edge instead of the (larger) layout minimum.
        val floor = minimumWindowDimension(DpSize(1024.dp, 600.dp))
        val bounds = resizedWindowBounds(
            Rectangle(0, 0, 1024, 600), ResizeEdge.BottomRight, -900, -900,
            floor.width, floor.height,
        )
        assertEquals(Rectangle(0, 0, 1024, 600), bounds)
    }
}

class WindowResizeTest {

    private val start = Rectangle(100, 200, 800, 600)

    private fun resize(edge: ResizeEdge, dx: Int, dy: Int) =
        resizedWindowBounds(start, edge, dx, dy, minWidth = 400, minHeight = 300)

    @Test
    fun rightEdgeGrowsWidthKeepingOrigin() {
        assertEquals(Rectangle(100, 200, 850, 600), resize(ResizeEdge.Right, 50, 999))
    }

    @Test
    fun bottomEdgeGrowsHeightKeepingOrigin() {
        assertEquals(Rectangle(100, 200, 800, 640), resize(ResizeEdge.Bottom, -999, 40))
    }

    @Test
    fun leftEdgeMovesOriginKeepingRightEdgeFixed() {
        // Dragging the left edge 60px right shrinks width and shifts x so x+width stays 900.
        assertEquals(Rectangle(160, 200, 740, 600), resize(ResizeEdge.Left, 60, 0))
    }

    @Test
    fun topEdgeMovesOriginKeepingBottomEdgeFixed() {
        assertEquals(Rectangle(100, 170, 800, 630), resize(ResizeEdge.Top, 0, -30))
    }

    @Test
    fun bottomRightCorner() {
        assertEquals(Rectangle(100, 200, 830, 620), resize(ResizeEdge.BottomRight, 30, 20))
    }

    @Test
    fun topLeftCorner() {
        assertEquals(Rectangle(120, 210, 780, 590), resize(ResizeEdge.TopLeft, 20, 10))
    }

    @Test
    fun widthClampsToMinimumKeepingRightEdgeFixed() {
        // 800 - 700 = 100 < min 400: width clamps to 400, x compensates so x+width stays 900.
        assertEquals(Rectangle(500, 200, 400, 600), resize(ResizeEdge.Left, 700, 0))
    }

    @Test
    fun heightClampsToMinimumKeepingBottomEdgeFixed() {
        assertEquals(Rectangle(100, 500, 800, 300), resize(ResizeEdge.Top, 0, 700))
    }

    @Test
    fun rightAndBottomClampWithoutMovingOrigin() {
        assertEquals(Rectangle(100, 200, 400, 300), resize(ResizeEdge.BottomRight, -700, -700))
    }
}
