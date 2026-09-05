package app.skerry.ui.design

/**
 * Pure reorderings of a flat list whose items carry a folder name, for manual drag-and-drop sorting.
 *
 * Order's source of truth is the list order itself — there is no sort field on a host, a snippet or
 * a runbook. Folders are derived from that order, so every operation here flattens the result back
 * through folder buckets: a folder's items stay one contiguous block, which is what makes "the
 * folder's third row" a position the next drag can be computed against.
 *
 * One implementation for three lists ([FolderItems] is what tells them apart). They differ in two
 * details that are not cosmetic — what counts as "no folder" and where that bucket sits — so both
 * are asked of the adapter rather than assumed here.
 */

/**
 * How one list's items answer the four questions a reordering asks of them.
 *
 * [canonicalName] must agree with how the list is *bucketed on screen*: a drop index is counted
 * against rendered rows, so an item this reordering files under a different folder than the renderer
 * does would be inserted at an index taken from somebody else's folder.
 */
interface FolderItems<T> {
    fun idOf(item: T): String
    fun folderOf(item: T): String?
    fun withFolder(item: T, folder: String?): T

    /** The folder a stored name actually means; `null` for the synthetic no-folder bucket. */
    fun canonicalName(folder: String?): String?

    /**
     * Whether the no-folder bucket is pinned to the end of the list rather than kept where its first
     * item put it. The snippet and runbook libraries draw it last always ([foldersOf]), so their
     * stored order has to agree — otherwise a folder dragged past it lands at an index that counts a
     * bucket the user cannot see there. The host sidebar draws it in first-appearance order like any
     * other folder ([app.skerry.ui.host.groupHostsByFolder]), and keeps it that way.
     */
    val ungroupedLast: Boolean
}

private fun <T> FolderItems<T>.keyOf(item: T): String? = canonicalName(folderOf(item))

/** "Folder -> items" buckets, in the order the list renders them. */
private fun <T> FolderItems<T>.bucketize(items: List<T>): LinkedHashMap<String?, MutableList<T>> {
    val buckets = LinkedHashMap<String?, MutableList<T>>()
    for (item in items) buckets.getOrPut(keyOf(item)) { mutableListOf() }.add(item)
    if (ungroupedLast && buckets.size > 1 && buckets.keys.last() != null) {
        // The keys are the folder order a drop index is counted against, so the bucket has to be
        // last here and not only after flatten: a bucket left mid-list makes moveFolder count
        // folders in an order the screen never draws. LinkedHashMap has no "move to end", so it is
        // removed and re-inserted; only the null key can ever need it.
        buckets.remove(null)?.let { buckets[null] = it }
    }
    return buckets
}

private fun <T> FolderItems<T>.flatten(buckets: Map<String?, List<T>>): List<T> =
    if (!ungroupedLast) {
        buckets.values.flatten()
    } else {
        buckets.filterKeys { it != null }.values.flatten() + buckets[null].orEmpty()
    }

/**
 * Move [movingIds] into folder [targetFolder] at [targetIndexInFolder] among that folder's items,
 * keeping the movers' relative order. Covers both drag scenarios: reordering inside a folder
 * ([targetFolder] == the current one) and moving to another, which rewrites the items' folder. The
 * index is clamped; ids the list does not hold are ignored, and an empty selection leaves it alone.
 */
fun <T> moveIntoFolder(
    items: List<T>,
    adapter: FolderItems<T>,
    movingIds: Set<String>,
    targetFolder: String?,
    targetIndexInFolder: Int,
): List<T> {
    if (movingIds.isEmpty()) return items
    val moving = items.filter { adapter.idOf(it) in movingIds }
    if (moving.isEmpty()) return items
    val target = adapter.canonicalName(targetFolder)
    val buckets = adapter.bucketize(items.filterNot { adapter.idOf(it) in movingIds })
    val bucket = buckets.getOrPut(target) { mutableListOf() }
    bucket.addAll(
        targetIndexInFolder.coerceIn(0, bucket.size),
        moving.map { adapter.withFolder(it, target) },
    )
    return adapter.flatten(buckets)
}

/**
 * Move folder [folder] as a whole to [targetFolderIndex] among the folders, its items keeping their
 * order inside it. The index is clamped; a folder the list does not have leaves it unchanged.
 */
fun <T> moveFolder(
    items: List<T>,
    adapter: FolderItems<T>,
    folder: String?,
    targetFolderIndex: Int,
): List<T> {
    val canonical = adapter.canonicalName(folder)
    val buckets = adapter.bucketize(items)
    val keys = buckets.keys.toMutableList()
    val from = keys.indexOf(canonical)
    if (from < 0) return items
    keys.removeAt(from)
    keys.add(targetFolderIndex.coerceIn(0, keys.size), canonical)
    val moved = LinkedHashMap<String?, MutableList<T>>()
    keys.forEach { moved[it] = buckets.getValue(it) }
    return adapter.flatten(moved)
}

