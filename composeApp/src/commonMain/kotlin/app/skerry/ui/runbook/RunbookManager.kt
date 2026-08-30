package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookStore
import app.skerry.shared.runbook.isRunnable
import app.skerry.shared.runbook.withId
import app.skerry.shared.tag.normalizeTags
import app.skerry.shared.text.normalizeGroup

/**
 * Editable runbook fields without [Runbook.id]: the create/edit form works on a draft and
 * [RunbookManager] assigns identity. [id] == null creates a new runbook.
 */
data class RunbookDraft(
    val id: String? = null,
    val label: String,
    val description: String = "",
    val steps: List<RunbookStep> = emptyList(),
    val tags: List<String> = emptyList(),
    val policy: RunbookPolicy = RunbookPolicy(),
    val group: String? = null,
)

/** One row of the runbook list: the saved [runbook], updated via [RunbookManager.save]. */
@Stable
class RunbookEntry internal constructor(runbook: Runbook) {
    var runbook: Runbook by mutableStateOf(runbook)
        internal set

    val id: String get() = runbook.id
}

/**
 * Library of saved runbooks over [RunbookStore] — the same shape as
 * [app.skerry.ui.snippet.SnippetManager], minus running: a run needs a terminal and its own
 * lifecycle, so it lives in [RunbookRunner].
 *
 * Tags are canonicalized on read for the same reason as snippets': records written by an older
 * client (or arriving through sync) may hold "#DB" and "db" side by side, which would split one
 * category into two. Canonicalizing on read rather than rewriting avoids racing the background sync
 * merge; the next [save] persists the canonical form anyway.
 */
@Stable
class RunbookManager(
    private val store: RunbookStore,
    private val newId: () -> String,
) {
    var runbooks: List<RunbookEntry> by mutableStateOf(store.all().map { RunbookEntry(it.canonical()) })
        private set

    /** Reload from the store — needed after sync writes and on vault unlock (locked reads empty). */
    fun reload() {
        runbooks = store.all().map { RunbookEntry(it.canonical()) }
    }

    fun find(id: String?): RunbookEntry? = id?.let { wanted -> runbooks.firstOrNull { it.id == wanted } }

    /**
     * Create (if [RunbookDraft.id] == null) or update a runbook and write it to the store. Returns
     * the assigned id. Steps that say nothing to do are dropped — an empty row is how the editor
     * starts a step, not something to run — and every surviving step is given an id if it lacks one,
     * so the editor can key rows through a reorder.
     */
    fun save(draft: RunbookDraft): String {
        val id = draft.id ?: newId()
        val steps = draft.steps
            .filter { it.isRunnable }
            .map { if (it.id.isBlank()) it.withId(newId()) else it }
        val runbook = Runbook(
            id = id,
            label = draft.label.trim(),
            description = draft.description.trim(),
            steps = steps,
            tags = normalizeTags(draft.tags),
            policy = draft.policy,
            group = normalizeGroup(draft.group),
        )
        store.put(runbook)
        val existing = find(id)
        if (existing != null) {
            existing.runbook = runbook
            runbooks = runbooks.map { if (it.id == id) RunbookEntry(runbook) else it }
        } else {
            runbooks = runbooks + RunbookEntry(runbook)
        }
        return id
    }

    /** Move runbook [runbookId] to [targetGroup] at [targetIndexInGroup]. */
    fun moveRunbook(runbookId: String, targetGroup: String?, targetIndexInGroup: Int) {
        moveRunbooks(setOf(runbookId), targetGroup, targetIndexInGroup)
    }

    /** Move multiple runbooks [runbookIds] to [targetGroup] at [targetIndexInGroup]. */
    fun moveRunbooks(runbookIds: Set<String>, targetGroup: String?, targetIndexInGroup: Int) {
        store.reorder { moveRunbooksToGroup(it, runbookIds, targetGroup, targetIndexInGroup) }
        runbooks = store.all().map { RunbookEntry(it.canonical()) }
    }

    /** Move folder [group] to [targetGroupIndex] among folders. */
    fun moveGroup(group: String?, targetGroupIndex: Int) {
        store.reorder { moveRunbookGroup(it, group, targetGroupIndex) }
        runbooks = store.all().map { RunbookEntry(it.canonical()) }
    }

    /** Rename group [oldName] to [newName] across all runbooks. */
    fun renameGroup(oldName: String, newName: String) {
        store.reorder { renameRunbookGroup(it, oldName, newName) }
        runbooks = store.all().map { RunbookEntry(it.canonical()) }
    }

    /** Delete group [name]: ungroups its runbooks, setting group to null (items are kept). */
    fun deleteGroup(name: String) {
        store.reorder { renameRunbookGroup(it, name, null) }
        runbooks = store.all().map { RunbookEntry(it.canonical()) }
    }

    /** Delete a runbook: remove it from the store and the list. */
    fun delete(id: String) {
        store.remove(id)
        runbooks = runbooks.filterNot { it.id == id }
    }
}

private fun Runbook.canonical(): Runbook {
    val canonical = normalizeTags(tags)
    return if (canonical == tags) this else copy(tags = canonical)
}
