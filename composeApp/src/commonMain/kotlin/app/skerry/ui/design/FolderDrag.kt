package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.CancellationException

/**
 * Drag-and-drop for the lists the user sorts by hand: the host sidebar, the snippet library, the
 * runbooks. Anchor modifiers ([itemBoundsAnchor]/[folderRangeAnchor]/[folderHeaderAnchor]) collect
 * each row's and folder's geometry in window coordinates; the gestures ([draggableItemRow]/
 * [draggableFolderHeader]) track the pointer and compute the drop on release through the pure
 * [itemDropTarget]/[folderDropTarget]. What actually moves where is the caller's business — the
 * modifiers only report the target.
 *
 * The three lists disagree about what a folder *is* (the sidebar's bucket is a name a host can
 * carry, the libraries' is a sentinel none can), so the state is fed [DragFolder]s the caller
 * builds rather than the folder types themselves.
 */

/**
 * One folder as the drag engine sees it: [name] is the key its geometry is filed under and the
 * Compose key of its section, [group] is what a row dropped into it should be filed as (`null` for
 * the no-folder bucket), and [itemIds] are its rows in the order they are drawn.
 */
data class DragFolder(val name: String, val group: String?, val itemIds: List<String>)

/**
 * The rows the list had on screen, read at drop time from the same provider the gesture resolves its
 * target through. A search or a chip narrows what is drawn while order lives in the whole list, so a
 * drop index counted here means nothing until the writer knows which rows it was counted over
 * ([app.skerry.ui.design.FilteredFolderList]).
 */
fun List<DragFolder>.visibleItemIds(): Set<String> = flatMapTo(mutableSetOf()) { it.itemIds }

/**
 * State of one draggable list. Row and folder dragging are mutually exclusive: a gesture starts on
 * a row or on a header, never on both.
 */
@Stable
class FolderDragState {

    /** Id of the row being dragged, or null. */
    var draggingItemId by mutableStateOf<String?>(null)
        private set

    /** [DragFolder.name] of the folder being dragged by its header, or null. */
    var draggingFolderName by mutableStateOf<String?>(null)
        private set

    /** Current row drop target, for highlighting the target folder and the insertion line. */
    var activeDrop by mutableStateOf<FolderDrop?>(null)
        private set

    /** Current folder insertion index (among the other folders), for the between-folders line. */
    var activeFolderDropIndex by mutableStateOf<Int?>(null)
        private set

    /** Pointer's vertical position in window coordinates, tracked over the gesture. */
    private var pointerY = 0f

    // Bounds in window coordinates, written on layout and read only from gestures. Plain HashMap,
    // not a Compose map: composition never reads them, so reactivity would only cost a snapshot
    // write on every layout pass (including scroll) for nothing. All access is on the main thread
    // (layout + gesture callbacks).
    private val itemBounds = HashMap<String, Rect>()
    private val folderRange = HashMap<String, Rect>()
    private val folderHeader = HashMap<String, Rect>()

    val isDragging: Boolean get() = draggingItemId != null || draggingFolderName != null

    fun setItemBounds(id: String, rect: Rect) { itemBounds[id] = rect }
    fun setFolderRange(name: String, rect: Rect) { folderRange[name] = rect }
    fun setFolderHeader(name: String, rect: Rect) { folderHeader[name] = rect }

    /**
     * Forget a row's or folder's geometry when it leaves the list. Without this a deleted row's
     * rectangle stays on file and keeps answering for a strip of the screen nothing draws any more,
     * so a drop over the row that took its place is counted on the wrong side of it.
     */
    fun clearItemBounds(id: String) { itemBounds.remove(id) }
    fun clearFolderBounds(name: String) { folderRange.remove(name); folderHeader.remove(name) }

    fun startItemDrag(id: String, localOffsetY: Float) {
        draggingItemId = id
        pointerY = (itemBounds[id]?.top ?: 0f) + localOffsetY
    }

    fun startFolderDrag(name: String, localOffsetY: Float) {
        draggingFolderName = name
        pointerY = (folderHeader[name]?.top ?: 0f) + localOffsetY
    }

    fun dragBy(deltaY: Float) {
        pointerY += deltaY
    }

    /**
     * [FolderBounds] for computing a row drop: centers exclude the dragged row (as [moveIntoFolder]
     * expects). A folder with no recorded geometry (not through layout yet, e.g. scrolled off) is
     * skipped; [itemDropTarget] then clamps the drop to the nearest folder it can see.
     */
    fun folderBounds(folders: List<DragFolder>): List<FolderBounds> = folders.mapNotNull { folder ->
        val range = folderRange[folder.name] ?: return@mapNotNull null
        FolderBounds(
            group = folder.group,
            top = range.top,
            bottom = range.bottom,
            // One pass: this runs for every folder on every raw pointer move.
            otherItemCentersY = folder.itemIds.mapNotNull { id ->
                if (id == draggingItemId) null else itemBounds[id]?.let { (it.top + it.bottom) / 2f }
            },
        )
    }

