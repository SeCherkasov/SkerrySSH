package app.skerry.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.skerry.ui.host.detectDeadZoneDragGestures

/** A pane's place on the grid and where it sits on screen (window coordinates). */
data class PaneBounds(val paneId: String, val row: Int, val column: Int, val rect: Rect)

/** Which edge of a pane a drop is aimed at — what the drop indicator is drawn on. */
enum class PaneEdge { Top, Bottom, Left, Right }

/** A resolved drop: where the dragged pane would land, and the edge that says so on screen. */
data class PaneDrop(val slot: PaneSlot, val overPaneId: String, val edge: PaneEdge)

/**
 * Share of a pane's height at its top and bottom that means "put it in a row of its own". The rest
 * of the pane splits down the middle into "before me" and "after me" in the row — the same reading
 * as a tiling window manager, where the edge you aim at is the side the pane arrives on.
 */
private const val NEW_ROW_ZONE = 0.25f

/**
 * Where a pane dropped at [pointer] would land, given where the panes currently are ([bounds], in
 * window coordinates). `null` when the pointer is outside every pane — the grid is fully covered by
 * its panes, so that only happens over a divider or outside the work area, and neither is a drop.
 *
 * Pure so the geometry can be tested without a running window.
 */
fun paneDropZone(bounds: List<PaneBounds>, pointer: Offset): PaneDrop? {
    val over = bounds.firstOrNull { it.rect.contains(pointer) } ?: return null
    val topZone = over.rect.top + over.rect.height * NEW_ROW_ZONE
    val bottomZone = over.rect.bottom - over.rect.height * NEW_ROW_ZONE
    return when {
        pointer.y < topZone -> PaneDrop(PaneSlot.NewRow(over.row), over.paneId, PaneEdge.Top)
        pointer.y > bottomZone -> PaneDrop(PaneSlot.NewRow(over.row + 1), over.paneId, PaneEdge.Bottom)
        pointer.x < over.rect.center.x -> PaneDrop(PaneSlot.InRow(over.row, over.column), over.paneId, PaneEdge.Left)
        else -> PaneDrop(PaneSlot.InRow(over.row, over.column + 1), over.paneId, PaneEdge.Right)
    }
}

/**
 * State for dragging a pane by its header to another place on the grid. Mirrors
 * [TabDragState]'s shape: geometry is collected on layout ([paneBoundsAnchor]), the gesture tracks
 * the pointer in window coordinates, and the target is resolved by [paneDropZone] — the caller
 * applies it via [SessionsController.movePane].
 */
@Stable
class PaneDragState {
    /** Id of the pane being dragged, or `null`. */
    var draggingPaneId by mutableStateOf<String?>(null)
        private set

    /** Where the drop would land right now — the indicator the grid draws. `null` while idle. */
    var drop by mutableStateOf<PaneDrop?>(null)
        private set

    private var pointer = Offset.Zero

    // Pane geometry in window coordinates, written on layout and read from gestures. A plain
    // HashMap (not snapshot state) since composition never reads it; all access is on the main thread.
    private val bounds = HashMap<String, PaneBounds>()

    val isDragging: Boolean get() = draggingPaneId != null

    fun setBounds(paneId: String, row: Int, column: Int, rect: Rect) {
        bounds[paneId] = PaneBounds(paneId, row, column, rect)
    }

    /**
     * Forget a closed pane. Besides dropping its geometry this aborts a drag of that pane: a pane
     * can be closed from elsewhere (its own close button is not the only way) and the removal
     * cancels the gesture coroutine without onDragEnd, which would leave the indicator stuck.
     */
    fun paneClosed(paneId: String) {
        bounds.remove(paneId)
        if (draggingPaneId == paneId) end()
    }

    fun start(paneId: String, localOffset: Offset) {
        draggingPaneId = paneId
        pointer = (bounds[paneId]?.rect?.topLeft ?: Offset.Zero) + localOffset
        refresh()
    }

    fun dragBy(delta: Offset) {
        pointer += delta
        refresh()
    }

    /** Recompute the drop target; written only on change so the grid doesn't redraw per pointer move. */
    private fun refresh() {
        if (draggingPaneId == null) return
        val next = paneDropZone(bounds.values.toList(), pointer)
        if (next != drop) drop = next
    }

    fun end() {
        draggingPaneId = null
        drop = null
    }
}

/** Records a pane's place and bounds in window coordinates; the drag gesture reads them. */
fun Modifier.paneBoundsAnchor(state: PaneDragState, paneId: String, row: Int, column: Int): Modifier =
    onGloballyPositioned { state.setBounds(paneId, row, column, it.boundsInWindow()) }

/**
 * Makes a pane header draggable: the pane follows the pointer to another slot on the grid and
 * [onDrop] applies the landing. The drag only claims the pointer past a dead zone (see
 * [detectDeadZoneDragGestures]), so clicking the header — which opens its host picker — still works.
 */
fun Modifier.draggablePaneHeader(
    state: PaneDragState,
    paneId: String,
    onDrop: (PaneSlot) -> Unit,
): Modifier = pointerInput(paneId) {
    var moved = false
    detectDeadZoneDragGestures(
        onStart = { offset ->
            moved = false
            state.start(paneId, offset)
        },
        onMove = { change, amount ->
            change.consume()
            moved = true
            state.dragBy(amount)
        },
        onEnd = {
            // Without an actual move (a micro-gesture from a click) the grid stays untouched.
            if (moved) state.drop?.let { onDrop(it.slot) }
            state.end()
        },
        onCancel = { state.end() },
    )
}
