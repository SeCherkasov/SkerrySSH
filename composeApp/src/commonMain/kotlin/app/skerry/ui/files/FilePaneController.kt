package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Listing state of a single file pane (local or remote). */
sealed interface FilePaneState {
    /** Listing is loading. */
    data object Loading : FilePaneState

    /** Listing loaded; [entries] sorted directories-first, then by name. */
    data class Loaded(val entries: List<FileItem>) : FilePaneState

    /** Last operation/load failed; [failure] is the typed reason, localized by the UI. */
    data class Error(val failure: FileBrowserFailure) : FilePaneState
}

/**
 * Controller for a single file pane over [FileBrowser], shared by local FS and remote SFTP panes.
 * Handles navigation, directory operations (create/delete/rename), and row multi-selection
 * (single/Ctrl-toggle/Shift-range). Cross-pane transfer is not its responsibility (see the
 * screen's transfer coordinator).
 *
 * Operations are serialized via [busy]; a failed operation moves the pane to [FilePaneState.Error]
 * without crashing the controller. Ownership of the source ([FileBrowser]/channel) is external.
 */
@Stable
class FilePaneController(
    private val browser: FileBrowser,
    private val scope: CoroutineScope,
) {
    /** Source label for headers/breadcrumbs; blank for the local FS — the UI substitutes a localized name. */
    val label: String get() = browser.label

    var path: String by mutableStateOf("/")
        private set

    var state: FilePaneState by mutableStateOf(FilePaneState.Loading)
        private set

    /** Paths of selected rows, for batch operations and highlighting. */
    var selection: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * Path of the row under the keyboard cursor (mc-style navigation), separate from [selection]:
     * the cursor is always present when the directory is non-empty, selection may be empty. Arrow
     * keys move the cursor, Insert/Space toggle the cursored row, Enter opens the cursored directory.
     */
    var cursor: String? by mutableStateOf(null)
        private set

    /** Cursor is on the synthetic ".." row (parent). Mutually exclusive with [cursor]. */
    var cursorOnParent: Boolean by mutableStateOf(false)
        private set

    /** Whether the ".." row is available (not at root). */
    val hasParent: Boolean get() = path != "/"

    /** Whether dotfiles are shown. Read-only externally; change via [setShowHidden]. */
    private var hiddenShown: Boolean by mutableStateOf(true)
    val showHidden: Boolean get() = hiddenShown

    /**
     * Quick name filter over the current listing ([matchesNameFilter]: substring or `*`/`?` glob).
     * Read-only externally; change via [setNameFilter]. Cleared on navigation — the filter narrows
     * the directory being viewed, it is not a persistent mode. Backing field is separate (like
     * [hiddenShown]) to avoid a JVM signature clash between the property setter and [setNameFilter].
     */
    private var filterText: String by mutableStateOf("")
    val nameFilter: String get() = filterText

    /**
     * Full sorted listing of the current directory before the visibility filter, so [showHidden]
     * can toggle without re-querying the source.
     */
    private var rawEntries: List<FileItem> = emptyList()

    /**
     * Shift-range anchor: path of the last singly-selected/toggled row. Snapshot state, so reads
     * in [selectTo] stay consistent with [selection] writes in the same transaction.
     */
    private var anchor: String? by mutableStateOf(null)
    private var busy = false

    /** Loads the source's starting directory. Call once when the pane opens. */
    fun start() = op {
        path = browser.realpath(".")
        reload()
    }

    /** Opens directory [item]; no-op for files. Resets selection. */
    fun open(item: FileItem) {
        if (item.type != FileItemType.Directory) return
        op { navigateTo(item.path) }
    }

    /** Navigates to the parent directory. Resets selection. */
    fun goUp() = op { navigateTo(parentPath(path)) }

    /**
     * Jumps to a user-typed [target] (path-bar Enter): an absolute path as-is, a relative one
     * resolved against the current [path]; the source's realpath canonicalizes it (normalizing
     * "."/".."/trailing slashes, and on SFTP the server's start dir). Blank input is a no-op. A bad
     * path surfaces as [FilePaneState.Error] while the pane stays in the current directory (so a typo
     * doesn't throw away the listing you were browsing).
     */
    fun goToPath(target: String) = op {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return@op
        val request = if (isAbsolutePathInput(trimmed)) trimmed else childPath(path, trimmed)
        // Dot segments are collapsed lexically before the round-trip for the same reason
        // [parentPath] is lexical: some SFTP servers reject REALPATH on paths containing "..".
        val resolved = browser.realpath(collapseDotSegments(request))
        when (val next = loadState(resolved)) {
            is FilePaneState.Loaded -> commit(resolved, next)
            else -> state = next
        }
    }

    /** Reloads the current directory. */
    fun refresh() = op { reload() }

    /** Creates subdirectory [name] in the current directory and moves the cursor to it. */
    fun mkdir(name: String) = op {
        val created = childPath(name)
        browser.mkdir(created)
        reload()
        // An active name filter can hide the just-created directory — creating something the user
        // can't see reads as a silent failure, so drop the filter to reveal it.
        if (nameFilter.isNotEmpty() && loadedEntries().none { it.path == created }) setNameFilter("")
        loadedEntries().firstOrNull { it.path == created }?.let { setCursor(it) }
    }

    /** Deletes [item] (directories recursively). */
    fun delete(item: FileItem) = op {
        browser.delete(item)
        reload()
    }

    /**
     * Targets of batch operations (F5/F6/F8): selected rows if non-empty, otherwise the cursored
     * row. Empty on ".."/an empty directory with no selection. Order follows the listing
     * (via [selectedItems]).
     */
    fun operands(): List<FileItem> =
        selectedItems().ifEmpty { cursoredItem()?.let { listOf(it) } ?: emptyList() }

    /**
     * Deletes all [operands] (F8): selected rows, or the cursored one. Each is removed
     * recursively. One [reload] afterward; no-op if [operands] is empty. Confirmation is the
     * UI's responsibility.
     */
    fun deleteSelected() = op {
        val targets = operands()
        if (targets.isEmpty()) return@op
        targets.forEach { item ->
            // Deletes via a path rebuilt from [path] + a validated name, not server-controlled item.path.
            val name = item.name
            if (isUnsafeListingName(name)) {
                throw FileBrowserException(FileBrowserFailure.IllegalName, detail = name)
            }
            browser.delete(item.copy(path = childPath(name)))
        }
        reload()
    }

    /** Renames [item] to [newName] within the current directory. */
    fun rename(item: FileItem, newName: String) = op {
        browser.rename(item.path, childPath(newName))
        reload()
    }

    /** Selects only [item] (plain click), setting it as the Shift-range anchor. */
    fun selectOnly(item: FileItem) {
        selection = setOf(item.path)
        anchor = item.path
    }

    /** Ctrl-click: toggles [item] in the selection, setting it as the anchor. */
    fun toggle(item: FileItem) {
        selection = if (item.path in selection) selection - item.path else selection + item.path
        anchor = item.path
    }

    /**
     * Shift-click: selects the range from the anchor to [item] in display order; falls back to
     * [selectOnly] with no anchor (or if it left the listing). The anchor is preserved so a
     * further Shift-click re-ranges from the same point. The range replaces the selection rather
     * than adding to a prior Ctrl-selection.
     */
    fun selectTo(item: FileItem) {
        val a = anchor
        val entries = (state as? FilePaneState.Loaded)?.entries
        if (a == null || entries == null) {
            selectOnly(item)
            return
        }
        val from = entries.indexOfFirst { it.path == a }
        val to = entries.indexOfFirst { it.path == item.path }
        if (from < 0 || to < 0) {
            selectOnly(item)
            return
        }
        val range = if (from <= to) from..to else to..from
        selection = entries.slice(range).mapTo(mutableSetOf()) { it.path }
    }

    /**
     * Rubber-band selection: marks/unmarks the row range from [anchor] to [current] in display
     * order. [select] fixes the sign for the whole drag. Unlike [selectTo] (which replaces the
     * selection), this adds/removes on top of it. The Shift-range anchor moves to [anchor]. No-op
     * outside [FilePaneState.Loaded] or if either end is missing.
     */
    fun rubberBandTo(anchor: FileItem, current: FileItem, select: Boolean) {
        val entries = (state as? FilePaneState.Loaded)?.entries ?: return
        val from = entries.indexOfFirst { it.path == anchor.path }
        val to = entries.indexOfFirst { it.path == current.path }
        if (from < 0 || to < 0) return
        val range = if (from <= to) from..to else to..from
        val touched = entries.slice(range).mapTo(mutableSetOf()) { it.path }
        selection = if (select) selection + touched else selection - touched
        this.anchor = anchor.path
    }

    /** Clears the selection. */
    fun clearSelection() = resetSelection()

    /**
     * Toggles dotfile visibility by refiltering the cached [rawEntries], without a source query.
     * Entries that leave view are pruned from selection and the cursor.
     */
    fun setShowHidden(value: Boolean) {
        if (hiddenShown == value) return
        hiddenShown = value
        if (state is FilePaneState.Loaded) {
            state = FilePaneState.Loaded(visible(rawEntries))
            pruneSelection()
            clampCursor()
        }
    }

    /**
     * Applies name filter [value] by refiltering the cached [rawEntries], without a source query
     * (same mechanism as [setShowHidden]). Entries that leave view are pruned from selection and
     * the cursor.
     */
    fun setNameFilter(value: String) {
        if (filterText == value) return
        filterText = value
        if (state is FilePaneState.Loaded) {
            state = FilePaneState.Loaded(visible(rawEntries))
            pruneSelection()
            clampCursor()
        }
    }

    /** Moves the cursor by [delta] rows, clamped at the edges. Navigation space includes "..". */
    fun moveCursor(delta: Int) {
        if (combinedCount() == 0) {
            cursor = null
            cursorOnParent = false
            return
        }
        val current = cursorCombinedIndex().let { if (it < 0) 0 else it }
        setCombined(current + delta)
    }

    /** Moves the cursor to the first row (Home): ".." if not at root. */
    fun cursorToFirst() = setCombined(0)

    /** Moves the cursor to the last row (End). */
    fun cursorToLast() = setCombined(combinedCount() - 1)

    /** Sets the cursor to [item] explicitly. */
    fun setCursor(item: FileItem) {
        cursor = item.path
        cursorOnParent = false
    }

    /** Sets the cursor on the ".." row, if available. */
    fun setCursorOnParent() {
        if (hasParent) {
            cursorOnParent = true
            cursor = null
        }
    }

    /** The cursored item, or null (cursor on ".."/empty directory/missing). */
    fun cursoredItem(): FileItem? =
        if (cursorOnParent) null else loadedEntries().firstOrNull { it.path == cursor }

    /** Enter: ".." navigates up; a directory opens; a file is a no-op. */
    fun enterCursored() {
        if (cursorOnParent) goUp() else cursoredItem()?.let(::open)
    }

    /** Space: toggles the cursored row without moving the cursor. ".." is never toggled. */
    fun markCursored() {
        if (cursorOnParent) return
        cursoredItem()?.let(::toggle)
    }

    /** Insert: toggles the cursored row and advances the cursor. On ".." only advances. */
    fun markCursoredAndAdvance() {
        if (cursorOnParent) {
            moveCursor(1)
            return
        }
        val item = cursoredItem() ?: return
        toggle(item)
        moveCursor(1)
    }

    /** Selected items of the current listing, in display order. */
    fun selectedItems(): List<FileItem> =
        (state as? FilePaneState.Loaded)?.entries?.filter { it.path in selection } ?: emptyList()

    /**
     * Names of ALL entries in the current directory (from the unfiltered [rawEntries], not the
     * visible listing), for overwrite/name-conflict checks before transfer/mkdir/rename — an entry
     * hidden by [showHidden] or [nameFilter] still occupies its name on the source.
     */
    fun currentEntryNames(): Set<String> =
        if (state is FilePaneState.Loaded) rawEntries.mapTo(mutableSetOf()) { it.name } else emptySet()

    /**
     * Navigates to [target] atomically: loads its listing first, then updates
     * path+state+selection in one snapshot, so the old listing is never shown under the new path.
     * Until the listing arrives the pane shows the previous directory unchanged; a listing error
     * is shown under the new path.
     */
    private suspend fun navigateTo(target: String) = commit(target, loadState(target))

    /**
     * Commits a freshly loaded [next] listing under [target] in one snapshot: path+state+selection,
     * cursor on the first real entry (not ".."), or on ".." for an empty directory. On an [Error]
     * [next] the pane still moves to [target] and shows the error (used by open/goUp, whose targets
     * come from the listing and rarely fail); goToPath commits only successful listings.
     */
    private fun commit(target: String, next: FilePaneState) {
        path = target
        // The filter narrows one directory's view; a new directory starts unfiltered. [next] was
        // built while the old filter was still active, so a successful listing is re-derived from
        // the fresh [rawEntries]. A failed navigation keeps the filter: it still describes the
        // last listing that was on screen, and going back up restores that view.
        state = if (next is FilePaneState.Loaded) {
            filterText = ""
            FilePaneState.Loaded(visible(rawEntries))
        } else {
            next
        }
        resetSelection() // New directory: selection is empty already, nothing to prune.
        cursor = (next as? FilePaneState.Loaded)?.entries?.firstOrNull()?.path
        cursorOnParent = cursor == null && hasParent
    }

    /** Reloads the current [path] in place (refresh/after mkdir/rename/delete). */
    private suspend fun reload() {
        state = loadState(path)
        // Selection is kept on Error; only pruned after a successful listing.
        pruneSelection()
        clampCursor()
    }

    /** Entries of the loaded directory, or empty (Loading/Error/empty directory). */
    private fun loadedEntries(): List<FileItem> = (state as? FilePaneState.Loaded)?.entries ?: emptyList()

    /** Offset of entries in the combined navigation space: 0 at root, 1 when ".." is present. */
    private fun parentOffset(): Int = if (hasParent) 1 else 0

    /** Size of the navigation space: entries plus the ".." row, if available. */
    private fun combinedCount(): Int = loadedEntries().size + parentOffset()

    /** Cursor index in the combined [.., entries...] space, or -1. */
    private fun cursorCombinedIndex(): Int {
        if (cursorOnParent && hasParent) return 0
        val c = cursor ?: return -1
        val idx = loadedEntries().indexOfFirst { it.path == c }
        return if (idx < 0) -1 else idx + parentOffset()
    }

    /** Sets the cursor by index in the combined navigation space, clamped at the edges. */
    private fun setCombined(index: Int) {
        val total = combinedCount()
        if (total == 0) {
            cursor = null
            cursorOnParent = false
            return
        }
        val i = index.coerceIn(0, total - 1)
        if (hasParent && i == 0) {
            cursorOnParent = true
            cursor = null
        } else {
            cursorOnParent = false
            cursor = loadedEntries()[i - parentOffset()].path
        }
    }

    /** After a reload, moves the cursor to the first row if it disappeared. */
    private fun clampCursor() {
        if (cursorOnParent) {
            if (!hasParent) setCombined(0) // Reached root: ".." disappeared, move to the first row.
            return
        }
        val entries = loadedEntries()
        if (entries.isEmpty()) {
            // Directory emptied: move the cursor to ".." if a parent exists.
            cursor = null
            cursorOnParent = hasParent
            return
        }
        if (cursor == null || entries.none { it.path == cursor }) cursor = entries.first().path
    }

    /**
     * Loads and sorts the listing of [target] into [FilePaneState]. The full listing is cached in
     * [rawEntries]; the visibility-filtered subset is returned. Does not touch
     * path/cursor/selection.
     */
    private suspend fun loadState(target: String): FilePaneState =
        try {
            val raw = browser.list(target).sortedForPane()
            rawEntries = raw
            FilePaneState.Loaded(visible(raw))
        } catch (e: FileBrowserException) {
            FilePaneState.Error(e.failure)
        }

    /** Filters the listing by [showHidden] (hidden entries start with a dot) and [nameFilter]. */
    private fun visible(entries: List<FileItem>): List<FileItem> =
        entries
            .let { if (showHidden) it else it.filterNot { e -> e.name.startsWith(".") } }
            .let { if (nameFilter.isBlank()) it else it.filter { e -> matchesNameFilter(e.name, nameFilter) } }

    /** Removes paths no longer present in the current listing from selection/anchor. */
    private fun pruneSelection() {
        val present = (state as? FilePaneState.Loaded)?.entries?.mapTo(mutableSetOf()) { it.path } ?: return
        if (!present.containsAll(selection)) selection = selection.intersect(present)
        if (anchor != null && anchor !in present) anchor = null
    }

    private fun resetSelection() {
        selection = emptySet()
        anchor = null
    }

    /**
     * Runs a pane operation, serialized via [busy]. Any [FileBrowserException] from the operation
     * moves the pane to [FilePaneState.Error].
     *
     * [busy] is a plain (non-`@Volatile`) flag: relies on [scope] being single-threaded/
     * main-confined. A multi-threaded dispatcher would race on [busy].
     */
    private fun op(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileBrowserException) {
                state = FilePaneState.Error(e.failure)
            } finally {
                busy = false
            }
        }
    }

    /** Path of child [name] in the current directory. */
    private fun childPath(name: String): String = childPath(path, name)

    /**
     * Lexical parent of an absolute path for the "up" action. Does not resolve `"$path/.."` via
     * [FileBrowser.realpath], since some SFTP servers reject REALPATH on paths containing `..`.
     * Root and paths without a separator resolve to `/`.
     */
    private fun parentPath(path: String): String {
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }
}

/**
 * Whether path-bar input is absolute: "/" (POSIX/SFTP), a Windows drive letter ("C:\", "C:/"), or
 * a UNC share ("\\server\...") — the desktop local pane on Windows works with such paths, and
 * resolving them against the current directory would produce garbage like "C:\Users\me/C:\Temp".
 */
private fun isAbsolutePathInput(input: String): Boolean =
    input.startsWith("/") ||
        input.startsWith("\\\\") ||
        (input.length >= 2 && input[0].isLetter() && input[1] == ':')

/**
 * Lexically collapses "."/".." segments of a '/'-separated absolute path ("/a/b/.." → "/a"), so
 * [FileBrowser.realpath] never sees "..". Windows-style paths pass through untouched (the local
 * browser resolves them itself). ".." above root stops at root.
 */
private fun collapseDotSegments(path: String): String {
    if (!path.startsWith("/") || !path.contains('.')) return path
    val out = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(segment)
        }
    }
    return "/" + out.joinToString("/")
}

/** Sorts directories first, then by case-insensitive name. */
private fun List<FileItem>.sortedForPane(): List<FileItem> =
    sortedWith(
        compareBy(
            { if (it.type == FileItemType.Directory) 0 else 1 },
            { it.name.lowercase() },
        ),
    )
