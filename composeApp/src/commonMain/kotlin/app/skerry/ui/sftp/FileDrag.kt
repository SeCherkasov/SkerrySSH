package app.skerry.ui.sftp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import app.skerry.shared.files.FileItem
import app.skerry.ui.design.detectDeadZoneDragGestures
import app.skerry.ui.design.unclippedWindowRect

/** Which of the two panes is active (receives the keyboard and cursor highlight). */
internal enum class ActivePane { Local, Remote }

/** A drag released over the other pane: [items] of [source] go there, moved rather than copied when [move]. */
internal data class FileDrop(val source: ActivePane, val items: List<FileItem>, val move: Boolean)

/**
 * One file drag between the two panes of the SFTP view. Rows start it ([fileDragSource]), each pane
 * files its window bounds ([fileDropAnchor]), the view reads [target] to light up the pane under the
 * pointer and acts on what [drop] returns. Shift is tracked whether or not a drag is running
 * ([fileDragModifiers]), so a Shift held before the press already reads as a move.
 *
 * Pointer and bounds are both window coordinates: a row reports the pointer from its own node, the
 * panes from theirs, and the window is the only space the two share.
 */
@Stable
internal class FileDragState {
    /** The pane the drag started in; `null` while no drag runs. */
    var source: ActivePane? by mutableStateOf(null)
        private set

    /** What the drag carries, fixed when it started: the marked rows, or the unmarked row it was picked up by. */
    var items: List<FileItem> by mutableStateOf(emptyList())
        private set

    /**
     * Pointer position in window coordinates. Written on every move, so only the layout phase may
     * read it (the chip's offset) — a composition that reads it redraws the panel per mouse event.
     */
    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Shift is held: the drop moves instead of copying. */
    var move: Boolean by mutableStateOf(false)
        private set

    /**
     * The pane a release would drop into: the one under the pointer, never the one the drag came
     * from. Stored and written only when it changes, which is what lets composition read it.
     */
    var target: ActivePane? by mutableStateOf(null)
        private set

    // Plain map: only [target], derived from it, is read by composition.
    private val bounds = mutableMapOf<ActivePane, Rect>()

    val active: Boolean get() = source != null

    fun setBounds(side: ActivePane, rect: Rect) {
        bounds[side] = rect
        refreshTarget()
    }

    fun setShift(held: Boolean) {
        move = held
    }

    /** Starts a drag of [carried] out of [from] at window position [at]. Nothing to carry — no drag. */
    fun start(from: ActivePane, carried: List<FileItem>, at: Offset) {
        if (carried.isEmpty()) return
        items = carried
        pointer = at
        source = from
        refreshTarget()
    }

    fun moveTo(at: Offset) {
        if (!active) return
        pointer = at
        refreshTarget()
    }

    fun cancel() {
        source = null
        items = emptyList()
        target = null
    }

    /** Ends the drag; the drop to perform, or `null` when it was released over no other pane. */
    fun drop(): FileDrop? {
        val from = source ?: return null
        val result = target?.let { FileDrop(from, items, move) }
        cancel()
        return result
    }

    private fun refreshTarget() {
        val from = source
        val next = if (from == null) null else bounds.entries.firstOrNull { (side, rect) -> side != from && rect.contains(pointer) }?.key
        if (next != target) target = next
    }
}

/**
 * What a pane's listing needs to hand its rows to a file drag: the shared [state], which pane it is,
 * and what the view does with a drop.
 */
internal class FileDragBinding(val state: FileDragState, val side: ActivePane, val onDrop: (FileDrop) -> Unit)

/** Files the pane's window bounds with [state]. */
internal fun Modifier.fileDropAnchor(state: FileDragState, side: ActivePane): Modifier =
    onGloballyPositioned { state.setBounds(side, it.unclippedWindowRect()) }

/**
 * Watches every pointer event under the view for Shift, before any child sees it. A drag captures
 * the pointer in the row it started on, and the row's gesture only hears about positions; the key
 * modifiers ride on the event, which the whole ancestor chain of that row receives.
 */
internal fun Modifier.fileDragModifiers(state: FileDragState): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                state.setShift(event.keyboardModifiers.isShiftPressed)
            }
        }
    }

/**
 * Makes a listing row the handle of a file drag. The press itself stays a click: the drag claims the
 * pointer only past the shared dead zone ([detectDeadZoneDragGestures]), so cursor placement and
 * double-click entry are untouched. [onStart] runs once the drag is real and returns what it carries.
 */
@Composable
internal fun Modifier.fileDragSource(
    state: FileDragState,
    side: ActivePane,
    onStart: () -> List<FileItem>,
    onDrop: (FileDrop) -> Unit,
): Modifier {
    // The gesture outlives recompositions (keyed on the drag, not the lambdas), so it reads the
    // latest callbacks and the row's latest coordinates through holders that live as long as it does.
    val currentStart by rememberUpdatedState(onStart)
    val currentDrop by rememberUpdatedState(onDrop)
    val holder = remember { CoordinatesHolder() }
    return onGloballyPositioned { holder.coordinates = it }
        .pointerInput(state, side) {
            detectDeadZoneDragGestures(
                onStart = { at -> holder.toWindow(at)?.let { state.start(side, currentStart(), it) } },
                onMove = { change, _ -> holder.toWindow(change.position)?.let(state::moveTo) },
                onEnd = { state.drop()?.let(currentDrop) },
                onCancel = state::cancel,
            )
        }
}

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null

    fun toWindow(local: Offset): Offset? = coordinates?.takeIf { it.isAttached }?.localToWindow(local)
}
