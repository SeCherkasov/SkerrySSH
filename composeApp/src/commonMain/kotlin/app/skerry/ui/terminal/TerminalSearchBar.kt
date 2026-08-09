package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.hoverable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.TerminalSearchError
import app.skerry.ui.design.HoverTooltip
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.terminal_search_close
import app.skerry.ui.generated.resources.terminal_search_counter
import app.skerry.ui.generated.resources.terminal_search_counter_capped
import app.skerry.ui.generated.resources.terminal_search_invalid_pattern
import app.skerry.ui.generated.resources.terminal_search_match_case
import app.skerry.ui.generated.resources.terminal_search_next
import app.skerry.ui.generated.resources.terminal_search_no_matches
import app.skerry.ui.generated.resources.terminal_search_placeholder
import app.skerry.ui.generated.resources.terminal_search_prev
import app.skerry.ui.generated.resources.terminal_search_regex
import app.skerry.ui.generated.resources.terminal_search_too_complex
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Width of the query field on desktop; on a phone it takes the row's free space instead. */
internal val SEARCH_FIELD_WIDTH = 200.dp

/**
 * Search panel over the terminal's output ([TerminalOutputSearch.query] and friends): query
 * field, hit counter, case/regex toggles, and previous/next/close controls. Sits at the pane's top
 * edge the way a browser's find bar sits over the page; the highlight itself is painted onto the
 * cell grid by [TerminalScreen].
 *
 * The field owns keyboard focus while the panel is open, so its keys never reach the PTY: Enter /
 * Shift+Enter step through hits, Esc closes. The same panel serves desktop and mobile — on touch it
 * takes the soft keyboard from the terminal's hidden IME field, which [TerminalScreen] stands down
 * while the panel is up.
 */
@Composable
internal fun TerminalSearchBar(state: TerminalScreenState, modifier: Modifier = Modifier, expand: Boolean = false) {
    val mono = LocalFonts.current.mono
    val query = state.search.query ?: return
    val focusRequester = remember { FocusRequester() }
    // The field's own buffer: the terminal state holds the query, this adds the caret. On open it is
    // seeded from the restored query with everything selected, so typing replaces it (as find bars
    // do). Keyed on the session: switching tabs swaps `state` while reusing this composable, and an
    // unkeyed buffer would keep showing the previous tab's query next to the new tab's matches.
    var value by remember(state) { mutableStateOf(TextFieldValue(query, TextRange(0, query.length))) }
    LaunchedEffect(state) { focusRequester.requestFocus() }
    Row(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("search", size = 15.sp, color = Skerry.colors.faint)
        // Fixed width (or the whole free row on a phone) with the field filling it: sized by its own
        // text instead, an empty field is a few pixels wide and the rest of the input area is dead —
        // a click there falls through to the terminal behind the panel and takes the focus with it.
        Box(
            if (expand) Modifier.weight(1f) else Modifier.width(SEARCH_FIELD_WIDTH),
            contentAlignment = Alignment.CenterStart,
        ) {
            val placeholder = stringResource(Res.string.terminal_search_placeholder)
            if (value.text.isEmpty()) {
                Txt(placeholder, color = Skerry.colors.faint, size = 12.sp, font = mono)
            }
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    state.search.updateQuery(it.text)
                },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 12.sp, fontFamily = mono),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search,
                ),
                // The soft keyboard's search key steps to the next hit — the touch equivalent of
                // Enter, which arrives as a key event only on desktop.
                keyboardActions = KeyboardActions(onSearch = { state.search.next() }),
                modifier = Modifier
                    .fillMaxWidth()
                    // The panel has no caption above the field; what it searches is said by the
                    // placeholder, so that is the field's name too (as in the sidebar search).
                    .fieldName(placeholder)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { state.search.close(); true }
                            // Enter walks the hits (Shift reverses), like every find bar. Consumed
                            // either way: a newline must not slip into the query or the PTY.
                            Key.Enter, Key.NumPadEnter -> {
                                if (event.isShiftPressed) state.search.prev() else state.search.next()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        SearchStatus(state, mono)
        // Toggles re-run the current query, so a search can be narrowed without retyping it.
        SearchToggle("Aa", state.search.caseSensitive, stringResource(Res.string.terminal_search_match_case), mono) {
            state.search.applyCase(!state.search.caseSensitive)
        }
        SearchToggle(".*", state.search.regex, stringResource(Res.string.terminal_search_regex), mono) {
            state.search.applyRegex(!state.search.regex)
        }
        IconBtn("keyboard_arrow_up", onClick = state.search::prev, box = 24, icon = 16.sp, tooltip = stringResource(Res.string.terminal_search_prev))
        IconBtn("keyboard_arrow_down", onClick = state.search::next, box = 24, icon = 16.sp, tooltip = stringResource(Res.string.terminal_search_next))
        IconBtn("close", onClick = state.search::close, box = 24, icon = 16.sp, tooltip = stringResource(Res.string.terminal_search_close))
    }
}

/** Hit counter ("3/17"), or why the query yielded nothing. */
@Composable
private fun SearchStatus(state: TerminalScreenState, mono: FontFamily) {
    val error = state.search.error
    val total = state.search.matches.size
    when {
        error != null -> Txt(
            when (error) {
                TerminalSearchError.InvalidPattern -> stringResource(Res.string.terminal_search_invalid_pattern)
                TerminalSearchError.PatternTooComplex -> stringResource(Res.string.terminal_search_too_complex)
            },
            color = Skerry.colors.sunset, size = 11.sp, font = mono,
        )
        state.search.query.isNullOrEmpty() -> Unit // nothing typed yet — no verdict to show
        total == 0 -> Txt(stringResource(Res.string.terminal_search_no_matches), color = Skerry.colors.faint, size = 11.sp, font = mono)
        // A capped list ("3/5000+") must not read as the true total.
        else -> Txt(
            stringResource(
                if (state.search.truncated) Res.string.terminal_search_counter_capped else Res.string.terminal_search_counter,
                state.search.index + 1,
                total,
            ),
            color = Skerry.colors.dim, size = 11.sp, font = mono,
        )
    }
}

/** Case / regex toggle chip: cyan-filled while on, like the mobile keybar's sticky `ctrl` key. */
@Composable
private fun SearchToggle(label: String, active: Boolean, tooltip: String, mono: FontFamily, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) Skerry.colors.cyan else Skerry.colors.overlayMed)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Txt(
            label,
            color = if (active) Skerry.colors.ink else Skerry.colors.dim,
            size = 11.sp,
            weight = FontWeight.Medium,
            font = mono,
        )
        if (hovered) HoverTooltip(tooltip)
    }
}
