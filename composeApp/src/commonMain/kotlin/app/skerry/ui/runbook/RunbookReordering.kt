package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.text.normalizeGroup

/**
 * Move runbooks [movingIds] into group [targetGroup] at [targetIndexInGroup] among its runbooks.
 * Preserves the original relative order among [movingIds].
 */
fun moveRunbooksToGroup(
    runbooks: List<Runbook>,
    movingIds: Set<String>,
    targetGroup: String?,
    targetIndexInGroup: Int,
): List<Runbook> {
    if (movingIds.isEmpty()) return runbooks
    val moving = runbooks.filter { it.id in movingIds }
    if (moving.isEmpty()) return runbooks
    val canonicalTarget = normalizeGroup(targetGroup)
    val buckets = LinkedHashMap<String?, MutableList<Runbook>>()
    for (r in runbooks) {
        if (r.id in movingIds) continue
        buckets.getOrPut(normalizeGroup(r.group)) { mutableListOf() }.add(r)
    }
    val target = buckets.getOrPut(canonicalTarget) { mutableListOf() }
    val insertionIndex = targetIndexInGroup.coerceIn(0, target.size)
    val updatedMoving = moving.map { it.copy(group = canonicalTarget) }
    target.addAll(insertionIndex, updatedMoving)
    return buckets.values.flatten()
}

/**
 * Move runbook [runbookId] into group [targetGroup] at [targetIndexInGroup] among its runbooks.
 * Covers both drag scenarios: reordering within a folder ([targetGroup] == current group) and
 * moving to another (rewriting [Runbook.group]).
 */
fun moveRunbookToGroup(
    runbooks: List<Runbook>,
    runbookId: String,
    targetGroup: String?,
    targetIndexInGroup: Int,
): List<Runbook> = moveRunbooksToGroup(runbooks, setOf(runbookId), targetGroup, targetIndexInGroup)

/**
 * Move folder [group] as a whole to [targetGroupIndex] among folders (runbook order within it is
 * preserved). Index is clamped; unknown [group] leaves the list unchanged.
 */
fun moveRunbookGroup(runbooks: List<Runbook>, group: String?, targetGroupIndex: Int): List<Runbook> {
    val canonical = normalizeGroup(group)
    val buckets = LinkedHashMap<String?, MutableList<Runbook>>()
    for (r in runbooks) {
        buckets.getOrPut(normalizeGroup(r.group)) { mutableListOf() }.add(r)
    }
    val keys = buckets.keys.toMutableList()
    val from = keys.indexOf(canonical)
    if (from < 0) return runbooks
    keys.removeAt(from)
    keys.add(targetGroupIndex.coerceIn(0, keys.size), canonical)
    return keys.flatMap { buckets.getValue(it) }
}

/**
 * Rename group [oldName] to [newName] across all runbooks.
 * Blank/null [newName] ungroups the runbooks (`Runbook.group = null`), which is also how a group is deleted.
 */
fun renameRunbookGroup(
    runbooks: List<Runbook>,
    oldName: String?,
    newName: String?,
): List<Runbook> {
    val canonicalOld = normalizeGroup(oldName) ?: return runbooks
    val canonicalNew = normalizeGroup(newName)
    if (canonicalOld == canonicalNew) return runbooks
    val buckets = LinkedHashMap<String?, MutableList<Runbook>>()
    for (r in runbooks) {
        val group = normalizeGroup(r.group)
        val updatedGroup = if (group == canonicalOld) canonicalNew else group
        val updated = if (group == canonicalOld) r.copy(group = canonicalNew) else r
        buckets.getOrPut(updatedGroup) { mutableListOf() }.add(updated)
    }
    return buckets.values.flatten()
}
