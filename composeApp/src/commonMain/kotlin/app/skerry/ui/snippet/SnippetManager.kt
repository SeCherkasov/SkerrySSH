package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.text.normalizeGroup
import app.skerry.shared.text.normalizeNotes
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetStore
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.captureSnippetRunEnvironment
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.shared.tag.normalizeTags
import app.skerry.ui.design.boundedVisibleText
import app.skerry.shared.terminal.displayColumns

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
    val notes: String? = null,
    val group: String? = null,
)

/**
 * Snippets saved before tags were canonicalized (and any written by an older client through sync)
 * may hold "#DB"/"db" side by side, which would split one category into several sections. They are
 * canonicalized on read rather than rewritten in place: a read-modify-write over the vault would
 * race the background sync merge, and the next [SnippetManager.save] persists the canonical form
 * anyway.
 */
internal fun Snippet.canonical(): Snippet {
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
    internal val sendLine: (line: String, secrets: List<String>) -> Unit,
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
            notes = draft.notes?.let(::normalizeNotes),
            group = normalizeGroup(draft.group),
        )
        store.put(snippet)
        val existing = find(id)
        if (existing != null) {
            existing.snippet = snippet
            snippets = snippets.map { if (it.id == id) SnippetEntry(snippet) else it }
        } else {
            snippets = snippets + SnippetEntry(snippet)
        }
        return id
    }

    /** Move snippet [snippetId] to [targetGroup] at [targetIndexInGroup]. */
    fun moveSnippet(snippetId: String, targetGroup: String?, targetIndexInGroup: Int) {
        moveSnippets(setOf(snippetId), targetGroup, targetIndexInGroup)
    }

    /** Move multiple snippets [snippetIds] to [targetGroup] at [targetIndexInGroup]. */
    fun moveSnippets(snippetIds: Set<String>, targetGroup: String?, targetIndexInGroup: Int) {
        store.reorder { moveSnippetsToGroup(it, snippetIds, targetGroup, targetIndexInGroup) }
        snippets = store.all().map { SnippetEntry(it.canonical()) }
    }

    /** Move folder [group] to [targetGroupIndex] among folders. */
    fun moveGroup(group: String?, targetGroupIndex: Int) {
        store.reorder { moveSnippetGroup(it, group, targetGroupIndex) }
        snippets = store.all().map { SnippetEntry(it.canonical()) }
    }

    /** Rename group [oldName] to [newName] across all snippets. */
    fun renameGroup(oldName: String, newName: String) {
        store.reorder { renameSnippetGroup(it, oldName, newName) }
        snippets = store.all().map { SnippetEntry(it.canonical()) }
    }

    /** Delete group [name]: ungroups its snippets, setting group to null (items are kept). */
    fun deleteGroup(name: String) {
        store.reorder { renameSnippetGroup(it, name, null) }
        snippets = store.all().map { SnippetEntry(it.canonical()) }
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
     * [send] also receives the resolved vault secrets of the run, so the terminal's own
     * confirmation (the production guard) can mask them instead of printing the resolved line.
     * [recording] — whether the target terminal is recording, for the dialog's warning.
     * [params] seeds the dialog's prompted parameters (the snippets panel collects them before Run);
     * empty falls back to this snippet's previous run.
     */
    fun run(
        id: String,
        recording: Boolean = false,
        params: Map<String, String> = emptyMap(),
        /**
         * Whether the caller is a row that runs on one tap and shows a line or two of the command.
         * A command it cannot show whole goes through the confirmation instead — a shared snippet
         * can pad its line until the tail, the part worth hiding, is past what the row draws.
         */
        oneTap: Boolean = false,
        send: (line: String, secrets: List<String>) -> Unit,
    ) {
        // A run initiated while the variable dialog is up would silently replace (or race) the
        // request the user is looking at — first request wins until confirmed or dismissed.
        if (pendingRun != null) return
        val snippet = find(id)?.snippet ?: return
        val segments = SnippetTemplate.parse(snippet.command)
        if (segments.none { it is SnippetSegment.Variable } && (!oneTap || showsWholeCommand(snippet.command))) {
            // Strip bidi/format tricks from the literal text too (Teams-shared snippets are not
            // "user-saved text"); an intentional multi-line script passes through unchanged.
            send(stripUnsafeFormatChars(snippet.command) + "\n", emptyList())
            return
        }
        pendingRun = SnippetRunRequest(
            snippet = snippet,
            segments = segments,
            environment = environment(),
            recording = recording,
            initialParams = params.ifEmpty { lastParams[snippet.id].orEmpty() },
            sendLine = send,
        )
    }

    /** Whether a row that offers this command in one tap can show all of it. */
    private fun showsWholeCommand(command: String): Boolean {
        // Measured on what the row draws, not on what the record holds: the row spells a character
        // that draws as nothing as `<U+202E>`, eight characters for one, so a raw count says a line
        // fits while the drawn one runs off the row and takes its tail with it. Either break counts
        // — the escape reads a lone CR as a line of its own.
        val drawn = boundedVisibleText(command)
        return '\n' !in drawn && displayColumns(drawn) <= MAX_ONE_TAP_COMMAND_COLUMNS
    }

    /**
     * The dialog confirmed the previewed [line]: send it (plus the newline) to the pending run's
     * terminal, remember [params] for the snippet's next run, close the dialog. No-op without a
     * pending run (double-click after confirm). [secrets] are the resolved vault values inside
     * [line] — passed along so the production guard's dialog can mask them.
     */
    fun confirmRun(line: String, params: Map<String, String>, secrets: List<String> = emptyList()) {
        val pending = pendingRun ?: return
        pendingRun = null
        if (params.isNotEmpty()) lastParams[pending.snippet.id] = params
        pending.sendLine(line + "\n", secrets)
    }

    /** Close the variable dialog without running (Cancel/Esc/vault lock). */
    fun dismissRun() {
        pendingRun = null
    }
}

/**
 * How wide a drawn command may be, in columns, and still run from a row that shows it in one tap.
 * The budget belongs to the narrowest of the one-tap surfaces — two lines of a 320.dp palette row at
 * 10.5.sp mono — and sits below its estimated capacity rather than at it: what a mono advance ratio
 * actually buys is a font's business, and a row that ellipsizes says nothing when it does.
 *
 * This is a readability rule, not a provenance one: a Teams-shared snippet never enters the personal
 * library the palette lists, so the record is always the user's own. What the gate promises is only
 * that a line sent on one tap is a line the row could show whole.
 */
private const val MAX_ONE_TAP_COMMAND_COLUMNS = 80
