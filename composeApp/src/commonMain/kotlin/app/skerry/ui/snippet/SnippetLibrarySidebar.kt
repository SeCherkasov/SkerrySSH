package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_empty
import app.skerry.ui.generated.resources.lib_snippets_library
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_starter_pack
import app.skerry.ui.generated.resources.lib_snippets_untitled
import org.jetbrains.compose.resources.stringResource

/**
 * Snippet library sidebar: search, category chips and the snippet list grouped into collapsible
 * category sections ([groupSnippetsByCategory]). With no tags anywhere the list stays flat — the
 * chip row and section headers would be pure chrome for a single "Uncategorized" bucket.
 */
@Composable
internal fun SnippetLibrarySidebar(
    all: List<SnippetEntry>,
    library: SnippetLibraryState,
    selectedId: String?,
    mono: FontFamily,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onInstallStarterPack: () -> Unit,
) {
    val chips = library.chips(all)
    val grouped = hasCategories(all)
    val categories = library.categories(all)

    Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            SnipSearchField(library.query, { library.query = it }, mono)
        }
        if (grouped) SnippetChipRow(chips, library)
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            SectionCaption(stringResource(Res.string.lib_snippets_library))
            if (categories.isEmpty()) {
                Txt(
                    if (all.isEmpty()) stringResource(Res.string.lib_snippets_empty) else stringResource(Res.string.lib_snippets_no_matches),
                    color = D.faint, size = 11.5.sp, font = mono,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp),
                )
                if (all.isEmpty()) {
                    Box(Modifier.padding(start = 10.dp, top = 10.dp)) {
                        ChipButton(
                            label = stringResource(Res.string.lib_snippets_starter_pack),
                            color = D.cyan,
                            size = 11.sp,
                            onClick = onInstallStarterPack,
                        )
                    }
                }
            } else if (grouped) {
                categories.forEach { category ->
                    SnippetCategorySection(category, library, selectedId, mono, onSelect)
                }
            } else {
                SnippetRows(categories.flatMap { it.snippets }, selectedId, mono, onSelect)
            }
        }
        HLine()
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            PrimaryButton(stringResource(Res.string.lib_snippets_new), onClick = onNew, icon = "add", modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnippetChipRow(chips: List<String>, library: SnippetLibraryState) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            val active = chip == library.activeChip
            ChipButton(
                label = snippetChipLabel(chip),
                color = if (active) D.cyan else D.dim,
                filled = active,
                size = 11.sp,
                verticalPadding = 4.dp,
                onClick = { library.activeChip = chip },
            )
        }
    }
}

@Composable
private fun SnippetCategorySection(
    category: SnippetCategory,
    library: SnippetLibraryState,
    selectedId: String?,
    mono: FontFamily,
    onSelect: (String) -> Unit,
) {
    val collapsed = library.isCollapsed(category.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { library.toggleCollapsed(category.name) }
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sym(if (collapsed) "chevron_right" else "expand_more", size = 15.sp, color = D.faint)
        Txt(
            if (category.name == UNCATEGORIZED_KEY) uncategorizedSnippetsLabel() else snippetTagLabel(category.name),
            color = D.dim, size = 11.sp, weight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Txt(category.snippets.size.toString(), color = D.faint, size = 10.5.sp, font = mono)
    }
    if (!collapsed) SnippetRows(category.snippets, selectedId, mono, onSelect)
}

@Composable
private fun SnippetRows(entries: List<SnippetEntry>, selectedId: String?, mono: FontFamily, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            // onClick keyed by id stays stable; otherwise selecting a row would recreate lambdas for
            // all rows and redraw them.
            val onClick = remember(entry.id) { { onSelect(entry.id) } }
            SnippetRow(entry = entry, selected = entry.id == selectedId, mono = mono, onClick = onClick)
        }
    }
}

@Composable
private fun SnippetRow(entry: SnippetEntry, selected: Boolean, mono: FontFamily, onClick: () -> Unit) {
    val s = entry.snippet
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("code_blocks", size = 15.sp, color = if (selected) D.cyanBright else D.dim)
            Txt(s.label.ifBlank { stringResource(Res.string.lib_snippets_untitled) }, color = if (selected) D.cyanBright else D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
        }
        Txt(s.command, color = if (selected) D.dim else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SectionCaption(text: String) {
    Txt(
        text, color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** Library search field. */
@Composable
private fun SnipSearchField(value: String, onValueChange: (String) -> Unit, mono: FontFamily) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0x08FFFFFF)).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(stringResource(Res.string.lib_snippets_search), color = D.faint, size = 12.5.sp, font = mono)
                    inner()
                }
            }
        },
    )
}
