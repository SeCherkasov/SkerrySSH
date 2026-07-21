package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.flow.SharedFlow
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.D
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_no_matches
import app.skerry.ui.generated.resources.term_no_snippets_yet
import app.skerry.ui.generated.resources.term_run_snippet_placeholder
import app.skerry.ui.generated.resources.term_untitled
import app.skerry.ui.session.Session
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.UNCATEGORIZED_KEY
import app.skerry.ui.snippet.groupSnippetsByCategory
import app.skerry.ui.snippet.hasCategories
import app.skerry.ui.snippet.matches
import app.skerry.ui.snippet.snippetTagLabel
import app.skerry.ui.snippet.uncategorizedSnippetsLabel
import org.jetbrains.compose.resources.stringResource

// Snippet palette: quickly run a saved command in the active terminal directly from the toolbar.

@Composable
internal fun SnippetPaletteButton(active: Session?, requests: SharedFlow<Unit>? = null) {
    val manager = LocalSnippets.current
    val terminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    // Keyed on active: switching tabs must not leave the palette open over a different toolbar.
    var open by remember(active) { mutableStateOf(false) }
    // Hotkey channel (⌘S / Ctrl+Shift+S). It only opens: with nothing to run into, the palette would
    // be a dead-end popup, so the key falls through to whatever else wants it.
    LaunchedEffect(requests, terminal) { requests?.collect { if (terminal != null) open = true } }
    if (manager == null) return
    Box {
        // Nowhere to run without a connected session — the button is dimmed and doesn't open.
        IconBtn("bolt", onClick = { if (terminal != null) open = !open }, tint = if (terminal != null) D.dim else D.faint)
        if (open && terminal != null) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SnippetPalette(manager) { entry ->
                    manager.run(entry.id) { text -> terminal.send(text) }
                    open = false
                }
            }
        }
    }
}

@Composable
internal fun SnippetPalette(manager: SnippetManager, onPick: (SnippetEntry) -> Unit) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query) }
    // Autofocus the search field on open — the palette is meant to be driven from the keyboard.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Column(
        Modifier.width(320.dp).clip(RoundedCornerShape(9.dp)).background(D.surface2).border(1.dp, D.lineStrong, RoundedCornerShape(9.dp)).padding(6.dp),
    ) {
        val style = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("search", size = 15.sp, color = D.faint)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Txt(stringResource(Res.string.term_run_snippet_placeholder), color = D.faint, size = 12.5.sp, font = mono)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = style, cursorBrush = SolidColor(D.cyan), modifier = Modifier.fillMaxWidth().focusRequester(searchFocus))
            }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (filtered.isEmpty()) {
                Txt(if (all.isEmpty()) stringResource(Res.string.term_no_snippets_yet) else stringResource(Res.string.term_no_matches), color = D.faint, size = 11.5.sp, font = mono, modifier = Modifier.padding(8.dp))
            } else if (hasCategories(filtered)) {
                // Same category split as the library, so a command is two steps away instead of a
                // scroll. No chips or collapsing here — the palette is keyboard-driven and ephemeral.
                groupSnippetsByCategory(filtered).forEach { category ->
                    key(category.name) {
                        PaletteCategoryCaption(category.name)
                        category.snippets.forEach { entry -> key(entry.id) { PaletteRow(entry, mono) { onPick(entry) } } }
                    }
                }
            } else {
                filtered.forEach { entry -> key(entry.id) { PaletteRow(entry, mono) { onPick(entry) } } }
            }
        }
    }
}

@Composable
private fun PaletteCategoryCaption(name: String) {
    Txt(
        if (name == UNCATEGORIZED_KEY) uncategorizedSnippetsLabel() else snippetTagLabel(name),
        color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 9.dp, top = 7.dp, bottom = 2.dp),
    )
}

@Composable
private fun PaletteRow(entry: SnippetEntry, mono: FontFamily, onClick: () -> Unit) {
    val s = entry.snippet
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("code_blocks", size = 14.sp, color = D.dim)
            Txt(s.label.ifBlank { stringResource(Res.string.term_untitled) }, color = D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
            if (!s.shortcut.isNullOrBlank()) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(D.bg).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Txt(s.shortcut!!, color = D.faint, size = 10.sp, font = mono)
                }
            }
        }
        Txt(s.command, color = D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
    }
}