    fun currentDrop(folders: List<DragFolder>): FolderDrop? = itemDropTarget(folderBounds(folders), pointerY)

    /** Header centers of the other folders (excluding the dragged one), in list order. */
    fun currentFolderDropIndex(folders: List<DragFolder>): Int {
        val centers = folders
            .filter { it.name != draggingFolderName }
            .mapNotNull { folderHeader[it.name]?.let { b -> (b.top + b.bottom) / 2f } }
        return folderDropTarget(centers, pointerY)
    }

    fun refreshDrop(folders: List<DragFolder>) {
        // Only on target change, otherwise every pointer move would redraw all folders (highlighting).
        val next = currentDrop(folders)
        if (next != activeDrop) activeDrop = next
    }

    fun refreshFolderDrop(folders: List<DragFolder>) {
        val next = currentFolderDropIndex(folders)
        if (next != activeFolderDropIndex) activeFolderDropIndex = next
    }

    fun endDrag() {
        draggingItemId = null
        draggingFolderName = null
        activeDrop = null
        activeFolderDropIndex = null
    }
}

/** Records a row's window bounds, read by drag targets on release. */
fun Modifier.itemBoundsAnchor(state: FolderDragState, id: String): Modifier =
    onGloballyPositioned { state.setItemBounds(id, it.boundsInWindow()) }

/** Records a folder block's window bounds, used to determine which folder the pointer is over. */
fun Modifier.folderRangeAnchor(state: FolderDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderRange(name, it.boundsInWindow()) }

/** Records a folder header's window bounds, providing centers for folder reordering. */
fun Modifier.folderHeaderAnchor(state: FolderDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderHeader(name, it.boundsInWindow()) }

/**
 * Minimum pointer travel before a mouse press turns into a row/folder drag. Compose's built-in
 * drag slop for a MOUSE pointer is ~0.125dp — sub-pixel — so the 1–2px of jitter inside a normal
 * click would start a "drag" that consumes the move and cancels the row's tap/double-tap, making
 * clicks connect only intermittently (how often depended on how still the hand/mouse was).
 */
private val MOUSE_DRAG_DEAD_ZONE = 6.dp

/**
 * Like detectDragGestures, but the drag claims the pointer only after [MOUSE_DRAG_DEAD_ZONE]
 * (mouse) or the standard touch slop. Nothing is consumed before that, so click jitter reaches the
 * row's click handlers untouched; a genuine drag replays the accumulated offset on start, so the
 * dragged row doesn't jump. [onEnd]/[onCancel] fire only if the drag actually started.
 */
suspend fun PointerInputScope.detectDeadZoneDragGestures(
    onStart: (Offset) -> Unit,
    onMove: (PointerInputChange, Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val threshold =
        if (down.type == PointerType.Mouse) MOUSE_DRAG_DEAD_ZONE.toPx() else viewConfiguration.touchSlop
    // Nothing is consumed while the dead zone is being crossed, so a press that never leaves it is a
    // plain click and reaches the row's own handlers untouched.
    val crossed = awaitDeadZone(down, threshold) ?: return@awaitEachGesture
    onStart(down.position)
    // The travel so far is replayed, or the dragged row would jump by the dead zone's width.
    onMove(crossed.change, crossed.travel)
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null || (change.isConsumed && !change.changedToUpIgnoreConsumed())) {
                // Pointer stream broken, or a deeper handler claimed a non-up change.
                onCancel()
                return@awaitEachGesture
            }
            if (change.changedToUpIgnoreConsumed()) {
                onEnd()
                return@awaitEachGesture
            }
            val delta = change.positionChange()
            // Only genuine movement is consumed (and surfaced), like foundation's drag(): a wheel
            // Scroll change mid-drag has a zero positionChange, and consuming it would block the
            // list's verticalScroll while a drag is active.
            if (delta != Offset.Zero) {
                change.consume()
                onMove(change, delta)
            }
        }
    } catch (e: CancellationException) {
        // The row can leave composition mid-drag (record deleted/filtered out by a sync apply);
        // without this the drop highlight would stay stuck until an unrelated drag resets it.
        onCancel()
        throw e
    }
}

/** The change that took the pointer past the dead zone, and how far it had travelled by then. */
private class DeadZoneCrossing(val change: PointerInputChange, val travel: Offset)

