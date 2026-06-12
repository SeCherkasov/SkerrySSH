package app.skerry.ui.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.SkerryColors

/**
 * Какую иконку рисовать. Набор намеренно узкий — только то, что использует desktop-каркас по
 * `docs/skerry-prototype-desktop.html`. Векторно на [Canvas], чтобы не тянуть material-icons и не
 * зависеть от наличия глифов в системном шрифте.
 */
enum class SkerryIconKind {
    Chevron, Close, Add, Search, Lock, LockOpen, Tune, MoreVert,
    Folder, Info, Power, Terminal, Key, Refresh, Ai,
}

/**
 * Моноцветная векторная иконка фиксированного размера. Геометрия задаётся в долях от стороны
 * холста, поэтому масштабируется любым [size]. [tint] по умолчанию приглушённый — под цвет
 * иконок панелей в прототипе.
 */
@Composable
fun SkerryIcon(
    kind: SkerryIconKind,
    modifier: Modifier = Modifier,
    tint: Color = SkerryColors.textDim,
    size: Dp = 18.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension * 0.085f
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        when (kind) {
            SkerryIconKind.Chevron -> chevron(tint, stroke)
            SkerryIconKind.Close -> close(tint, stroke)
            SkerryIconKind.Add -> add(tint, stroke)
            SkerryIconKind.Search -> search(tint, stroke, w)
            SkerryIconKind.Lock -> lock(tint, stroke, closed = true)
            SkerryIconKind.LockOpen -> lock(tint, stroke, closed = false)
            SkerryIconKind.Tune -> tune(tint, stroke, w)
            SkerryIconKind.MoreVert -> moreVert(tint, w)
            SkerryIconKind.Folder -> folder(tint, stroke)
            SkerryIconKind.Info -> info(tint, stroke, w)
            SkerryIconKind.Power -> power(tint, stroke)
            SkerryIconKind.Terminal -> terminal(tint, stroke)
            SkerryIconKind.Key -> key(tint, stroke, w)
            SkerryIconKind.Refresh -> refresh(tint, stroke)
            SkerryIconKind.Ai -> ai(tint)
        }
    }
}

private fun DrawScope.p(x: Float, y: Float) = Offset(size.width * x, size.height * y)

private fun DrawScope.chevron(c: Color, st: Stroke) {
    drawLine(c, p(0.28f, 0.4f), p(0.5f, 0.62f), st.width, st.cap)
    drawLine(c, p(0.5f, 0.62f), p(0.72f, 0.4f), st.width, st.cap)
}

private fun DrawScope.close(c: Color, st: Stroke) {
    drawLine(c, p(0.3f, 0.3f), p(0.7f, 0.7f), st.width, st.cap)
    drawLine(c, p(0.7f, 0.3f), p(0.3f, 0.7f), st.width, st.cap)
}

private fun DrawScope.add(c: Color, st: Stroke) {
    drawLine(c, p(0.5f, 0.26f), p(0.5f, 0.74f), st.width, st.cap)
    drawLine(c, p(0.26f, 0.5f), p(0.74f, 0.5f), st.width, st.cap)
}

private fun DrawScope.search(c: Color, st: Stroke, w: Float) {
    val r = size.minDimension * 0.24f
    val cx = size.width * 0.43f
    val cy = size.height * 0.43f
    drawCircle(c, r, Offset(cx, cy), style = st)
    drawLine(c, Offset(cx + r * 0.72f, cy + r * 0.72f), p(0.78f, 0.78f), w, st.cap)
}

private fun DrawScope.lock(c: Color, st: Stroke, closed: Boolean) {
    val left = size.width * 0.32f
    val top = size.height * 0.46f
    val bw = size.width * 0.36f
    val bh = size.height * 0.3f
    drawRoundRectStroke(c, left, top, bw, bh, st)
    val ax = size.width * (if (closed) 0.5f else 0.6f)
    drawShackle(c, ax, top, st, closed)
}

private fun DrawScope.drawRoundRectStroke(c: Color, x: Float, y: Float, ww: Float, hh: Float, st: Stroke) {
    drawLine(c, Offset(x, y), Offset(x + ww, y), st.width, st.cap)
    drawLine(c, Offset(x, y + hh), Offset(x + ww, y + hh), st.width, st.cap)
    drawLine(c, Offset(x, y), Offset(x, y + hh), st.width, st.cap)
    drawLine(c, Offset(x + ww, y), Offset(x + ww, y + hh), st.width, st.cap)
}

private fun DrawScope.drawShackle(c: Color, centerX: Float, bodyTop: Float, st: Stroke, closed: Boolean) {
    val r = size.width * 0.13f
    val top = bodyTop - r * 1.5f
    drawLine(c, Offset(centerX - r, bodyTop), Offset(centerX - r, top + r), st.width, st.cap)
    drawLine(c, Offset(centerX - r, top + r), Offset(centerX, top), st.width, st.cap)
    drawLine(c, Offset(centerX, top), Offset(centerX + r, top + r), st.width, st.cap)
    if (closed) drawLine(c, Offset(centerX + r, top + r), Offset(centerX + r, bodyTop), st.width, st.cap)
}

