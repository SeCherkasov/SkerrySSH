package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.FilterChipRow
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_collapse_all
import app.skerry.ui.generated.resources.lib_expand_all
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_run_empty
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.snippet.ALL_SNIPPETS_CHIP
import app.skerry.ui.snippet.SnippetCategoryHeader
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.filterSnippets
import app.skerry.ui.snippet.groupSnippetsByCategory
import app.skerry.ui.snippet.hasCategories
import app.skerry.ui.snippet.snippetGroupChipLabel
import app.skerry.ui.snippet.snippetGroupChips
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Snippet-run picker opened from the terminal header (`bolt` icon): list of saved commands, tap
 * runs the selected snippet in the active session via [onRun].
 */
@Composable
internal fun MobileSnippetRunSheet(manager: SnippetManager, onRun: (SnippetEntry) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    var activeChip by remember { mutableStateOf(ALL_SNIPPETS_CHIP) }
    val filtered = remember(all, activeChip, query) {
        filterSnippets(all, activeChip = activeChip, query = query)
    }

    val allCategoryNames = remember(all) { groupSnippetsByCategory(all).map { it.name } }
    var collapsedCategories by remember(all) {
        mutableStateOf(allCategoryNames.toSet())
    }
    val allCollapsed = allCategoryNames.isNotEmpty() && allCategoryNames.all { it in collapsedCategories }

    // Inline sheet (like the Vault/New connection sheets), rendered at the screen's top-level Box,
    // not via Popup: a focusable Popup shifted window insets and slightly moved the terminal header.
    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.75f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(Res.string.lib_snippets_run_title), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold)
            MobileFormInput(query, { query = it }, stringResource(Res.string.lib_snippets_search))
            if (hasCategories(all)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChipRow(
                        chips = remember(all) { snippetGroupChips(all) },
                        activeChip = activeChip,
                        onSelect = { activeChip = it },
                        modifier = Modifier.weight(1f),
                        label = { snippetGroupChipLabel(it) },
                    )
                    if (activeChip == ALL_SNIPPETS_CHIP && allCategoryNames.isNotEmpty()) {
                        IconBtn(
                            if (allCollapsed) "unfold_more" else "unfold_less",
                            onClick = {
                                collapsedCategories = if (allCollapsed) emptySet() else allCategoryNames.toSet()
                            },
                            box = 24,
                            icon = 15.sp,
                            tint = Skerry.colors.dim,
                            tooltip = stringResource(if (allCollapsed) Res.string.lib_expand_all else Res.string.lib_collapse_all),
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                Txt(if (all.isEmpty()) stringResource(Res.string.lib_snippets_run_empty) else stringResource(Res.string.lib_snippets_no_matches), color = Skerry.colors.faint, size = 13.sp)
            } else if (hasCategories(filtered) && activeChip == ALL_SNIPPETS_CHIP) {
                groupSnippetsByCategory(filtered).forEach { category ->
                    val isCollapsed = if (query.isNotBlank()) false else category.name in collapsedCategories
                    key(category.name) {
                        SnippetCategoryHeader(
                            category = category.name,
                            count = category.snippets.size,
                            collapsed = isCollapsed,
                            onToggle = {
                                collapsedCategories = if (category.name in collapsedCategories) {
                                    collapsedCategories - category.name
                                } else {
                                    collapsedCategories + category.name
                                }
                            },
                        )
                        if (!isCollapsed) {
                            category.snippets.forEach { entry ->
                                key(entry.id) {
                                    val onClick = remember(entry.id) { { onRun(entry) } }
                                    MobileSnippetCard(entry.snippet, onClick)
                                }
                            }
                        }
                    }
                }
            } else {
                filtered.forEach { entry ->
                    key(entry.id) {
                        val onClick = remember(entry.id) { { onRun(entry) } }
                        MobileSnippetCard(entry.snippet, onClick)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
