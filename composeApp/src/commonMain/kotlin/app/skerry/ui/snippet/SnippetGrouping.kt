package app.skerry.ui.snippet

import androidx.compose.runtime.Composable
import app.skerry.ui.design.Folder
import app.skerry.ui.design.folderNames
import app.skerry.ui.design.foldersOf
import app.skerry.ui.design.tagChipLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_chip_all
import app.skerry.ui.generated.resources.lib_snippets_uncategorized
import org.jetbrains.compose.resources.stringResource

/**
 * The library has two ways of cutting the same list, and they are not the same cut. A snippet sits
 * in exactly one *folder* ([Snippet.group]) — that is what the sections, the drag-and-drop order and
 * the palettes are built on ([snippetFolders]). It also carries any number of *tags*, which is what
 * the library's filter row narrows by ([snippetCategoryChips]). Keeping one key for both would make
 * "Uncategorized" mean "no tags" in one place and "no folder" in the other.
 */

/**
 * Technical key for the synthetic bucket holding snippets without tags. Used as a filter chip value;
 * not localized, since that would break filtering on locale change. For display, use
 * [uncategorizedSnippetsLabel]. The folders' own bucket is [app.skerry.ui.design.UNGROUPED_FOLDER].
 */
const val UNCATEGORIZED_KEY = "Uncategorized"

/** Technical key of the "all snippets" chip at the start of the library filter row. */
const val ALL_SNIPPETS_CHIP = "All"

/**
 * Names the library's folders in the one collapsed set the app persists
 * ([app.skerry.ui.design.folderCollapseKey]) — a `Production` folder here and a `Production` folder
 * of hosts fold independently.
 */
const val SNIPPET_FOLDER_SCOPE = "snippet"

/** Folders the library already uses — what the editor's "Group" select offers. */
fun snippetFolders(snippets: List<SnippetEntry>): List<String> =
    folderNames(snippets.map { it.snippet.group })

/**
 * The library's folder sections, in the order the user dragged them into
 * ([foldersOf] with `ordered`) — the same sections the library list draws, so a palette shows a
 * command where its owner expects it.
 */
fun snippetFolderSections(snippets: List<SnippetEntry>): List<Folder<SnippetEntry>> =
    foldersOf(snippets, ordered = true) { it.snippet.group }

/** Localized "uncategorized" bucket label for display (not for filtering, see [UNCATEGORIZED_KEY]). */
@Composable
fun uncategorizedSnippetsLabel(): String = stringResource(Res.string.lib_snippets_uncategorized)

/** Chip label for display: localized for the two technical keys, `#tag` for a real tag. */
@Composable
fun snippetChipLabel(chip: String): String = when (chip) {
    ALL_SNIPPETS_CHIP -> stringResource(Res.string.lib_snippets_chip_all)
    UNCATEGORIZED_KEY -> uncategorizedSnippetsLabel()
    else -> tagChipLabel(chip)
}

/**
 * Whether anything is tagged at all. With no tags the library's filter row is pure chrome around a
 * single "Uncategorized" chip.
 */
fun hasCategories(snippets: List<SnippetEntry>): Boolean = snippets.any { it.snippet.tags.isNotEmpty() }

/** Unique tags present in [snippets], alphabetically. Tags are canonical, so a plain sort will do. */
fun snippetTags(snippets: List<SnippetEntry>): List<String> =
    snippets.flatMap { it.snippet.tags }.distinct().sorted()

/**
 * Filter chips: `All`, then every tag in use. The uncategorized chip appears only when something is
 * actually untagged.
 */
fun snippetCategoryChips(snippets: List<SnippetEntry>): List<String> = buildList {
    add(ALL_SNIPPETS_CHIP)
    addAll(snippetTags(snippets))
    if (snippets.any { it.snippet.tags.isEmpty() }) add(UNCATEGORIZED_KEY)
}

/** Case-insensitive search across a snippet's name, command, folder, tags and notes. */
fun SnippetEntry.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    return snippet.label.lowercase().contains(q) ||
        snippet.command.lowercase().contains(q) ||
        snippet.notes?.lowercase()?.contains(q) == true ||
        snippet.group?.lowercase()?.contains(q) == true ||
        snippet.tags.any { it.lowercase().contains(q) }
}

/**
 * Narrow [snippets] by the active chip ([activeChip] = tag, `All` = no filter) and [query] (AND).
 * Search is case-insensitive across label/command/folder/tags/notes (see [SnippetEntry.matches]).
 */
fun filterSnippets(
    snippets: List<SnippetEntry>,
    activeChip: String = ALL_SNIPPETS_CHIP,
    query: String = "",
): List<SnippetEntry> = snippets.filter { entry ->
    val chipOk = when (activeChip) {
        ALL_SNIPPETS_CHIP -> true
        UNCATEGORIZED_KEY -> entry.snippet.tags.isEmpty()
        else -> activeChip in entry.snippet.tags
    }
    chipOk && (query.isBlank() || entry.matches(query))
}
