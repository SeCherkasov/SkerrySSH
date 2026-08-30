package app.skerry.ui.runbook

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
import app.skerry.ui.snippet.UNCATEGORIZED_KEY
import app.skerry.ui.snippet.uncategorizedSnippetsLabel
import org.jetbrains.compose.resources.stringResource

/**
 * Names the library's folders in the one collapsed set the app persists
 * ([app.skerry.ui.design.folderCollapseKey]) — a `Production` folder here and a `Production` folder
 * of snippets or hosts fold independently.
 */
const val RUNBOOK_FOLDER_SCOPE = "runbook"

/** Technical key of the "all runbooks" chip at the start of the filter row. */
const val ALL_RUNBOOKS_CHIP = "All"

/** A runbook section: group name plus its runbooks. */
@Immutable
data class RunbookCategory(val name: String, val runbooks: List<RunbookEntry>)

/** Folders the library already uses — what the editor's "Group" select offers. */
fun runbookFolders(runbooks: List<RunbookEntry>): List<String> =
    folderNames(runbooks.map { it.runbook.group })

/** Chip label for display in library (tags): localized for the two technical keys, otherwise tag label (#tag). */
@Composable
fun runbookChipLabel(chip: String): String = when (chip) {
    ALL_RUNBOOKS_CHIP -> stringResource(Res.string.lib_snippets_chip_all)
    UNGROUPED_FOLDER -> uncategorizedSnippetsLabel()
    else -> tagChipLabel(chip)
}

/** Chip label for display in palettes/drawers (group folders): localized for the two technical keys, otherwise folder label. */
@Composable
fun runbookGroupChipLabel(chip: String): String = when (chip) {
    ALL_RUNBOOKS_CHIP -> stringResource(Res.string.lib_snippets_chip_all)
    UNGROUPED_FOLDER -> uncategorizedSnippetsLabel()
    else -> folderLabel(chip)
}

/**
 * Group runbooks into sections by folder/group ([foldersOf]).
 */
fun groupRunbooksByCategory(runbooks: List<RunbookEntry>): List<RunbookCategory> {
    val folders = foldersOf(runbooks) { it.runbook.group }
    return folders.map { RunbookCategory(it.name, it.items) }
}

/**
 * Whether anything is filed in a folder at all in the runbooks.
 */
fun hasRunbookCategories(runbooks: List<RunbookEntry>): Boolean =
    hasFolders(runbooks) { it.runbook.group }

/**
 * Unique tags present in [runbooks] in alphabetical order.
 */
fun runbookTags(runbooks: List<RunbookEntry>): List<String> =
    runbooks.flatMap { it.runbook.tags }.distinct().sorted()

/**
 * Filter chips for runbook group folders.
 */
fun runbookCategoryChips(runbooks: List<RunbookEntry>): List<String> {
    val tags = runbookTags(runbooks)
    val hasUntagged = runbooks.any { it.runbook.tags.isEmpty() }
    return buildList {
        add(ALL_RUNBOOKS_CHIP)
        addAll(tags)
        if (hasUntagged) add(UNCATEGORIZED_KEY)
    }
}

/**
 * Group chips for the palette/drawer: `All`, plus the group folders in [groupRunbooksByCategory] order.
 */
fun runbookGroupChips(runbooks: List<RunbookEntry>): List<String> =
    listOf(ALL_RUNBOOKS_CHIP) + groupRunbooksByCategory(runbooks).map { it.name }

/**
 * Filter runbooks by active chip and search query.
 */
fun filterRunbooks(
    runbooks: List<RunbookEntry>,
    activeChip: String = ALL_RUNBOOKS_CHIP,
    query: String = "",
): List<RunbookEntry> {
    val q = query.trim().lowercase()
    return runbooks.filter { entry ->
        val matchesQuery = q.isEmpty() || entry.matches(q)
        val matchesChip = when (activeChip) {
            ALL_RUNBOOKS_CHIP -> true
            UNGROUPED_FOLDER -> entry.runbook.group.isNullOrBlank()
            else -> entry.runbook.group?.equals(activeChip, ignoreCase = true) == true || activeChip in entry.runbook.tags
        }
        matchesQuery && matchesChip
    }
}
