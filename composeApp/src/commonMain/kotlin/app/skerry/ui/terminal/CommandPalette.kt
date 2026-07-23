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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.CommandSuggestion
import app.skerry.shared.terminal.TerminalHistoryRecord
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.terminal.commandSuggestions
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_palette_empty
import app.skerry.ui.generated.resources.term_palette_hint
import app.skerry.ui.generated.resources.term_palette_placeholder
import app.skerry.ui.generated.resources.term_palette_this_host
import app.skerry.ui.generated.resources.term_palette_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import app.skerry.ui.theme.Skerry

/**
 * Command palette (⌘K / Ctrl+Shift+K): fuzzy search over the command history of every host, with
 * the current session's commands first. Picking one *inserts* it into the terminal without running
 * it ([TerminalScreenState.applyHistoryCommand]) — these are real commands against real servers, so
 * the last keystroke stays the user's.
 *
 * History is read once per opening, off the UI thread: decrypting the vault records is disk work
 * and would jank the terminal underneath.
 */
@Composable
internal fun CommandPalette(
    history: TerminalHistoryStore?,
    currentKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<TerminalHistoryRecord>?>(null) }
    LaunchedEffect(history) {
        records = withContext(Dispatchers.Default) { history?.all().orEmpty() }
    }
    val suggestions = remember(records, query, currentKey) {
        records?.let { commandSuggestions(it, currentKey, query) }.orEmpty()
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    ModalScrim(onDismiss = onDismiss, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(top = 90.dp)
                .width(560.dp)
                .consumeClicks()
                .clip(RoundedCornerShape(10.dp))
                .background(Skerry.colors.surface2)
                .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(10.dp))
                .padding(8.dp)
                // Enter on the first hit: the palette is meant to be driven without the mouse.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        suggestions.firstOrNull()?.let { onPick(it.command) }
                        true
                    } else {
                        false
                    }
                },
        ) {
            Txt(stringResource(Res.string.term_palette_title), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            PaletteSearch(query, { query = it }, mono, searchFocus)
            // Lazy, not a scrolling Column: the list runs to [commandSuggestions]'s cap of 200 and
            // is rebuilt on every keystroke, so only the visible rows should be laid out.
            LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 6.dp)) {
                if (records == null) {
                    // Still loading; an empty box beats flashing "no commands" for a frame.
                } else if (suggestions.isEmpty()) {
                    item {
                        Txt(stringResource(Res.string.term_palette_empty), color = Skerry.colors.faint, size = 11.5.sp, font = mono, modifier = Modifier.padding(8.dp))
                    }
                } else {
                    items(suggestions, key = { it.command }) { suggestion ->
                        CommandRow(suggestion, mono) { onPick(suggestion.command) }
                    }
                }
            }
            Txt(stringResource(Res.string.term_palette_hint), color = Skerry.colors.faint, size = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
        }
    }
}

@Composable
private fun PaletteSearch(value: String, onValueChange: (String) -> Unit, mono: FontFamily, focus: FocusRequester) {
    val textColor = Skerry.colors.text
    val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = mono) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("search", size = 16.sp, color = Skerry.colors.faint)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(stringResource(Res.string.term_palette_placeholder), color = Skerry.colors.faint, size = 13.sp, font = mono)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Skerry.colors.cyan),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

/**
 * The command with the characters the query matched picked out — what makes a fuzzy hit readable
 * ("dcp" matching `docker compose pull`). Positions come from the matcher, which is the only thing
 * that knows which characters they were.
 */
@Composable
internal fun highlightMatches(suggestion: CommandSuggestion): AnnotatedString = buildAnnotatedString {
    append(suggestion.command)
    val hit = SpanStyle(color = Skerry.colors.cyanBright, fontWeight = FontWeight.SemiBold)
    for (i in suggestion.positions) {
        if (i in suggestion.command.indices) addStyle(hit, i, i + 1)
    }
}

@Composable
private fun CommandRow(suggestion: CommandSuggestion, mono: FontFamily, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(highlightMatches(suggestion), color = Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        val origin = when {
            suggestion.fromCurrentHost -> stringResource(Res.string.term_palette_this_host)
            else -> suggestion.hostLabel
        }
        if (origin != null) {
            Txt(origin, color = if (suggestion.fromCurrentHost) Skerry.colors.cyanBright else Skerry.colors.faint, size = 10.sp)
        }
    }
}
