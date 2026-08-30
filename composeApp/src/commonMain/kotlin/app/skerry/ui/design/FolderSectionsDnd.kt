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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.host.FolderBounds
import app.skerry.ui.host.HostDrop
import app.skerry.ui.host.folderDropTarget
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.CancellationException

private val MOUSE_DRAG_DEAD_ZONE = 6.dp

private class DragTrackingState(val startPosition: Offset) {
    var dragging = false
    var accumulated = Offset.Zero

    fun onDelta(
        change: PointerInputChange,
        threshold: Float,
        onStart: (Offset) -> Unit,
        onMove: (PointerInputChange, Offset) -> Unit,
    ) {
        val delta = change.positionChange()
        if (dragging) {
            if (delta != Offset.Zero) {
                change.consume()
                onMove(change, delta)
            }
        } else {
            accumulated += delta
            if (accumulated.getDistance() >= threshold) {
                dragging = true
                change.consume()
                onStart(startPosition)
                onMove(change, accumulated)
            }
        }
    }
}

suspend fun PointerInputScope.detectDeadZoneDragGestures(
    onStart: (Offset) -> Unit,
    onMove: (PointerInputChange, Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val threshold =
        if (down.type == PointerType.Mouse) MOUSE_DRAG_DEAD_ZONE.toPx() else viewConfiguration.touchSlop
    val tracker = DragTrackingState(down.position)
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                if (tracker.dragging) onCancel()
                break
            }
            if (change.changedToUpIgnoreConsumed()) {
                if (tracker.dragging) onEnd()
                break
            }
            tracker.onDelta(change, threshold, onStart, onMove)
        }
    } catch (e: CancellationException) {
        if (tracker.dragging) onCancel()
        throw e
    }
}

/**
 * Drag state for item lists partitioned into folders (Snippets, Runbooks, Keychain).
 * Supports single-item and multi-selection batch dragging and folder drop targeting.
 */
@Stable
class ListDragState {
    var draggingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var anchorId by mutableStateOf<String?>(null)
        private set

    var activeDrop by mutableStateOf<HostDrop?>(null)
        private set

    var draggingFolderName by mutableStateOf<String?>(null)
        private set

    var activeFolderDropIndex by mutableStateOf<Int?>(null)
        private set

    val draggingId: String? get() = anchorId ?: draggingIds.firstOrNull()

    private var pointerY = 0f
    private val itemBounds = HashMap<String, Rect>()
    private val folderRange = HashMap<String, Rect>()
    private val folderHeader = HashMap<String, Rect>()

    val isDragging: Boolean get() = draggingIds.isNotEmpty() || draggingFolderName != null

    fun isItemDragging(id: String): Boolean = id in draggingIds
    fun isFolderDragging(name: String): Boolean = name == draggingFolderName

    fun setItemBounds(id: String, rect: Rect) { itemBounds[id] = rect }
    fun clearItemBounds(id: String) { itemBounds.remove(id) }
    fun setFolderRange(name: String, rect: Rect) { folderRange[name] = rect }
    fun setFolderHeader(name: String, rect: Rect) { folderHeader[name] = rect }
    fun clearFolderHeader(name: String) { folderHeader.remove(name) }

    fun startFolderDrag(name: String, localOffsetY: Float) {
        draggingFolderName = name
        pointerY = (folderHeader[name]?.top ?: 0f) + localOffsetY
    }

    fun <T> currentFolderDropIndex(folders: List<Folder<T>>): Int {
        val centers = folders
            .filter { it.name != draggingFolderName }
            .mapNotNull { folderHeader[it.name]?.let { b -> (b.top + b.bottom) / 2f } }
        return folderDropTarget(centers, pointerY)
    }

    fun <T> refreshFolderDrop(folders: List<Folder<T>>) {
        val next = currentFolderDropIndex(folders)
        if (next != activeFolderDropIndex) activeFolderDropIndex = next
    }

    fun startDrag(id: String, localOffsetY: Float, selectedIds: Set<String> = emptySet()) {
        anchorId = id
        draggingIds = if (id in selectedIds) selectedIds else setOf(id)
        pointerY = (itemBounds[id]?.top ?: 0f) + localOffsetY
    }

