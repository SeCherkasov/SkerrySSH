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
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_collapse_all
import app.skerry.ui.generated.resources.lib_expand_all
import app.skerry.ui.generated.resources.runbook_empty_mobile
import app.skerry.ui.generated.resources.runbook_no_matches
import app.skerry.ui.generated.resources.runbook_none_runnable
import app.skerry.ui.generated.resources.runbook_palette_placeholder
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.runbook.ALL_RUNBOOKS_CHIP
import app.skerry.ui.runbook.RunbookEntry
import app.skerry.ui.runbook.RunbookManager
import app.skerry.ui.runbook.filterRunbooks
import app.skerry.ui.runbook.groupRunbooksByCategory
import app.skerry.ui.runbook.hasRunbookCategories
import app.skerry.ui.runbook.runbookGroupChipLabel
import app.skerry.ui.runbook.runbookGroupChips
import app.skerry.ui.snippet.SnippetCategoryHeader
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Runbook-run picker opened from the terminal header / menu: list of saved procedures, tap
 * starts the selected runbook in the active session via [onRun].
 */
@Composable
internal fun MobileRunbookRunSheet(
    manager: RunbookManager,
    onRun: (RunbookEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val saved = manager.runbooks
    val all = remember(saved) { saved.filter { it.runbook.steps.isNotEmpty() } }
    var activeChip by remember { mutableStateOf(ALL_RUNBOOKS_CHIP) }
    val filtered = remember(all, activeChip, query) {
        filterRunbooks(all, activeChip = activeChip, query = query)
    }

    val allCategoryNames = remember(all) { groupRunbooksByCategory(all).map { it.name } }
    var collapsedCategories by remember(all) {
        mutableStateOf(allCategoryNames.toSet())
    }
    val allCollapsed = allCategoryNames.isNotEmpty() && allCategoryNames.all { it in collapsedCategories }

    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.75f) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(stringResource(Res.string.runbook_section), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold)
            MobileFormInput(query, { query = it }, stringResource(Res.string.runbook_palette_placeholder))
            if (hasRunbookCategories(all)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChipRow(
                        chips = remember(all) { runbookGroupChips(all) },
                        activeChip = activeChip,
                        onSelect = { activeChip = it },
                        modifier = Modifier.weight(1f),
                        label = { runbookGroupChipLabel(it) },
                    )
                    if (activeChip == ALL_RUNBOOKS_CHIP && allCategoryNames.isNotEmpty()) {
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
                val emptyMessage = when {
                    saved.isEmpty() -> stringResource(Res.string.runbook_empty_mobile)
                    all.isEmpty() -> stringResource(Res.string.runbook_none_runnable)
                    else -> stringResource(Res.string.runbook_no_matches)
                }
                Txt(emptyMessage, color = Skerry.colors.faint, size = 13.sp)
            } else if (hasRunbookCategories(filtered) && activeChip == ALL_RUNBOOKS_CHIP) {
                groupRunbooksByCategory(filtered).forEach { category ->
                    val isCollapsed = if (query.isNotBlank()) false else category.name in collapsedCategories
                    key(category.name) {
                        SnippetCategoryHeader(
                            category = category.name,
                            count = category.runbooks.size,
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
                            category.runbooks.forEach { entry ->
                                key(entry.id) {
                                    val onClick = remember(entry.id) { { onRun(entry) } }
                                    RunbookCard(entry, mono, onClick)
                                }
                            }
                        }
                    }
                }
            } else {
                filtered.forEach { entry ->
                    key(entry.id) {
                        val onClick = remember(entry.id) { { onRun(entry) } }
                        RunbookCard(entry, mono, onClick)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
