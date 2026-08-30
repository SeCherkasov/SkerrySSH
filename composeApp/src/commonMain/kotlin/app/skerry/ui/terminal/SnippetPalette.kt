package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.CloseWhenUnavailable
import app.skerry.ui.design.CommandLine
import app.skerry.ui.design.FilterChipRow
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.NOTE_PEEK_LINES
import app.skerry.ui.design.NoteBlock
import app.skerry.ui.design.RowNoteTooltip
import app.skerry.ui.design.SkerryVerticalScrollbar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberModalPresence
import app.skerry.ui.design.rememberRowNote
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_collapse_all
import app.skerry.ui.generated.resources.lib_expand_all
import app.skerry.ui.generated.resources.lib_restore_layout
import app.skerry.ui.generated.resources.lib_save_default_layout
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import app.skerry.ui.generated.resources.shell_tip_snippets
import app.skerry.ui.generated.resources.term_no_matches
import app.skerry.ui.generated.resources.term_no_snippets_yet
import app.skerry.ui.generated.resources.term_run_snippet_placeholder
import app.skerry.ui.generated.resources.term_untitled
import app.skerry.ui.session.Session
import app.skerry.ui.snippet.ALL_SNIPPETS_CHIP
import app.skerry.ui.snippet.SnippetCategoryHeader
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.filterSnippets
import app.skerry.ui.snippet.groupSnippetsByCategory
import app.skerry.ui.snippet.hasCategories
import app.skerry.ui.snippet.matches
import app.skerry.ui.snippet.snippetGroupChipLabel
import app.skerry.ui.snippet.snippetGroupChips
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// Snippet palette: quickly run a saved command in the active terminal directly from the toolbar.

@Composable
internal fun SnippetPaletteButton(active: Session?, request: ToolbarRequest? = null) {
    val manager = LocalSnippets.current
    val terminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    // Keyed on active: switching tabs must not leave the palette open over a different toolbar.
    var open by remember(active) { mutableStateOf(false) }
    // Hotkey channel (⌘S / Ctrl+Shift+S). It only opens: with nothing to run into, the palette would
    // be a dead-end popup, so the key falls through to whatever else wants it.
    OnToolbarRequest(request) { if (terminal != null) open = true }
    if (manager == null) return
    val enabled = toolbarActionEnabled(ToolbarAction.Snippets, active)
    CloseWhenUnavailable(enabled) { open = false }
    Box {
        // Nowhere to run without a connected session: disabled rather than dimmed-but-live, so the
        // press is refused instead of landing on a handler that drops it.
        IconBtn("bolt", onClick = { open = !open }, enabled = enabled, tooltip = stringResource(Res.string.shell_tip_snippets))
        if (open && terminal != null) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SnippetPalette(manager) { entry ->
                    manager.run(entry.id, recording = terminal.recording, oneTap = true) { text, secrets -> terminal.sendUserInputGuarded(text, secrets) }
                    open = false
                }
            }
        }
    }
}