/**
 * Waits for the pointer to travel [threshold] from [down], consuming nothing until it does. Returns
 * null if the gesture ended, was claimed by a deeper handler, or lost its pointer first — in every
 * one of those cases no drag ever started, so there is nothing to cancel.
 */
private suspend fun AwaitPointerEventScope.awaitDeadZone(
    down: PointerInputChange,
    threshold: Float,
): DeadZoneCrossing? {
    var travel = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (change.changedToUpIgnoreConsumed() || change.isConsumed) return null
        travel += change.positionChange()
        if (travel.getDistance() >= threshold) {
            change.consume()
            return DeadZoneCrossing(change, travel)
        }
    }
}

/**
 * Makes a row draggable. [folders] is read lazily (a fresh list at gesture time), [onDrop] receives
 * the target folder and index. [longPress] selects the gesture start: on desktop, a drag past a
 * small dead zone (mouse, see [detectDeadZoneDragGestures]); on touch, after a long press,
 * otherwise the drag would hijack the list's vertical scroll.
 */
fun Modifier.draggableItemRow(
    state: FolderDragState,
    id: String,
    folders: () -> List<DragFolder>,
    longPress: Boolean = false,
    onDrop: (FolderDrop) -> Unit,
): Modifier = pointerInput(id, longPress) {
    var moved = false
    val onStart = { offset: Offset ->
        moved = false
        state.startItemDrag(id, offset.y)
        state.refreshDrop(folders())
    }
    val onMove = { change: PointerInputChange, amount: Offset ->
        change.consume()
        moved = true
        state.dragBy(amount.y)
        state.refreshDrop(folders())
    }
    val onEnd = {
        // Without an actual move (a micro-gesture from a tap) the list and disk stay untouched.
        if (moved) {
            // The target the highlight was drawn from, not one recomputed against a layout that
            // may already have moved under the pointer: the drop has to land where the user saw it.
            val drop = state.activeDrop ?: state.currentDrop(folders())
            drop?.let(onDrop)
        }
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    } else {
        detectDeadZoneDragGestures(onStart, onMove, onEnd, onCancel)
    }
}

/**
 * Makes a folder header draggable. [onDrop] receives the target index among folders. [longPress]:
 * see [draggableItemRow].
 */
fun Modifier.draggableFolderHeader(
    state: FolderDragState,
    name: String,
    folders: () -> List<DragFolder>,
    longPress: Boolean = false,
    onDrop: (Int) -> Unit,
): Modifier = pointerInput(name, longPress) {
    var moved = false
    val onStart = { offset: Offset ->
        moved = false
        state.startFolderDrag(name, offset.y)
        state.refreshFolderDrop(folders())
    }
    val onMove = { change: PointerInputChange, amount: Offset ->
        change.consume()
        moved = true
        state.dragBy(amount.y)
        state.refreshFolderDrop(folders())
    }
    val onEnd = {
        if (moved) onDrop(state.activeFolderDropIndex ?: state.currentFolderDropIndex(folders()))
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    } else {
        detectDeadZoneDragGestures(onStart, onMove, onEnd, onCancel)
    }
}

/**
 * Where the between-folders insertion line goes while a folder is dragged: above the folder named by
 * [before], or after the last one when [atEnd]. Neither while nothing is dragged.
 *
 * The three lists that draw folders (the sidebar, the mobile catalog and [FolderSections]) each
 * render their own folder type, but the arithmetic is the same in all three and getting it wrong
 * puts the line one folder off — so it is computed here and only drawn there.
 */
@Immutable
data class FolderLinePlacement(val before: String?, val atEnd: Boolean)

private val NoFolderLine = FolderLinePlacement(before = null, atEnd = false)

/** [FolderLinePlacement] for a list drawing [folderNames] in that order. */
fun FolderDragState.folderLinePlacement(folderNames: List<String>): FolderLinePlacement {
    val dragged = draggingFolderName ?: return NoFolderLine
    val index = activeFolderDropIndex ?: return NoFolderLine
    val others = folderNames.filterNot { it == dragged }
    return if (index < others.size) FolderLinePlacement(others[index], atEnd = false) else NoFolderLine.copy(atEnd = true)
}

/**
 * The line drawn where a dragged row or folder would land. Wide dots at both ends: the line sits
 * between two rows of a list that is mostly horizontal rules already, and a plain 2dp rule read as
 * one of them.
 */
@Composable
fun FolderDropLine(horizontal: Dp = 18.dp) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Skerry.colors.cyanBright))
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(Skerry.colors.cyan))
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Skerry.colors.cyanBright))
    }
}
