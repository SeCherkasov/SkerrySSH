package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore

/**
 * Editable snippet fields without [Snippet.id]: the create/edit form works on a draft, and
 * [SnippetManager] assigns identity. [id] == null creates a new snippet.
 */
data class SnippetDraft(
    val id: String? = null,
    val label: String,
    val command: String,
    val tags: List<String> = emptyList(),
    val shortcut: String? = null,
)

/** One row of the snippet list: the saved [snippet], updated via [SnippetManager.save]. */
@Stable
class SnippetEntry internal constructor(snippet: Snippet) {
    var snippet: Snippet by mutableStateOf(snippet)
        internal set

    val id: String get() = snippet.id
}

/**
 * Manager of saved snippets: a snippet is a standalone object in [SnippetStore], not part of an open
 * session. CRUD over the library plus [run] — building the command line to send to the active
 * terminal. The terminal is unknown to the manager: the caller passes [send] so it can be tested
 * without a live session.
 */
@Stable
class SnippetManager(
    private val store: SnippetStore,
    private val newId: () -> String,
) {
    var snippets: List<SnippetEntry> by mutableStateOf(store.all().map { SnippetEntry(it) })
        private set

    /**
     * Reload the list from the store. Needed after writes that bypass the manager and on vault unlock:
     * at startup the vault is locked and [store] returns empty; snippets appear after unlock.
     */
    fun reload() {
        snippets = store.all().map { SnippetEntry(it) }
    }

    fun find(id: String?): SnippetEntry? = id?.let { wanted -> snippets.firstOrNull { it.id == wanted } }

    /**
     * Snippet with the given hotkey [shortcut] (canonical form, see [Snippet.shortcut]) or `null`.
     * Used by the global hotkey handler. An empty/`null` query is always `null`. On a collision the
     * first is returned — the UI prevents assigning one hotkey twice, but reads don't rely on it.
     */
    fun forShortcut(shortcut: String?): SnippetEntry? {
        if (shortcut.isNullOrBlank()) return null
        return snippets.firstOrNull { it.snippet.shortcut == shortcut }
    }

    /**
     * Another snippet already holding hotkey [shortcut], or `null`. [excludingId] is the edited
     * snippet's id (its own hotkey isn't a collision). An empty/`null` hotkey is always `null`. Used
     * by the editor to prevent assigning one chord twice (else [forShortcut] would silently take the
     * first).
     */
    fun shortcutConflict(shortcut: String?, excludingId: String?): SnippetEntry? {
        if (shortcut.isNullOrBlank()) return null
        return snippets.firstOrNull { it.id != excludingId && it.snippet.shortcut == shortcut }
    }

    /**
     * Create (if [SnippetDraft.id] == null) or update a snippet and write it to the store. Returns the
     * assigned id. Editing an existing one updates its row in place.
     */
    fun save(draft: SnippetDraft): String {
        val id = draft.id ?: newId()
        val snippet = Snippet(
            id = id,
            label = draft.label,
            command = draft.command,
            tags = draft.tags,
            shortcut = draft.shortcut?.takeIf { it.isNotBlank() },
        )
        store.put(snippet)
        val existing = find(id)
        if (existing != null) existing.snippet = snippet else snippets = snippets + SnippetEntry(snippet)
        return id
    }

    /** Delete a snippet: remove it from the store and the list. */
    fun delete(id: String) {
        store.remove(id)
        snippets = snippets.filterNot { it.id == id }
    }

    /**
     * Run a snippet: send its command plus a newline to [send] (the caller binds [send] to the active
     * terminal). Unknown id is a no-op. The command runs as-is, unescaped — it's user-saved text, not
     * untrusted input.
     */
    fun run(id: String, send: (String) -> Unit) {
        val snippet = find(id)?.snippet ?: return
        send(snippet.command + "\n")
    }
}
