package app.skerry.ui.host

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.skerry.ui.host.FolderBounds
import app.skerry.ui.host.HostDrop
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.folderDropTarget
import app.skerry.ui.host.hostDropTarget

/**
 * Manual sidebar sort state (drag-and-drop). Anchor modifiers ([hostBoundsAnchor]/[folderRangeAnchor]/
 * [folderHeaderAnchor]) collect each row/folder's geometry in window coordinates; gestures
 * ([draggableHostRow]/[draggableFolderHeader]) track the pointer and compute the drop target on
 * release via pure [hostDropTarget]/[folderDropTarget]. What actually moves where is decided by
 * [HostManagerController] through the callback passed to the modifier.
 */
@Stable
class HostDragState {
    /** Id of the host being dragged (or null). Host and folder dragging are mutually exclusive. */
    var draggingHostId by mutableStateOf<String?>(null)
        private set

    /** Name of the folder (HostFolder.name) currently being dragged by its header (or null). */
    var draggingFolderName by mutableStateOf<String?>(null)
        private set

    /** Current host drop target, for highlighting the target folder and the insertion line. */
    var activeHostDrop by mutableStateOf<HostDrop?>(null)
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
    private val hostBounds = HashMap<String, Rect>()
    private val folderRange = HashMap<String, Rect>()
    private val folderHeader = HashMap<String, Rect>()

    val isDragging: Boolean get() = draggingHostId != null || draggingFolderName != null

    fun setHostBounds(id: String, rect: Rect) { hostBounds[id] = rect }
    fun setFolderRange(name: String, rect: Rect) { folderRange[name] = rect }
    fun setFolderHeader(name: String, rect: Rect) { folderHeader[name] = rect }

    /** Forget a removed host's row geometry, otherwise bounds would accumulate for stale ids. */
    fun clearHostBounds(id: String) { hostBounds.remove(id) }

    fun startHostDrag(id: String, localOffsetY: Float) {
        draggingHostId = id
        pointerY = (hostBounds[id]?.top ?: 0f) + localOffsetY
    }

    fun startFolderDrag(name: String, localOffsetY: Float) {
        draggingFolderName = name
        pointerY = (folderHeader[name]?.top ?: 0f) + localOffsetY
    }

    fun dragBy(deltaY: Float) {
        pointerY += deltaY
    }

    /**
     * FolderBounds for computing a host drop: centers exclude the dragged host (as moveHostToGroup
     * expects). A folder with no recorded geometry (hasn't gone through layout yet, e.g. scrolled
     * off-screen) is skipped; [hostDropTarget] then clamps the drop to the nearest visible folder.
     */
    fun hostFolderBounds(folders: List<HostFolder>): List<FolderBounds> = folders.mapNotNull { folder ->
        val range = folderRange[folder.name] ?: return@mapNotNull null
        FolderBounds(
            // An empty folder has no group derivable from its hosts, so use its name as the group
            // key, so dropping a host into a freshly created empty group sets Host.group to that
            // name rather than null (else it would land in Ungrouped). The synthetic "Ungrouped"
            // group itself stays null.
            group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL },
            top = range.top,
            bottom = range.bottom,
            otherHostCentersY = folder.hosts
                .filter { it.id != draggingHostId }
                .mapNotNull { hostBounds[it.id]?.let { b -> (b.top + b.bottom) / 2f } },
        )
    }

    fun currentHostDrop(folders: List<HostFolder>): HostDrop? = hostDropTarget(hostFolderBounds(folders), pointerY)

    /** Header centers of the other folders (excluding the dragged one), in list order, for folderDropTarget. */
    fun currentFolderDropIndex(folders: List<HostFolder>): Int {
        val centers = folders
            .filter { it.name != draggingFolderName }
            .mapNotNull { folderHeader[it.name]?.let { b -> (b.top + b.bottom) / 2f } }
        return folderDropTarget(centers, pointerY)
    }

    fun refreshHostDrop(folders: List<HostFolder>) {
        // Only on target change, otherwise every pointer move would redraw all folders (highlighting).
        val next = currentHostDrop(folders)
        if (next != activeHostDrop) activeHostDrop = next
    }

    fun refreshFolderDrop(folders: List<HostFolder>) {
        val next = currentFolderDropIndex(folders)
        if (next != activeFolderDropIndex) activeFolderDropIndex = next
    }

    fun endDrag() {
        draggingHostId = null
        draggingFolderName = null
        activeHostDrop = null
        activeFolderDropIndex = null
    }
}

/** Records a host row's window bounds, read by drag targets on release. */
fun Modifier.hostBoundsAnchor(state: HostDragState, hostId: String): Modifier =
    onGloballyPositioned { state.setHostBounds(hostId, it.boundsInWindow()) }

/** Records a folder block's window bounds, used to determine which folder the pointer is over. */
fun Modifier.folderRangeAnchor(state: HostDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderRange(name, it.boundsInWindow()) }

/** Records a folder header's window bounds, providing centers for folder reordering. */
fun Modifier.folderHeaderAnchor(state: HostDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderHeader(name, it.boundsInWindow()) }

/**
 * Makes a host row draggable. [folders] is read lazily (a fresh list at gesture time), [onDrop]
 * receives the target folder and index and moves the host via the controller. [longPress] selects
 * the gesture start: on desktop, immediately on drag (mouse); on touch (mobile), after a long
 * press, otherwise drag would hijack the list's vertical scroll.
 */
fun Modifier.draggableHostRow(
    state: HostDragState,
    hostId: String,
    folders: () -> List<HostFolder>,
    longPress: Boolean = false,
    onDrop: (HostDrop) -> Unit,
): Modifier = pointerInput(hostId, longPress) {
    var moved = false
    val onStart = { offset: Offset ->
        moved = false
        state.startHostDrag(hostId, offset.y)
        state.refreshHostDrop(folders())
    }
    val onMove = { change: PointerInputChange, amount: Offset ->
        change.consume()
        moved = true
        state.dragBy(amount.y)
        state.refreshHostDrop(folders())
    }
    val onEnd = {
        // Without an actual move (a micro-gesture from a tap) the catalog and disk stay untouched.
        if (moved) state.currentHostDrop(folders())?.let(onDrop)
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    } else {
        detectDragGestures(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    }
}

/**
 * Makes a folder header draggable. [onDrop] receives the target index among folders and moves the
 * block via the controller. [longPress]: see [draggableHostRow], touch starts on a long press to
 * avoid interfering with scroll.
 */
fun Modifier.draggableFolderHeader(
    state: HostDragState,
    name: String,
    folders: () -> List<HostFolder>,
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
        if (moved) onDrop(state.currentFolderDropIndex(folders()))
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    } else {
        detectDragGestures(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    }
}
