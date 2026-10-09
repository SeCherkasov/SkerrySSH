package app.skerry.ui.terminal

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize

/**
 * Recorded glyph commands for the visible rows. Selection/search washes stay below these layers,
 * and cursor/hover decoration stays above them. Rows keep their original per-cell positioning.
 * Owned by one terminal and rendering generation; used only on the UI draw thread.
 */
internal class RowDrawingCache(private val graphics: GraphicsContext) {
    private class Drawing(val layer: GraphicsLayer) {
        var row: RowRenderCache.Entry? = null
        var size = IntSize.Zero
    }

    private val drawings = HashMap<Int, Drawing>()

    /** Drop history as it leaves the viewport, bounding native storage to one visible window. */
    fun retain(window: IntRange) {
        val iterator = drawings.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in window) {
                graphics.releaseGraphicsLayer(entry.value.layer)
                iterator.remove()
            }
        }
    }

    fun draw(
        index: Int,
        row: RowRenderCache.Entry,
        scope: DrawScope,
        size: IntSize,
        record: DrawScope.() -> Unit,
    ) {
        val drawing = drawings.getOrPut(index) { Drawing(graphics.createGraphicsLayer()) }
        if (drawing.row !== row || drawing.size != size) {
            drawing.layer.record(scope, scope.layoutDirection, size, record)
            rowGlyphRecordings++
            drawing.row = row
            drawing.size = size
        }
        with(scope) { drawLayer(drawing.layer) }
    }

    fun close() {
        for (drawing in drawings.values) graphics.releaseGraphicsLayer(drawing.layer)
        drawings.clear()
    }
}

// Like the layout/segmentation counters, records actual work in the sequential draw/test thread.
internal var rowGlyphRecordings = 0