/**
 * Rename folder [oldName] to [newName] across every item filed under it. A blank/`null` [newName]
 * un-files them — the same path that "deletes" a folder, keeping the items. Merging into an existing
 * folder is allowed and flattens like the moves above, so the merged folder stays one block.
 * Unknown/blank [oldName], or old == new, leaves the list unchanged.
 */
fun <T> renameFolder(
    items: List<T>,
    adapter: FolderItems<T>,
    oldName: String?,
    newName: String?,
): List<T> {
    val from = adapter.canonicalName(oldName) ?: return items
    val to = adapter.canonicalName(newName)
    if (from == to) return items
    val renamed = items.map { if (adapter.keyOf(it) == from) adapter.withFolder(it, to) else it }
    return adapter.flatten(adapter.bucketize(renamed))
}

/**
 * Translate a drop index taken over a filtered view into an index in the full list.
 *
 * A list shows what a search or a chip left of it, while order lives in the whole list: dropping
 * "after the second visible row" must not count the rows the filter hid, or a record invisible here
 * would be jumped over and silently reordered. [visiblePositions] are the full-list positions of the
 * rows the user can see, in order; [visibleIndex] is the insertion point among them (0 = before the
 * first, size = after the last).
 *
 * With nothing visible to place it against the drop carries no order at all, and [whenNothingVisible]
 * decides what that means: the end of the list for a move into another folder (the folder change is
 * the information), the item's own current position for a move inside the one it is already in —
 * otherwise a jiggle inside a folder the filter left it alone in would step it over every hidden row.
 */
fun filteredIndexToFull(
    fullSize: Int,
    visiblePositions: List<Int>,
    visibleIndex: Int,
    whenNothingVisible: Int = fullSize,
): Int = when {
    visiblePositions.isEmpty() -> whenNothingVisible
    visibleIndex <= 0 -> visiblePositions.first()
    visibleIndex >= visiblePositions.size -> visiblePositions.last() + 1
    else -> visiblePositions[visibleIndex]
}

/**
 * A list, the part of it a filter left on screen, and the two accessors a drop is read through.
 *
 * The four travel together because a translation is only meaningful when they come from one list: a
 * drop index counted against rows drawn from [visible] becomes a position in [all], and a mismatched
 * pair would put it in someone else's folder.
 */
class FilteredFolderList<T>(
    val all: List<T>,
    val visible: List<T>,
    val group: (T) -> String?,
    val itemKey: (T) -> String,
)

/**
 * The index [moveIntoFolder] needs when the drop was taken over a filtered list:
 * [visibleIndexInFolder] counts the visible rows of [targetFolder] that are not the dragged one,
 * which is what the sections report, and the answer counts all of them.
 *
 * Folders are keyed the way [foldersOf] buckets them, because that is what drew the rows the index
 * was counted against.
 */
fun <T> FilteredFolderList<T>.fullIndexInFolder(
    movingId: String,
    targetFolder: String?,
    visibleIndexInFolder: Int,
): Int {
    val target = storedFolderName(targetFolder)
    fun rowsOfTarget(list: List<T>) = list.filter { storedFolderName(group(it)) == target }
    val here = rowsOfTarget(all).indexOfFirst { itemKey(it) == movingId }
    val full = rowsOfTarget(all).filterNot { itemKey(it) == movingId }.map(itemKey)
    val positions = rowsOfTarget(visible)
        .mapNotNull { row -> full.indexOf(itemKey(row)).takeIf { it >= 0 } }
    // Already in this folder: staying put is the answer when nothing else is on screen.
    return filteredIndexToFull(full.size, positions, visibleIndexInFolder, if (here < 0) full.size else here)
}

/**
 * The index [moveFolder] needs when the folder drag was taken over a filtered list. Both counts run
 * over the folders other than [movingFolder], which is the order the headers are drawn in and the
 * order [moveFolder] reinserts against. A folder whose every row the filter hid is not on screen at
 * all, so it is simply one of the positions the visible ones are mapped onto; with no other folder
 * on screen the drag conveys nothing and the folder keeps the place it has.
 */
fun <T> FilteredFolderList<T>.fullFolderIndex(movingFolder: String?, visibleFolderIndex: Int): Int {
    val moving = storedFolderName(movingFolder)
    fun folderNames(list: List<T>) = foldersOf(list, ordered = true, group).map { it.name }
    val here = folderNames(all).indexOf(moving)
    val full = folderNames(all).filterNot { it == moving }
    val positions = folderNames(visible)
        .filterNot { it == moving }
        .mapNotNull { name -> full.indexOf(name).takeIf { it >= 0 } }
    return filteredIndexToFull(full.size, positions, visibleFolderIndex, if (here < 0) full.size else here)
}
