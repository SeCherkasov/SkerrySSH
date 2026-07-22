package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_edit
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetLibraryState
import app.skerry.ui.snippet.UNCATEGORIZED_KEY
import app.skerry.ui.snippet.hasCategories
import app.skerry.ui.snippet.snippetChipLabel
import app.skerry.ui.snippet.snippetTagLabel
import app.skerry.ui.snippet.uncategorizedSnippetsLabel
import org.jetbrains.compose.resources.stringResource

/**
 * Snippet library body on mobile: search field, category chips and the cards grouped into
 * collapsible sections. Mirrors the desktop sidebar ([app.skerry.ui.snippet.SnippetLibrarySidebar])
 * — same [SnippetLibraryState], only the layout differs. With nothing tagged the list stays flat.
 */
@Composable
internal fun MobileSnippetLibrary(
    all: List<SnippetEntry>,
    library: SnippetLibraryState,
    mono: FontFamily,
    onEdit: (SnippetEntry) -> Unit,
    onRenameCategory: (String) -> Unit,
) {
    val grouped = hasCategories(all)
    val categories = library.categories(all)

    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        MobileFormInput(library.query, { library.query = it }, stringResource(Res.string.lib_snippets_search))
    }
    if (grouped) {
        MobileSnippetChips(library.chips(all), library.activeChip) { library.activeChip = it }
    }
    if (categories.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp)) {
            Txt(stringResource(Res.string.lib_snippets_no_matches), color = D.faint, size = 13.sp)
        }
        return
    }
    categories.forEach { category ->
        key(category.name) {
            if (grouped) {
                MobileSnippetSectionHeader(
                    name = if (category.name == UNCATEGORIZED_KEY) uncategorizedSnippetsLabel() else snippetTagLabel(category.name),
                    count = category.snippets.size,
                    collapsed = library.isCollapsed(category.name),
                    onToggle = { library.toggleCollapsed(category.name) },
                    // The synthetic "uncategorized" bucket is not a real tag, so it carries no rename
                    // pencil — mirrors the desktop sidebar (SnippetCategorySection).
                    onRename = if (category.name != UNCATEGORIZED_KEY) ({ onRenameCategory(category.name) }) else null,
                )
            }
            if (!grouped || !library.isCollapsed(category.name)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    category.snippets.forEach { entry ->
                        key(entry.id) {
                            val onClick = remember(entry.id) { { onEdit(entry) } }
                            MobileSnippetCard(entry.snippet, mono, onClick)
                        }
                    }
                }
            }
        }
    }
}

/** Category chip row: "All" + `#tag` + "Uncategorized"; active chip highlighted, horizontally scrollable. */
@Composable
private fun MobileSnippetChips(chips: List<String>, active: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        chips.forEach { chip ->
            key(chip) {
                val on = chip == active
                val onClick = remember(chip) { { onSelect(chip) } }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) D.cyan.copy(alpha = 0.14f) else Color(0x0DFFFFFF))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                        .padding(horizontal = 13.dp, vertical = 5.dp),
                ) {
                    Txt(
                        snippetChipLabel(chip),
                        color = if (on) D.cyanBright else D.dim,
                        size = 12.5.sp,
                        weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Category section header: collapse chevron + uppercase name + optional rename pencil + snippet count. */
@Composable
private fun MobileSnippetSectionHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRename: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = D.faint)
        }
        Txt(name.uppercase(), color = D.faint, size = 12.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
        if (onRename != null) {
            IconBtn("edit", onClick = onRename, box = 30, icon = 16.sp, tint = D.faint, tooltip = stringResource(Res.string.lib_snippets_edit))
        }
        Txt(count.toString(), color = D.faint, size = 11.5.sp)
    }
}
