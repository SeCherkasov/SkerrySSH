package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * View state of the snippet library — search text and the active tag chip. The library renders as
 * one flat list: a snippet carries several tags, so grouping showed it once per tag; the chip row
 * narrows the same list instead. Shared by the desktop and mobile screens (only the layout differs),
 * and held on the app-level design state so switching to the terminal and back doesn't reset it.
 */
@Stable
class SnippetLibraryState {

    var query: String by mutableStateOf("")

    /** Active tag chip: [ALL_SNIPPETS_CHIP], [UNCATEGORIZED_KEY], or a tag. */
    var activeChip: String by mutableStateOf(ALL_SNIPPETS_CHIP)

    /**
     * Keep the active chip on a renamed tag ([SnippetManager.renameTag]) instead of falling back to
     * "all". [newKey] is the canonical target; a merge onto an existing tag just re-points the old key.
     */
    fun onTagRenamed(oldKey: String, newKey: String) {
        if (activeChip == oldKey) activeChip = newKey
    }

    /**
     * Snippets to show: [query] AND the active chip. A chip whose tag no longer exists (its last
     * snippet was deleted or re-tagged) falls back to "all" instead of emptying the list.
     */
    fun visible(all: List<SnippetEntry>): List<SnippetEntry> =
        filterSnippets(all, activeChip = effectiveChip(all), query = query)

    /** Chips to render: `All` plus the tags present in [all] (unaffected by the search text). */
    fun chips(all: List<SnippetEntry>): List<String> = snippetCategoryChips(all)

    /** The chip actually in effect — [activeChip] unless its tag is gone. */
    fun effectiveChip(all: List<SnippetEntry>): String =
        if (activeChip in snippetCategoryChips(all)) activeChip else ALL_SNIPPETS_CHIP
}
