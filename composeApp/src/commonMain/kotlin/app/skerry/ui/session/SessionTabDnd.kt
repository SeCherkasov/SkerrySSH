package app.skerry.ui.session

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Insertion index for the dragged tab among the other tabs (X centers in list order) at pointer
 * position [pointerX]: the count of tabs whose center is left of the pointer. The result is in
 * coordinates excluding the dragged tab, which is the `toIndex` expected by
 * [app.skerry.ui.session.SessionsController.moveTab] (removeAt→add in the same list).
 */
fun tabInsertIndex(otherCentersX: List<Float>, pointerX: Float): Int = otherCentersX.count { it < pointerX }

/**
 * State for titlebar tab drag-reorder. [tabBoundsAnchor] collects chip geometry in window
 * coordinates; the [draggableTab] gesture tracks the pointer and computes the target index via
 * [tabInsertIndex] on release. The caller decides what to reorder via onDrop (usually
 * [app.skerry.ui.session.SessionsController.moveTab]).
 */
@Stable
class TabDragState {
    /** Id of the dragged tab (or null). */
    var draggingTabId by mutableStateOf<String?>(null)
        private set

    /**
     * Insert-line position in coordinates of the full list (0..size): before the tab at this index
     * (size = line at the very end). `null` means no drag in progress. Accounts for the dragged chip
     * still being rendered in the row (see [refresh]).
     */
    var insertLineIndex by mutableStateOf<Int?>(null)
        private set

    // Horizontal pointer position in window coordinates, tracked over the course of the gesture.
    private var pointerX = 0f

    // Chip bounds in window coordinates, written on layout, read from gestures. A plain HashMap (not
    // snapshot state) since composition never reads it. All access happens on the main thread.
    private val bounds = HashMap<String, Rect>()

    val isDragging: Boolean get() = draggingTabId != null

    fun setBounds(id: String, rect: Rect) { bounds[id] = rect }

    /** Forget geometry for a closed tab, so bounds don't accumulate for stale ids. */
    fun clearBounds(id: String) { bounds.remove(id) }

    fun start(id: String, localOffsetX: Float) {
        draggingTabId = id
        pointerX = (bounds[id]?.left ?: 0f) + localOffsetX
    }

    fun dragBy(deltaX: Float) { pointerX += deltaX }

    /** Target `toIndex` (in the list excluding the dragged tab) for the current pointer position. */
    fun currentDropIndex(ids: List<String>): Int =
        tabInsertIndex(otherCenters(ids), pointerX)

    private fun otherCenters(ids: List<String>): List<Float> = ids
        .filter { it != draggingTabId }
        .mapNotNull { bounds[it]?.let { b -> (b.left + b.right) / 2f } }

    /**
     * Recompute the insert line. [insertLineIndex] is in full-row coordinates: the drop index `d`
     * (excluding the dragged tab) is mapped against the dragged tab's position `p` (still in the
     * row): the line goes before `d`, or before `d+1` if the target is right of the original
     * position. Written only on change to avoid redrawing the titlebar on every pointer move.
     */
    fun refresh(ids: List<String>) {
        val dragId = draggingTabId ?: return
        val p = ids.indexOf(dragId)
        val d = currentDropIndex(ids)
        val line = if (d <= p) d else d + 1
        if (line != insertLineIndex) insertLineIndex = line
    }

    fun end() {
        draggingTabId = null
        insertLineIndex = null
    }
}

/** Records a tab chip's bounds in window coordinates; the drag gesture reads them on release. */
fun Modifier.tabBoundsAnchor(state: TabDragState, tabId: String): Modifier =
    onGloballyPositioned { state.setBounds(tabId, it.boundsInWindow()) }

/**
 * Makes a tab chip draggable. [ids] is read lazily (fresh order at gesture time); [onDrop] gets
 * (fromIndex, toIndex) and reorders the tab via the controller. Taps pass through to the chip's
 * [clickable] since detectDragGestures only fires on an actual drag.
 */
fun Modifier.draggableTab(
    state: TabDragState,
    tabId: String,
    ids: () -> List<String>,
    onDrop: (fromIndex: Int, toIndex: Int) -> Unit,
): Modifier = pointerInput(tabId) {
    var moved = false
    detectDragGestures(
        onDragStart = { offset ->
            moved = false
            state.start(tabId, offset.x)
            state.refresh(ids())
        },
        onDrag = { change, amount ->
            change.consume()
            moved = true
            state.dragBy(amount.x)
            state.refresh(ids())
        },
        onDragEnd = {
            if (moved) {
                val list = ids()
                val from = list.indexOf(tabId)
                val to = state.currentDropIndex(list)
                if (from >= 0) onDrop(from, to)
            }
            state.end()
        },
        onDragCancel = { state.end() },
    )
}
