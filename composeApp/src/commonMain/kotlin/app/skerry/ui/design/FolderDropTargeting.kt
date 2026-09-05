package app.skerry.ui.design

/**
 * Pure drop geometry for the lists that can be dragged into shape — the host sidebar, the snippet
 * library, the runbooks: given the pointer's vertical position, computes which folder and index a
 * row drops into ([itemDropTarget]) or where a folder gets reordered to ([folderDropTarget]). Kept
 * separate from Compose gesture handling so it can be unit-tested ([FolderDropTargetingTest]);
 * gestures only supply window-pixel coordinates.
 */

/**
 * Geometry of one folder in window coordinates. [top]/[bottom] is the folder block's vertical range
 * (used to pick the target folder). [otherItemCentersY] holds the row centers of this folder's rows
 * excluding the dragged one, so the index matches [moveIntoFolder]'s contract of removing the
 * dragged row before inserting.
 */
data class FolderBounds(
    val group: String?,
    val top: Float,
    val bottom: Float,
    val otherItemCentersY: List<Float>,
)

/** Where to drop a row: target folder's group plus index among its rows (excluding the dragged one). */
data class FolderDrop(val group: String?, val index: Int)

/**
 * Target folder is the one whose range contains [pointerY]; above the first / below the last
 * clamps to that edge. Index within it is the count of the folder's rows whose center is above
 * the pointer. `null` if there are no folders.
 *
 * `null` means the drop is dropped: the row snaps back and nothing is written. That is only ever
 * reached with no folder measured at all, which holds because every caller lays its folders out in
 * a plain scrollable `Column` — a lazy list would leave the off-screen ones unmeasured and turn a
 * drop over them into a silent no-op.
 */
fun itemDropTarget(folders: List<FolderBounds>, pointerY: Float): FolderDrop? {
    if (folders.isEmpty()) return null
    val folder = folders.firstOrNull { pointerY >= it.top && pointerY <= it.bottom }
        ?: if (pointerY < folders.first().top) folders.first() else folders.last()
    return FolderDrop(folder.group, folder.otherItemCentersY.count { it < pointerY })
}

/**
 * Insertion index for a folder among the others: count of headers above [pointerY]. [headerCentersY]
 * excludes the dragged folder, so the index matches [moveFolder]'s contract.
 */
fun folderDropTarget(headerCentersY: List<Float>, pointerY: Float): Int =
    headerCentersY.count { it < pointerY }
