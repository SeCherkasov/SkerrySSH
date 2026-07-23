package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.FilterChipRow
import app.skerry.ui.design.SidebarSearchField
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_edit
import app.skerry.ui.generated.resources.lib_snippets_empty
import app.skerry.ui.generated.resources.lib_snippets_library
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_placeholder
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_subtitle
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_title
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_starter_pack
import app.skerry.ui.generated.resources.lib_snippets_untitled
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_save
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.key
import app.skerry.ui.theme.Skerry

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
    onRenameTag: (oldTag: String, newTag: String) -> Unit = { _, _ -> },
) {
    val chips = library.chips(all)
    val grouped = hasCategories(all)
    val categories = library.categories(all)
    // Tag being renamed via the category pencil (null = dialog closed).
    var renamingTag by remember { mutableStateOf<String?>(null) }

    Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            SnipSearchField(library.query, { library.query = it })
        }
        if (grouped) SnippetChipRow(chips, library)
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            SidebarSectionTitle(
                stringResource(Res.string.lib_snippets_library),
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
            )
            if (categories.isEmpty()) {
                Txt(
                    if (all.isEmpty()) stringResource(Res.string.lib_snippets_empty) else stringResource(Res.string.lib_snippets_no_matches),
                    color = Skerry.colors.faint, size = 11.5.sp, font = mono,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp),
                )
                if (all.isEmpty()) {
                    Box(Modifier.padding(start = 10.dp, top = 10.dp)) {
                        ChipButton(
                            label = stringResource(Res.string.lib_snippets_starter_pack),
                            color = Skerry.colors.cyan,
                            size = 11.sp,
                            onClick = onInstallStarterPack,
                        )
                    }
                }
            } else if (grouped) {
                categories.forEach { category ->
                    SnippetCategorySection(category, library, selectedId, mono, onSelect, onEditCategory = { renamingTag = it })
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

    renamingTag?.let { tag ->
        RenameTagDialog(
            initialName = tag,
            onDismiss = { renamingTag = null },
            onSave = { newTag -> onRenameTag(tag, newTag); renamingTag = null },
        )
    }
}

/**
 * Rename a snippet tag (which doubles as a library category). Mirrors the host group rename dialog
 * ([app.skerry.ui.host.GroupDialog]) — scrim + card, prefilled name field, save disabled while blank.
 */
@Composable
private fun RenameTagDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canSave = name.trim().isNotEmpty()
    val save = { if (canSave) onSave(name) }
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(
                stringResource(Res.string.lib_snippets_rename_tag_title),
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            )
            Txt(
                stringResource(Res.string.lib_snippets_rename_tag_subtitle),
                color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = LocalFonts.current.ui),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(Skerry.colors.card)
                            .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp))
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            if (name.isEmpty()) Txt(stringResource(Res.string.lib_snippets_rename_tag_placeholder), color = Skerry.colors.faint, size = 13.sp)
                            inner()
                        }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.shell_cancel), color = Skerry.colors.dim, size = 12.5.sp)
                }
                PrimaryButton(stringResource(Res.string.shell_save), onClick = save, enabled = canSave)
            }
        }
    }
}

@Composable
private fun SnippetChipRow(chips: List<String>, library: SnippetLibraryState) {
    // Same scrollable single-row filter strip as the hosts sidebar, so the two read as one system.
    FilterChipRow(
        chips = chips,
        activeChip = library.activeChip,
        onSelect = { library.activeChip = it },
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        label = { snippetChipLabel(it) },
    )
}

@Composable
private fun SnippetCategorySection(
    category: SnippetCategory,
    library: SnippetLibraryState,
    selectedId: String?,
    mono: FontFamily,
    onSelect: (String) -> Unit,
    onEditCategory: (String) -> Unit,
) {
    val collapsed = library.isCollapsed(category.name)
    // The synthetic "uncategorized" bucket is not a real tag, so it carries no rename pencil.
    val editable = category.name != UNCATEGORIZED_KEY
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { library.toggleCollapsed(category.name) }
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sym(if (collapsed) "chevron_right" else "expand_more", size = 15.sp, color = Skerry.colors.faint)
        Txt(
            if (editable) snippetTagLabel(category.name) else uncategorizedSnippetsLabel(),
            color = Skerry.colors.dim, size = 11.sp, weight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            IconBtn(
                "edit",
                onClick = { onEditCategory(category.name) },
                box = 20,
                icon = 13.sp,
                tint = Skerry.colors.faint,
                tooltip = stringResource(Res.string.lib_snippets_edit),
            )
        }
        Txt(category.snippets.size.toString(), color = Skerry.colors.faint, size = 10.5.sp, font = mono)
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
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) Skerry.colors.cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("code_blocks", size = 15.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
            Txt(s.label.ifBlank { stringResource(Res.string.lib_snippets_untitled) }, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.textBright, size = 12.5.sp, weight = FontWeight.Medium)
        }
        Txt(s.command, color = if (selected) Skerry.colors.dim else Skerry.colors.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Library search field — the shared sidebar field, identical to the hosts sidebar. */
@Composable
private fun SnipSearchField(value: String, onValueChange: (String) -> Unit) {
    SidebarSearchField(value, onValueChange, stringResource(Res.string.lib_snippets_search), Modifier.fillMaxWidth())
}