    fun dragBy(deltaY: Float) {
        pointerY += deltaY
    }

    fun <T> folderBounds(folders: List<Folder<T>>, keyOf: (T) -> String): List<FolderBounds> =
        folders.mapNotNull { folder ->
            val range = folderRange[folder.name] ?: return@mapNotNull null
            FolderBounds(
                group = if (folder.name == UNGROUPED_FOLDER) null else folder.name,
                top = range.top,
                bottom = range.bottom,
                otherHostCentersY = folder.items
                    .filter { keyOf(it) !in draggingIds }
                    .mapNotNull { itemBounds[keyOf(it)]?.let { b -> (b.top + b.bottom) / 2f } },
            )
        }

    fun <T> refreshDrop(folders: List<Folder<T>>, keyOf: (T) -> String) {
        val next = currentDrop(folders, keyOf)
        if (next != activeDrop) activeDrop = next
    }

    fun <T> currentDrop(folders: List<Folder<T>>, keyOf: (T) -> String): HostDrop? {
        val bounds = folderBounds(folders, keyOf)
        if (bounds.isEmpty()) return null
        val matchingFolder = bounds.firstOrNull { pointerY >= it.top - 16f && pointerY <= it.bottom + 16f }
            ?: if (pointerY < bounds.first().top) bounds.first() else bounds.last()
        val index = matchingFolder.otherHostCentersY.count { it < pointerY }
        return HostDrop(matchingFolder.group, index)
    }

    fun endDrag() {
        draggingIds = emptySet()
        anchorId = null
        activeDrop = null
        draggingFolderName = null
        activeFolderDropIndex = null
    }
}

fun Modifier.itemBoundsAnchor(state: ListDragState, id: String): Modifier =
    onGloballyPositioned { state.setItemBounds(id, it.boundsInWindow()) }

fun Modifier.folderRangeAnchor(state: ListDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderRange(name, it.boundsInWindow()) }

fun Modifier.folderHeaderAnchor(state: ListDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderHeader(name, it.boundsInWindow()) }

fun <T> Modifier.draggableFolderHeader(
    state: ListDragState,
    name: String,
    folders: () -> List<Folder<T>>,
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
        if (moved) {
            val dropIndex = state.activeFolderDropIndex ?: state.currentFolderDropIndex(folders())
            onDrop(dropIndex)
        }
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(
            onDragStart = onStart,
            onDrag = onMove,
            onDragEnd = onEnd,
            onDragCancel = onCancel,
        )
    } else {
        detectDeadZoneDragGestures(
            onStart = onStart,
            onMove = onMove,
            onEnd = onEnd,
            onCancel = onCancel,
        )
    }
}

fun <T> Modifier.draggableItemRow(
    state: ListDragState,
    id: String,
    folders: () -> List<Folder<T>>,
    keyOf: (T) -> String,
    selectedIds: () -> Set<String> = { emptySet() },
    longPress: Boolean = false,
    onDrop: (drop: HostDrop, movingIds: Set<String>) -> Unit,
): Modifier = pointerInput(id, longPress) {
    var moved = false
    val onStart = { offset: Offset ->
        moved = false
        state.startDrag(id, offset.y, selectedIds())
        state.refreshDrop(folders(), keyOf)
    }
    val onMove = { change: PointerInputChange, amount: Offset ->
        change.consume()
        moved = true
        state.dragBy(amount.y)
        state.refreshDrop(folders(), keyOf)
    }
    val onEnd = {
        if (moved) {
            val drop = state.activeDrop ?: state.currentDrop(folders(), keyOf)
            val moving = state.draggingIds
            if (drop != null && moving.isNotEmpty()) {
                onDrop(drop, moving)
            }
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

@Composable
fun ListDropLine(
    modifier: Modifier = Modifier,
    horizontal: Dp = 18.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Skerry.colors.cyanBright)
        )
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Skerry.colors.cyan)
        )
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Skerry.colors.cyanBright)
        )
    }
}

@Composable
fun DragBatchBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Skerry.colors.cyanBright)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text = "+$count",
            color = Color.Black,
            size = 10.5.sp,
            weight = FontWeight.Bold,
        )
    }
}
