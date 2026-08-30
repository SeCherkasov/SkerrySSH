package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.text.normalizeGroup

/**
 * Move snippets [movingIds] into group [targetGroup] at [targetIndexInGroup] among its snippets.
 * Preserves the original relative order among [movingIds].
 */
fun moveSnippetsToGroup(
    snippets: List<Snippet>,
    movingIds: Set<String>,
    targetGroup: String?,
    targetIndexInGroup: Int,
): List<Snippet> {
    if (movingIds.isEmpty()) return snippets
    val moving = snippets.filter { it.id in movingIds }
    if (moving.isEmpty()) return snippets
    val canonicalTarget = normalizeGroup(targetGroup)
    val buckets = LinkedHashMap<String?, MutableList<Snippet>>()
    for (s in snippets) {
        if (s.id in movingIds) continue
        buckets.getOrPut(normalizeGroup(s.group)) { mutableListOf() }.add(s)
    }
    val target = buckets.getOrPut(canonicalTarget) { mutableListOf() }
    val insertionIndex = targetIndexInGroup.coerceIn(0, target.size)
    val updatedMoving = moving.map { it.copy(group = canonicalTarget) }
    target.addAll(insertionIndex, updatedMoving)
    return buckets.values.flatten()
}

/**
 * Move snippet [snippetId] into group [targetGroup] at [targetIndexInGroup] among its snippets.
 * Covers both drag scenarios: reordering within a folder ([targetGroup] == current group) and
 * moving to another (rewriting [Snippet.group]).
 */
fun moveSnippetToGroup(
    snippets: List<Snippet>,
    snippetId: String,
    targetGroup: String?,
    targetIndexInGroup: Int,
): List<Snippet> = moveSnippetsToGroup(snippets, setOf(snippetId), targetGroup, targetIndexInGroup)

/**
 * Move folder [group] as a whole to [targetGroupIndex] among folders (snippet order within it is
 * preserved). Index is clamped; unknown [group] leaves the list unchanged.
 */
fun moveSnippetGroup(snippets: List<Snippet>, group: String?, targetGroupIndex: Int): List<Snippet> {
    val canonical = normalizeGroup(group)
    val buckets = LinkedHashMap<String?, MutableList<Snippet>>()
    for (s in snippets) {
        buckets.getOrPut(normalizeGroup(s.group)) { mutableListOf() }.add(s)
    }
    val keys = buckets.keys.toMutableList()
    val from = keys.indexOf(canonical)
    if (from < 0) return snippets
    keys.removeAt(from)
    keys.add(targetGroupIndex.coerceIn(0, keys.size), canonical)
    return keys.flatMap { buckets.getValue(it) }
}

/**
 * Rename group [oldName] to [newName] across all snippets.
 * Blank/null [newName] ungroups the snippets (`Snippet.group = null`), which is also how a group is deleted.
 */
fun renameSnippetGroup(
    snippets: List<Snippet>,
    oldName: String?,
    newName: String?,
): List<Snippet> {
    val canonicalOld = normalizeGroup(oldName) ?: return snippets
    val canonicalNew = normalizeGroup(newName)
    if (canonicalOld == canonicalNew) return snippets
    val buckets = LinkedHashMap<String?, MutableList<Snippet>>()
    for (s in snippets) {
        val group = normalizeGroup(s.group)
        val updatedGroup = if (group == canonicalOld) canonicalNew else group
        val updated = if (group == canonicalOld) s.copy(group = canonicalNew) else s
        buckets.getOrPut(updatedGroup) { mutableListOf() }.add(updated)
    }
    return buckets.values.flatten()
}
