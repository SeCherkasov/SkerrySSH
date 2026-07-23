package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetStore
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.captureSnippetRunEnvironment
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.shared.tag.normalizeTags

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

/**
 * Snippets saved before tags were canonicalized (and any written by an older client through sync)
 * may hold "#DB"/"db" side by side, which would split one category into several sections. They are
 * canonicalized on read rather than rewritten in place: a read-modify-write over the vault would
 * race the background sync merge, and the next [SnippetManager.save] persists the canonical form
 * anyway.
 */
private fun Snippet.canonical(): Snippet {
    val canonical = normalizeTags(tags)
    return if (canonical == tags) this else copy(tags = canonical)
}

/**
 * A snippet run waiting for the dynamic-variable dialog ([SnippetManager.pendingRun]): the command
 * contains `${{…}}` placeholders, so the resolved line must be previewed and confirmed before
 * anything reaches the terminal. [environment] is captured when the run was initiated — the
 * previewed date/uuid/random values are exactly the ones sent (TOCTOU rule, coding-guidelines §3).
 * [initialParams] prefills prompted parameters with the values from this snippet's previous run.
 * [recording] — the target terminal is recording a cast, so the dialog warns that the resolved
 * line (secrets included) will be captured.
 */
@Stable
class SnippetRunRequest internal constructor(
    val snippet: Snippet,
    val segments: List<SnippetSegment>,
    val environment: SnippetRunEnvironment,
    val recording: Boolean,
    val initialParams: Map<String, String>,
    internal val sendLine: (String) -> Unit,
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
    private val environment: () -> SnippetRunEnvironment = ::captureSnippetRunEnvironment,
    private val newId: () -> String,
) {
    var snippets: List<SnippetEntry> by mutableStateOf(store.all().map { SnippetEntry(it.canonical()) })
        private set

    /** Run awaiting the variable dialog; the app shell renders [SnippetRunDialog] while non-null. */
    var pendingRun: SnippetRunRequest? by mutableStateOf(null)
        private set

    /** Last confirmed prompted-parameter values per snippet id — session-only, never persisted. */
    private val lastParams = mutableMapOf<String, Map<String, String>>()

    /**
     * Reload the list from the store. Needed after writes that bypass the manager and on vault unlock:
     * at startup the vault is locked and [store] returns empty; snippets appear after unlock.
     */
    fun reload() {
        snippets = store.all().map { SnippetEntry(it.canonical()) }
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
            tags = normalizeTags(draft.tags),
            shortcut = draft.shortcut?.takeIf { it.isNotBlank() },
        )
        store.put(snippet)
        val existing = find(id)
        if (existing != null) existing.snippet = snippet else snippets = snippets + SnippetEntry(snippet)
        return id
    }

    /**
     * Rename tag [oldTag] to [newTag] across every snippet that carries it, mirroring host group
     * rename ([app.skerry.ui.host.HostManagerController.renameGroup]) — tags double as the library's
     * categories. [newTag] is canonicalized like typed tag input ([parseSnippetTags]); a blank or
     * unchanged target is a no-op. Order is preserved and a collision with an existing tag merges
     * (via [normalizeTags]). Each touched snippet is persisted through the same path as an edit.
     *
     * Returns the canonical target the tag was renamed to, or `null` on a no-op (blank/unchanged
     * target) — so the caller can migrate view state (e.g. a collapsed section) onto the new key.
     */
    fun renameTag(oldTag: String, newTag: String): String? {
        val target = parseSnippetTags(newTag).firstOrNull() ?: return null
        if (target == oldTag) return null
        for (entry in snippets) {
            val tags = entry.snippet.tags
            if (oldTag !in tags) continue
            val renamed = normalizeTags(tags.map { if (it == oldTag) target else it })
            val updated = entry.snippet.copy(tags = renamed)
            store.put(updated)
            entry.snippet = updated
        }
        return target
    }

    /** Delete a snippet: remove it from the store and the list. */
    fun delete(id: String) {
        store.remove(id)
        snippets = snippets.filterNot { it.id == id }
    }

    /**
     * Run a snippet: send its command plus a newline to [send] (the caller binds [send] to the active
     * terminal). Unknown id is a no-op. A plain command runs as-is, unescaped — it's user-saved text,
     * not untrusted input. A command with `${{…}}` variables never runs directly: it parks in
     * [pendingRun] until the dialog resolves and confirms it (variable values — clipboard, vault
     * secrets, Teams-shared templates — ARE untrusted; see [SnippetTemplate.resolve]).
     * [recording] — whether the target terminal is recording, for the dialog's warning.
     */
    fun run(id: String, recording: Boolean = false, send: (String) -> Unit) {
        // A run initiated while the variable dialog is up would silently replace (or race) the
        // request the user is looking at — first request wins until confirmed or dismissed.
        if (pendingRun != null) return
        val snippet = find(id)?.snippet ?: return
        val segments = SnippetTemplate.parse(snippet.command)
        if (segments.none { it is SnippetSegment.Variable }) {
            // Strip bidi/format tricks from the literal text too (Teams-shared snippets are not
            // "user-saved text"); an intentional multi-line script passes through unchanged.
            send(stripUnsafeFormatChars(snippet.command) + "\n")
            return
        }
        pendingRun = SnippetRunRequest(
            snippet = snippet,
            segments = segments,
            environment = environment(),
            recording = recording,
            initialParams = lastParams[snippet.id].orEmpty(),
            sendLine = send,
        )
    }

    /**
     * The dialog confirmed the previewed [line]: send it (plus the newline) to the pending run's
     * terminal, remember [params] for the snippet's next run, close the dialog. No-op without a
     * pending run (double-click after confirm).
     */
    fun confirmRun(line: String, params: Map<String, String>) {
        val pending = pendingRun ?: return
        pendingRun = null
        if (params.isNotEmpty()) lastParams[pending.snippet.id] = params
        pending.sendLine(line + "\n")
    }

    /** Close the variable dialog without running (Cancel/Esc/vault lock). */
    fun dismissRun() {
        pendingRun = null
    }
}