@Composable
internal fun SnippetPalette(manager: SnippetManager, onPick: (SnippetEntry) -> Unit) {
    // Registered here rather than at each call site: the palette is only ever shown inside a
    // focusable Popup (toolbar button, host row menu), and both must hand the keyboard back.
    rememberModalPresence()
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    var activeChip by remember { mutableStateOf(ALL_SNIPPETS_CHIP) }
    val filtered = remember(all, activeChip, query) {
        filterSnippets(all, activeChip = activeChip, query = query)
    }

    // Default to collapsed in terminal palette if not customized
    val allCategoryNames = remember(all) { groupSnippetsByCategory(all).map { it.name } }
    var collapsedCategories by remember(all) {
        mutableStateOf(allCategoryNames.toSet())
    }
    var savedCollapsedCategories by remember(all) {
        mutableStateOf(allCategoryNames.toSet())
    }
    val hasTemporaryLayout = collapsedCategories != savedCollapsedCategories
    val allCollapsed = allCategoryNames.isNotEmpty() && allCategoryNames.all { it in collapsedCategories }

    // Autofocus the search field on open — the palette is meant to be driven from the keyboard.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Column(
        Modifier.width(320.dp).clip(RoundedCornerShape(9.dp)).background(Skerry.colors.surface2).border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(9.dp)).padding(6.dp),
    ) {
        val textColor = Skerry.colors.text
        val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("search", size = 15.sp, color = Skerry.colors.faint)
            val placeholder = stringResource(Res.string.term_run_snippet_placeholder)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 12.5.sp, font = mono)
                // The placeholder is the only label this field draws (see fieldName).
                BasicTextField(query, { query = it }, singleLine = true, textStyle = style, cursorBrush = SolidColor(Skerry.colors.cyan), modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).fieldName(placeholder))
            }
        }
        if (hasCategories(all)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
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
                if (hasTemporaryLayout) {
                    IconBtn(
                        "history",
                        onClick = { collapsedCategories = savedCollapsedCategories },
                        box = 24,
                        icon = 14.sp,
                        tint = Skerry.colors.dim,
                        tooltip = stringResource(Res.string.lib_restore_layout),
                    )
                    IconBtn(
                        "bookmark",
                        onClick = { savedCollapsedCategories = collapsedCategories },
                        box = 24,
                        icon = 14.sp,
                        tint = Skerry.colors.cyanBright,
                        tooltip = stringResource(Res.string.lib_save_default_layout),
                    )
                }
                if (activeChip == ALL_SNIPPETS_CHIP) {
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
        val scrollState = rememberScrollState()
        Box(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(top = 6.dp, end = 8.dp)) {
                if (filtered.isEmpty()) {
                    Txt(if (all.isEmpty()) stringResource(Res.string.term_no_snippets_yet) else stringResource(Res.string.term_no_matches), color = Skerry.colors.faint, size = 11.5.sp, font = mono, modifier = Modifier.padding(8.dp))
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
                                category.snippets.forEach { entry -> key(entry.id) { PaletteRow(entry, mono) { onPick(entry) } } }
                            }
                        }
                    }
                } else {
                    filtered.forEach { entry -> key(entry.id) { PaletteRow(entry, mono) { onPick(entry) } } }
                }
            }
            SkerryVerticalScrollbar(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).matchParentSize().padding(top = 4.dp, bottom = 4.dp, end = 1.dp),
            )
        }
    }
}

/** Lines of a command a palette row previews before it ellipsizes. */
private const val ROW_PREVIEW_LINES = 2

@Composable
private fun PaletteRow(entry: SnippetEntry, mono: FontFamily, onClick: () -> Unit) {
    val s = entry.snippet
    // The hover tooltip carries the whole note; the row itself carries the opening of it, because
    // this palette runs a fitting command on one click with no confirmation — a warning only a
    // pointer can reveal is no warning for whoever drives it from the keyboard.
    val note = rememberRowNote(s.notes)
    Box(Modifier.fillMaxWidth()) {
        RowNoteTooltip(note)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .hoverable(note.interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Sym("code_blocks", size = 14.sp, color = Skerry.colors.dim)
                Txt(remember(s) { untrustedLabel(s.label) }.ifBlank { stringResource(Res.string.term_untitled) }, color = Skerry.colors.textBright, size = 12.5.sp, weight = FontWeight.Medium)
                // Gated on the filtered chord, not the raw one: a chip drawn around nothing is a stray
                // pill next to the row.
                val chord = remember(s) { s.shortcut?.let { untrustedLabel(it) }.orEmpty() }
                if (chord.isNotBlank()) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Skerry.colors.bg).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Txt(chord, color = Skerry.colors.faint, size = 10.sp, font = mono)
                    }
                }
            }
            CommandLine(s.command, maxLines = ROW_PREVIEW_LINES, modifier = Modifier.padding(top = 3.dp))
            NoteBlock(
                s.notes, stringResource(Res.string.lib_snippets_field_notes),
                Modifier.padding(top = 3.dp), size = 11.sp, maxLines = NOTE_PEEK_LINES,
            )
        }
    }
}
