package app.skerry.ui.snippet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.skerry.ui.design.UNGROUPED_FOLDER
import app.skerry.ui.design.folderLabel
import app.skerry.ui.design.folderNames
import app.skerry.ui.design.foldersOf
import app.skerry.ui.design.hasFolders
import app.skerry.ui.design.tagChipLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_chip_all
import app.skerry.ui.generated.resources.lib_snippets_uncategorized
import org.jetbrains.compose.resources.stringResource

/** A snippet library section: group name plus its snippets (in source list order). */
@Immutable
data class SnippetCategory(val name: String, val snippets: List<SnippetEntry>)

/**
 * Technical key for the synthetic bucket holding unfiled snippets.
 */
const val UNCATEGORIZED_KEY = UNGROUPED_FOLDER

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

/** Localized "uncategorized" bucket label for display. */
@Composable
fun uncategorizedSnippetsLabel(): String = stringResource(Res.string.lib_snippets_uncategorized)

/** Chip label for display in library (tags): localized for the two technical keys, otherwise tag label (#tag). */
@Composable
fun snippetChipLabel(chip: String): String = when (chip) {
    ALL_SNIPPETS_CHIP -> stringResource(Res.string.lib_snippets_chip_all)
    UNGROUPED_FOLDER -> uncategorizedSnippetsLabel()
    else -> tagChipLabel(chip)
}

/** Chip label for display in palettes/drawers (group folders): localized for the two technical keys, otherwise folder label. */
@Composable
fun snippetGroupChipLabel(chip: String): String = when (chip) {
    ALL_SNIPPETS_CHIP -> stringResource(Res.string.lib_snippets_chip_all)
    UNGROUPED_FOLDER -> uncategorizedSnippetsLabel()
    else -> folderLabel(chip)
}

/**
 * Group snippets into library sections by folder/group ([foldersOf]). Untagged/unfiled
 * snippets land in the [UNGROUPED_FOLDER] bucket, kept last. Snippets keep source order inside a
 * section. Pure function (no Compose), shared by desktop and mobile.
 */
fun groupSnippetsByCategory(snippets: List<SnippetEntry>): List<SnippetCategory> {
    val folders = foldersOf(snippets) { it.snippet.group }
    return folders.map { SnippetCategory(it.name, it.items) }
}

/**
 * Whether anything is filed in a folder at all.
 */
fun hasCategories(snippets: List<SnippetEntry>): Boolean =
    hasFolders(snippets) { it.snippet.group }

/**
 * Unique tags present in [snippets] in alphabetical order.
 */
fun snippetTags(snippets: List<SnippetEntry>): List<String> =
    snippets.flatMap { it.snippet.tags }.distinct().sorted()

/**
 * Filter chips for the snippet library: `All`, unique tags in alphabetical order, and
 * [UNCATEGORIZED_KEY] if anything is untagged.
 */
fun snippetCategoryChips(snippets: List<SnippetEntry>): List<String> {
    val tags = snippetTags(snippets)
    val hasUntagged = snippets.any { it.snippet.tags.isEmpty() }
    return buildList {
        add(ALL_SNIPPETS_CHIP)
        addAll(tags)
        if (hasUntagged) add(UNCATEGORIZED_KEY)
    }
}

/**
 * Group chips for the palette/drawer: `All`, plus the group folders in [groupSnippetsByCategory] order.
 */
fun snippetGroupChips(snippets: List<SnippetEntry>): List<String> =
    listOf(ALL_SNIPPETS_CHIP) + groupSnippetsByCategory(snippets).map { it.name }

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
 * Narrow [snippets] by the active chip ([activeChip] = tag or group name, `All` = no filter) and [query] (AND).
 * Search is case-insensitive across label/command/tags/notes (see [SnippetEntry.matches]).
 */
fun filterSnippets(
    snippets: List<SnippetEntry>,
    activeChip: String = ALL_SNIPPETS_CHIP,
    query: String = "",
): List<SnippetEntry> = snippets.filter { entry ->
    val chipOk = when (activeChip) {
        ALL_SNIPPETS_CHIP -> true
        UNGROUPED_FOLDER -> entry.snippet.group.isNullOrBlank()
        else -> entry.snippet.group?.equals(activeChip, ignoreCase = true) == true || activeChip in entry.snippet.tags
    }
    chipOk && (query.isBlank() || entry.matches(query))
}
