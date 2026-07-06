package app.skerry.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Skerry logo — a "signal S": a thick S-curve (light-to-deep teal gradient) with three
 * concentric wave arcs open to the right (decreasing opacity). Drawn on a Canvas
 * (viewBox 0 0 120 120); coordinates scale by `u = size/120`.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 120f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val sw = 6.5f * u

        // Three wave arcs: center ~(58,60), open to the right, ~100° sector, radii 26/40/54.
        data class Wave(val r: Float, val alpha: Float)
        listOf(Wave(26f, 0.85f), Wave(40f, 0.45f), Wave(54f, 0.18f)).forEach { w ->
            val r = w.r * u
            val c = p(58f, 60f)
            drawArc(
                color = D.teal.copy(alpha = w.alpha),
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }

        // S-curve (two cubic segments) on top — tealLight → tealDeep gradient.
        val s = Path().apply {
            moveTo(p(60f, 44f).x, p(60f, 44f).y)
            cubicTo(p(60f, 37f).x, p(60f, 37f).y, p(53f, 34f).x, p(53f, 34f).y, p(46f, 34f).x, p(46f, 34f).y)
            cubicTo(p(36f, 34f).x, p(36f, 34f).y, p(31f, 40f).x, p(31f, 40f).y, p(31f, 47f).x, p(31f, 47f).y)
            cubicTo(p(31f, 60f).x, p(31f, 60f).y, p(60f, 54f).x, p(60f, 54f).y, p(60f, 68f).x, p(60f, 68f).y)
            cubicTo(p(60f, 77f).x, p(60f, 77f).y, p(53f, 81f).x, p(53f, 81f).y, p(45f, 81f).x, p(45f, 81f).y)
            cubicTo(p(36f, 81f).x, p(36f, 81f).y, p(31f, 77f).x, p(31f, 77f).y, p(31f, 70f).x, p(31f, 70f).y)
        }
        drawPath(
            path = s,
            brush = Brush.linearGradient(
                colors = listOf(D.tealLight, D.tealDeep),
                start = p(31f, 32f),
                end = p(60f, 84f),
            ),
            style = Stroke(width = 10.5f * u, cap = StrokeCap.Round),
        )
    }
}
