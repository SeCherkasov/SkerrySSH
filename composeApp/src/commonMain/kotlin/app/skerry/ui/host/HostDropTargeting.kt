package app.skerry.ui.host

/**
 * Pure drop geometry for sidebar drag-and-drop: given the pointer's vertical position, computes
 * which folder and index a host drops into ([hostDropTarget]) or where a folder gets reordered to
 * ([folderDropTarget]). Kept separate from Compose gesture handling so it can be unit-tested
 * ([app.skerry.ui.host.HostDropTargetingTest]); gestures only supply window-pixel coordinates.
 */

/**
 * Geometry of one sidebar folder in window coordinates. [top]/[bottom] is the folder block's
 * vertical range (used to pick the target folder). [otherHostCentersY] holds the row centers of
 * this folder's hosts excluding the dragged one, so the index matches [moveHostToGroup]'s contract
 * of removing the dragged host before inserting.
 */
data class FolderBounds(
    val group: String?,
    val top: Float,
    val bottom: Float,
    val otherHostCentersY: List<Float>,
)

/** Where to drop a host: target folder's group plus index among its hosts (excluding the dragged one). */
data class HostDrop(val group: String?, val index: Int)

/**
 * Target folder is the one whose range contains [pointerY]; above the first / below the last
 * clamps to that edge. Index within it is the count of the folder's hosts whose center is above
 * the pointer. `null` if there are no folders.
 */
fun hostDropTarget(folders: List<FolderBounds>, pointerY: Float): HostDrop? {
    if (folders.isEmpty()) return null
    val folder = folders.firstOrNull { pointerY >= it.top && pointerY <= it.bottom }
        ?: if (pointerY < folders.first().top) folders.first() else folders.last()
    return HostDrop(folder.group, folder.otherHostCentersY.count { it < pointerY })
}

/**
 * Insertion index for a folder among the others: count of headers above [pointerY]. [headerCentersY]
 * excludes the dragged folder, so the index matches [moveGroup]'s contract.
 */
fun folderDropTarget(headerCentersY: List<Float>, pointerY: Float): Int =
    headerCentersY.count { it < pointerY }
