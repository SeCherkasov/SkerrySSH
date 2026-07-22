package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * View state of the snippet library — search text, active category chip and which category sections
 * are collapsed. Shared by the desktop sidebar and the mobile screen (only the layout differs), and
 * held on the app-level design state so switching to the terminal and back doesn't reset the view.
 * Deliberately not persisted: unlike host folders, snippet categories are derived from tags and
 * shift as snippets are edited, so a stored collapse set would age badly.
 */
@Stable
class SnippetLibraryState {

    var query: String by mutableStateOf("")

    /** Active category chip: [ALL_SNIPPETS_CHIP], [UNCATEGORIZED_KEY], or a tag. */
    var activeChip: String by mutableStateOf(ALL_SNIPPETS_CHIP)

    var collapsed: Set<String> by mutableStateOf(emptySet())
        private set

    fun isCollapsed(category: String): Boolean = category in collapsed

    fun toggleCollapsed(category: String) {
        collapsed = if (category in collapsed) collapsed - category else collapsed + category
    }

    /**
     * Carry view state across a tag rename ([SnippetManager.renameTag]): a collapsed section stays
     * collapsed under its new name, and an active category chip follows the rename instead of falling
     * back to "all". [newKey] is the canonical target; a merge onto an existing tag just re-points the
     * old key at it.
     */
    fun onTagRenamed(oldKey: String, newKey: String) {
        if (oldKey in collapsed) collapsed = collapsed - oldKey + newKey
        if (activeChip == oldKey) activeChip = newKey
    }

    /**
     * Snippets to show: [query] AND the active chip. A chip whose category no longer exists (its last
     * snippet was deleted or re-tagged) falls back to "all" instead of emptying the list.
     */
    fun visible(all: List<SnippetEntry>): List<SnippetEntry> =
        filterSnippets(all, activeChip = effectiveChip(all), query = query)

    /**
     * Sections to render for [all], already narrowed by [visible]. With a chip active the view *is*
     * one category, so it renders as a single section: re-grouping the filtered list would split a
     * snippet that carries several tags back out under its other tags and show it twice.
     */
    fun categories(all: List<SnippetEntry>): List<SnippetCategory> {
        val chip = effectiveChip(all)
        val shown = filterSnippets(all, activeChip = chip, query = query)
        return when {
            chip == ALL_SNIPPETS_CHIP -> groupSnippetsByCategory(shown)
            shown.isEmpty() -> emptyList()
            else -> listOf(SnippetCategory(chip, shown))
        }
    }

    /** Chips to render: `All` plus the categories present in [all] (unaffected by the search text). */
    fun chips(all: List<SnippetEntry>): List<String> = snippetCategoryChips(all)

    private fun effectiveChip(all: List<SnippetEntry>): String =
        if (activeChip in snippetCategoryChips(all)) activeChip else ALL_SNIPPETS_CHIP
}
