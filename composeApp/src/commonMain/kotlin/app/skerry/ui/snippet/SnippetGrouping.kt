package app.skerry.ui.snippet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_chip_all
import app.skerry.ui.generated.resources.lib_snippets_uncategorized
import org.jetbrains.compose.resources.stringResource

/** A snippet library section: category name plus its snippets (in source list order). */
@Immutable
data class SnippetCategory(val name: String, val snippets: List<SnippetEntry>)

/**
 * Technical key for the synthetic bucket holding snippets without tags. Used as the grouping key in
 * [groupSnippetsByCategory] and as a filter chip value; not localized, since that would break
 * grouping and filtering on locale change. For display, use [uncategorizedSnippetsLabel].
 */
const val UNCATEGORIZED_KEY = "Uncategorized"

/** Technical key of the "all snippets" chip at the start of the library filter row. */
const val ALL_SNIPPETS_CHIP = "All"

/** Localized "uncategorized" bucket label for display (not for grouping, see [UNCATEGORIZED_KEY]). */
@Composable
fun uncategorizedSnippetsLabel(): String = stringResource(Res.string.lib_snippets_uncategorized)

/** Chip label for display: localized for the two technical keys, `#tag` for a real category. */
@Composable
fun snippetChipLabel(chip: String): String = when (chip) {
    ALL_SNIPPETS_CHIP -> stringResource(Res.string.lib_snippets_chip_all)
    UNCATEGORIZED_KEY -> uncategorizedSnippetsLabel()
    else -> snippetTagLabel(chip)
}

/** Tag label: `#` prefix (the model value has none). Pure, no localization involved. */
fun snippetTagLabel(tag: String): String = "#$tag"

/**
 * Group snippets into library sections by tag. Unlike host folders ([app.skerry.ui.host.HostFolder]),
 * a snippet carries several tags and therefore appears in every matching section — so sections are
 * ordered alphabetically rather than by first appearance, which would be arbitrary here. Tags are
 * canonical (lowercase, see [parseSnippetTags]), so the sort is a plain string comparison. Untagged
 * snippets land in the [UNCATEGORIZED_KEY] bucket, kept last. Snippets keep source order inside a
 * section. Pure function (no Compose), shared by desktop and mobile.
 */
fun groupSnippetsByCategory(snippets: List<SnippetEntry>): List<SnippetCategory> {
    val buckets = sortedMapOf<String, MutableList<SnippetEntry>>()
    val uncategorized = mutableListOf<SnippetEntry>()
    for (entry in snippets) {
        val tags = entry.snippet.tags
        if (tags.isEmpty()) uncategorized += entry
        else for (tag in tags) buckets.getOrPut(tag) { mutableListOf() }.add(entry)
    }
    return buildList {
        buckets.forEach { (name, list) -> add(SnippetCategory(name, list)) }
        if (uncategorized.isNotEmpty()) add(SnippetCategory(UNCATEGORIZED_KEY, uncategorized))
    }
}

/**
 * Whether anything is tagged at all. With no tags the library renders as a flat list: chips and
 * section headers would be pure chrome around a single "Uncategorized" bucket.
 */
fun hasCategories(snippets: List<SnippetEntry>): Boolean = snippets.any { it.snippet.tags.isNotEmpty() }

/**
 * Filter chips: `All`, then the categories in the same order [groupSnippetsByCategory] renders them.
 * The uncategorized chip appears only when something is actually untagged.
 */
fun snippetCategoryChips(snippets: List<SnippetEntry>): List<String> =
    listOf(ALL_SNIPPETS_CHIP) + groupSnippetsByCategory(snippets).map { it.name }

/**
 * Narrow [snippets] by the active chip ([activeChip] = category, `All` = no filter) and [query] (AND).
 * Search is case-insensitive across label/command/tags (see [SnippetEntry.matches]).
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