private fun DrawScope.tune(c: Color, st: Stroke, w: Float) {
    drawLine(c, p(0.22f, 0.36f), p(0.78f, 0.36f), st.width, st.cap)
    drawLine(c, p(0.22f, 0.64f), p(0.78f, 0.64f), st.width, st.cap)
    drawCircle(c, size.minDimension * 0.08f, p(0.62f, 0.36f), style = Stroke(w))
    drawCircle(c, size.minDimension * 0.08f, p(0.38f, 0.64f), style = Stroke(w))
}

private fun DrawScope.moreVert(c: Color, w: Float) {
    val r = w * 0.9f
    drawCircle(c, r, p(0.5f, 0.28f))
    drawCircle(c, r, p(0.5f, 0.5f))
    drawCircle(c, r, p(0.5f, 0.72f))
}

private fun DrawScope.folder(c: Color, st: Stroke) {
    val l = size.width * 0.24f
    val r = size.width * 0.76f
    val top = size.height * 0.36f
    val bottom = size.height * 0.68f
    drawLine(c, Offset(l, top), Offset(size.width * 0.44f, top), st.width, st.cap)
    drawLine(c, Offset(size.width * 0.44f, top), Offset(size.width * 0.5f, top + size.height * 0.06f), st.width, st.cap)
    drawLine(c, Offset(size.width * 0.5f, top + size.height * 0.06f), Offset(r, top + size.height * 0.06f), st.width, st.cap)
    drawLine(c, Offset(l, top), Offset(l, bottom), st.width, st.cap)
    drawLine(c, Offset(r, top + size.height * 0.06f), Offset(r, bottom), st.width, st.cap)
    drawLine(c, Offset(l, bottom), Offset(r, bottom), st.width, st.cap)
}

private fun DrawScope.info(c: Color, st: Stroke, w: Float) {
    drawCircle(c, size.minDimension * 0.3f, center, style = st)
    drawCircle(c, w * 0.7f, p(0.5f, 0.36f))
    drawLine(c, p(0.5f, 0.48f), p(0.5f, 0.66f), st.width, st.cap)
}

private fun DrawScope.power(c: Color, st: Stroke) {
    drawLine(c, p(0.5f, 0.24f), p(0.5f, 0.5f), st.width, st.cap)
    val r = size.minDimension * 0.27f
    drawArc(
        color = c,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r + size.height * 0.06f),
        size = Size(r * 2, r * 2),
        style = st,
    )
}

private fun DrawScope.terminal(c: Color, st: Stroke) {
    drawLine(c, p(0.3f, 0.34f), p(0.46f, 0.5f), st.width, st.cap)
    drawLine(c, p(0.46f, 0.5f), p(0.3f, 0.66f), st.width, st.cap)
    drawLine(c, p(0.52f, 0.66f), p(0.7f, 0.66f), st.width, st.cap)
}

private fun DrawScope.key(c: Color, st: Stroke, w: Float) {
    val r = size.minDimension * 0.16f
    drawCircle(c, r, p(0.36f, 0.4f), style = st)
    drawLine(c, Offset(size.width * 0.45f, size.height * 0.49f), p(0.74f, 0.7f), st.width, st.cap)
    drawLine(c, p(0.62f, 0.58f), p(0.7f, 0.5f), st.width, st.cap)
}

private fun DrawScope.refresh(c: Color, st: Stroke) {
    val r = size.minDimension * 0.28f
    drawArc(
        color = c,
        startAngle = 40f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2, r * 2),
        style = st,
    )
    drawLine(c, p(0.72f, 0.3f), p(0.78f, 0.42f), st.width, st.cap)
    drawLine(c, p(0.78f, 0.42f), p(0.86f, 0.32f), st.width, st.cap)
}

/** Искра (4-конечная звезда) + маленькая рядом — знак AI/lighthouse-момента. */
private fun DrawScope.ai(c: Color) {
    fourStar(c, cxF = 0.42f, cyF = 0.47f, outerF = 0.30f, innerF = 0.12f)
    fourStar(c, cxF = 0.75f, cyF = 0.25f, outerF = 0.13f, innerF = 0.05f)
}

private fun DrawScope.fourStar(c: Color, cxF: Float, cyF: Float, outerF: Float, innerF: Float) {
    val cx = size.width * cxF
    val cy = size.height * cyF
    val o = size.minDimension * outerF
    val i = size.minDimension * innerF * 0.707f // внутренние вершины на диагонали
    val path = Path().apply {
        moveTo(cx, cy - o)
        lineTo(cx + i, cy - i)
        lineTo(cx + o, cy)
        lineTo(cx + i, cy + i)
        lineTo(cx, cy + o)
        lineTo(cx - i, cy + i)
        lineTo(cx - o, cy)
        lineTo(cx - i, cy - i)
        close()
    }
    drawPath(path, c)
}
