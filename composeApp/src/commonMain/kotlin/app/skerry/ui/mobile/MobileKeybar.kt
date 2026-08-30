package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_key_alt
import app.skerry.ui.generated.resources.term_key_armed
import app.skerry.ui.generated.resources.term_key_assistant
import app.skerry.ui.generated.resources.term_key_ctrl
import app.skerry.ui.generated.resources.term_key_cycle_suggestion
import app.skerry.ui.generated.resources.term_key_dash
import app.skerry.ui.generated.resources.term_key_down
import app.skerry.ui.generated.resources.term_key_end
import app.skerry.ui.generated.resources.term_key_escape
import app.skerry.ui.generated.resources.term_key_find_output
import app.skerry.ui.generated.resources.term_key_fn
import app.skerry.ui.generated.resources.term_key_function
import app.skerry.ui.generated.resources.term_key_hide_keyboard
import app.skerry.ui.generated.resources.term_key_home
import app.skerry.ui.generated.resources.term_key_insert
import app.skerry.ui.generated.resources.term_key_left
import app.skerry.ui.generated.resources.term_key_open
import app.skerry.ui.generated.resources.term_key_page_down
import app.skerry.ui.generated.resources.term_key_page_up
import app.skerry.ui.generated.resources.term_key_pipe
import app.skerry.ui.generated.resources.term_key_right
import app.skerry.ui.generated.resources.term_key_search_history
import app.skerry.ui.generated.resources.term_key_search_newer
import app.skerry.ui.generated.resources.term_key_search_older
import app.skerry.ui.generated.resources.term_key_search_remove
import app.skerry.ui.generated.resources.term_key_slash
import app.skerry.ui.generated.resources.term_key_tab
import app.skerry.ui.generated.resources.term_key_tilde
import app.skerry.ui.generated.resources.term_key_up
import app.skerry.ui.immersive.hiddenSystemBarsPadding
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Special-key panel — the core of the mobile SSH UX. Two rows of equal width laid out as a grid
 * ([keybarRows]): esc/tab, the sticky ctrl and alt modifiers, the shell punctuation, the arrow cross,
 * Home/End/PgUp/PgDn, and `fn` for the F1-F12 layer. Nothing scrolls sideways — a swipe over the
 * panel belongs to the terminal above it, and a key found by looking is a key found again by feel.
 *
 * Sequences come from [keybarEffect], which encodes through the same [app.skerry.ui.terminal.mapTerminalKey]
 * the physical keyboard uses, so an armed modifier reaches the PTY inside the sequence (`ESC[1;5C` =
 * ctrl+→, a word jump) instead of being dropped. Printable keys go through
 * [TerminalScreenState.typeInput] so the production guard sees the whole line; control sequences go
 * through [TerminalScreenState.sendUserInput], which also snaps a scrolled-back viewport to the live
 * screen. The sticky [modifiers] belong to the screen, not to this panel: they apply to soft-keyboard
 * input too (the IME path bypasses the panel).
 */
@Composable
internal fun MobileKeybar(
    terminal: TerminalScreenState,
    modifiers: StickyModifiers,
    aiOpen: Boolean = false,
    onToggleAi: (() -> Unit)? = null,
    // True when the tab bar is not laid out below this panel (a modal is up, or the keyboard is).
    // Whatever is bottom-most owns the navigation-bar inset, and in immersive mode the live inset
    // is zero — so a swipe that transiently reveals the bars would land on the keys.
    reserveSystemBars: Boolean = false,
) {
    // Keyed on the session: another session's panel must open on the base layer, not on whatever
    // layer the last one was left in.
    var fnLayer by remember(terminal) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val disarm = { modifiers.disarm() }
    val press = { key: KeybarKey ->
        // Tab with an autocomplete suggestion — accept it; otherwise the effect below sends a plain
        // tab. An armed modifier is spent on the accept rather than carried: Ctrl/Alt+Tab has no
        // terminal meaning to carry it to.
        if (key == KeybarKey.Tab && terminal.hasSuggestion) {
            terminal.acceptSuggestion()
            disarm()
        } else {
            when (val effect = keybarEffect(key, modifiers.ctrl, modifiers.alt, terminal.applicationCursorKeys)) {
                is KeybarEffect.Type -> { terminal.typeInput(effect.text); disarm() }
                is KeybarEffect.Send -> { terminal.sendUserInput(effect.sequence); disarm() }
                is KeybarEffect.Run -> when (effect.command) {
                    // Ctrl and Alt arm independently: ctrl+alt+arrow is a sequence of its own (mod 7).
                    KeybarCommand.ArmCtrl -> modifiers.ctrl = !modifiers.ctrl
                    KeybarCommand.ArmAlt -> modifiers.alt = !modifiers.alt
                    KeybarCommand.ToggleFn -> fnLayer = !fnLayer
                    // Hide only: the keyboard comes back on a tap into the terminal, which raises it
                    // explicitly (TerminalScreen's tap handler), so there is nothing to toggle here.
                    KeybarCommand.HideKeyboard -> keyboard?.hide()
                    KeybarCommand.ToggleAi -> onToggleAi?.invoke()
                    // Both searches take the soft keyboard for their query, and the panel becomes
                    // the search layer — leaving the fn layer armed underneath would return the
                    // user to F1-F12 when the search closes.
                    KeybarCommand.SearchHistory -> { terminal.reverseSearch.open(); fnLayer = false; disarm() }
                    KeybarCommand.FindOutput -> { terminal.search.open(); fnLayer = false; disarm() }
                    KeybarCommand.CycleSuggestion -> terminal.cycleSuggestion()
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Skerry.colors.surface2)
            .then(if (reserveSystemBars) Modifier.hiddenSystemBarsPadding(top = false, bottom = true) else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        // While reverse-search (Ctrl-R) is open, the panel shows its controls; the query is typed on
        // the soft keyboard (routed to TerminalScreen). One row: the layer has five keys, and a grid
        // of blanks would only take a line of terminal.
        if (terminal.reverseSearch.query != null) {
            ReverseSearchRow(terminal, onDone = disarm)
            return@Column
        }
        for (row in keybarRows(fnLayer)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (key in row) {
                    KeybarCap(
                        key = key,
                        terminal = terminal,
                        modifiers = modifiers,
                        fnLayer = fnLayer,
                        aiOpen = aiOpen,
                        aiAvailable = onToggleAi != null,
                        onClick = { press(key) },
                    )
                }
            }
        }
    }
}

/**
 * Controls of the reverse-search layer (Ctrl-R): leave, walk the matches, drop one, accept.
 *
 * One row of keys, but the height of the grid it replaces. The terminal is this panel's sibling under
 * a weight and resizes the PTY when its viewport settles, so a shorter layer would hand the host a
 * SIGWINCH and make the shell redraw the very search prompt being read — twice, counting the way back.
 */
@Composable
private fun ReverseSearchRow(terminal: TerminalScreenState, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(GRID_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TextCap("esc", stringResource(Res.string.term_key_escape), Modifier.weight(1f)) {
            terminal.reverseSearch.close()
            onDone()
        }
        IconCap("expand_more", stringResource(Res.string.term_key_search_older), Modifier.weight(1f)) {
            terminal.reverseSearch.next()
        }
        IconCap("expand_less", stringResource(Res.string.term_key_search_newer), Modifier.weight(1f)) {
            terminal.reverseSearch.prev()
        }
        IconCap("delete", stringResource(Res.string.term_key_search_remove), Modifier.weight(1f)) {
            terminal.reverseSearch.deleteSelected()
        }
        // A glyph, not the word: the other four caps of this row are glyphs, and "insert" here is a
        // verb the panel would have to translate rather than a key legend like `esc` or `pgup`.
        IconCap("check", stringResource(Res.string.term_key_insert), Modifier.weight(1f), accent = true) {
            terminal.reverseSearch.accept()
            onDone()
        }
    }
}

/**
 * One key of the grid: a label or a glyph, sized by the row it shares.
 *
 * Whether a key is live is decided here rather than by the caller: `hasSuggestion` flips on nearly
 * every keystroke, and reading it in the panel's own scope would recompose all eighteen caps for it.
 */
@Composable
private fun RowScope.KeybarCap(
    key: KeybarKey,
    terminal: TerminalScreenState,
    modifiers: StickyModifiers,
    fnLayer: Boolean,
    aiOpen: Boolean,
    aiAvailable: Boolean,
    onClick: () -> Unit,
) {
    val cell = Modifier.weight(1f)
    // A key whose dependency is missing is drawn dimmed and refuses the tap, rather than looking
    // live and doing nothing: the desktop composition has no soft keyboard to hide.
    val keyboardPresent = LocalSoftwareKeyboardController.current != null
    val enabled = when (key) {
        KeybarKey.Ai -> aiAvailable
        KeybarKey.CycleSuggestion -> terminal.hasSuggestion
        KeybarKey.HideKeyboard -> keyboardPresent
        else -> true
    }
    val glyph = GLYPHS[key]
    if (glyph != null) {
        IconCap(
            icon = glyph.icon,
            name = stringResource(glyph.name),
            modifier = cell,
            accent = key == KeybarKey.Ai && aiOpen,
            // The assistant is a toggle, and its state is spoken rather than left to the cyan tint.
            active = key == KeybarKey.Ai && aiOpen,
            enabled = enabled,
            onClick = onClick,
        )
    } else {
        // The modifiers and the layer switch are special keys: cyan at rest, solid while armed —
        // and the armed state is spoken, not left to colour alone.
        val active = when (key) {
            KeybarKey.Ctrl -> modifiers.ctrl
            KeybarKey.Alt -> modifiers.alt
            KeybarKey.Fn -> fnLayer
            else -> false
        }
        TextCap(
            label = keybarLabel(key),
            name = keybarSpokenName(key),
            modifier = cell,
            accent = key == KeybarKey.Ctrl || key == KeybarKey.Alt || key == KeybarKey.Fn,
            active = active,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

/** A glyph key: its Material Symbols ligature and the name a screen reader reads instead of it. */
private data class KeybarGlyph(val icon: String, val name: StringResource)

/**
 * The keys drawn as icons. Glyph and spoken name are stored together on purpose: as two parallel
 * `when` blocks, a new glyph key added to one and forgotten in the other compiles and announces
 * itself as whatever the fallback branch happened to be.
 */
private val GLYPHS = mapOf(
    KeybarKey.Up to KeybarGlyph("keyboard_arrow_up", Res.string.term_key_up),
    KeybarKey.Down to KeybarGlyph("keyboard_arrow_down", Res.string.term_key_down),
    KeybarKey.Left to KeybarGlyph("keyboard_arrow_left", Res.string.term_key_left),
    KeybarKey.Right to KeybarGlyph("keyboard_arrow_right", Res.string.term_key_right),
    KeybarKey.HideKeyboard to KeybarGlyph("keyboard_hide", Res.string.term_key_hide_keyboard),
    KeybarKey.Ai to KeybarGlyph("auto_awesome", Res.string.term_key_assistant),
    KeybarKey.SearchHistory to KeybarGlyph("search", Res.string.term_key_search_history),
    KeybarKey.FindOutput to KeybarGlyph("find_in_page", Res.string.term_key_find_output),
    KeybarKey.CycleSuggestion to KeybarGlyph("autorenew", Res.string.term_key_cycle_suggestion),
)

/**
 * Label of a key drawn as text. Key names, not UI copy: `esc`, `pgup` and `f5` are what the keys are
 * called on every terminal keyboard and are not translated.
 */
private fun keybarLabel(key: KeybarKey): String = LABELS[key] ?: key.name.lowercase() // f1..f12

private val LABELS = mapOf(
    KeybarKey.Esc to "esc",
    KeybarKey.Tab to "tab",
    KeybarKey.Ctrl to "ctrl",
    KeybarKey.Alt to "alt",
    KeybarKey.Fn to "fn",
    KeybarKey.Slash to "/",
    KeybarKey.Pipe to "|",
    KeybarKey.Dash to "-",
    KeybarKey.Tilde to "~",
    KeybarKey.Home to "home",
    KeybarKey.End to "end",
    KeybarKey.PageUp to "pgup",
    KeybarKey.PageDown to "pgdn",
)

/**
 * What a screen reader says for a text cap. The printed label is not it: a lone `~` or `|` is
 * pronounced differently by every TTS engine (some say nothing at all), and `pgup`/`pgdn`/`esc` are
 * abbreviations no engine has a word for — spelled out, they are indistinguishable by ear.
 */
@Composable
private fun keybarSpokenName(key: KeybarKey): String = SPOKEN[key]?.let { stringResource(it) }
    ?: stringResource(Res.string.term_key_function, key.name.removePrefix("F")) // f1..f12

private val SPOKEN = mapOf(
    KeybarKey.Esc to Res.string.term_key_escape,
    KeybarKey.Tab to Res.string.term_key_tab,
    KeybarKey.Ctrl to Res.string.term_key_ctrl,
    KeybarKey.Alt to Res.string.term_key_alt,
    KeybarKey.Fn to Res.string.term_key_fn,
    KeybarKey.Slash to Res.string.term_key_slash,
    KeybarKey.Pipe to Res.string.term_key_pipe,
    KeybarKey.Dash to Res.string.term_key_dash,
    KeybarKey.Tilde to Res.string.term_key_tilde,
    KeybarKey.Home to Res.string.term_key_home,
    KeybarKey.End to Res.string.term_key_end,
    KeybarKey.PageUp to Res.string.term_key_page_up,
    KeybarKey.PageDown to Res.string.term_key_page_down,
)

/**
 * Height of every cap, so the two rows read as a grid rather than two independent strips. Below the
 * 48dp target recommendation on purpose: two rows at that height would take a fifth of the screen
 * above an open soft keyboard. Nine caps to a row make it ~35dp wide on a 360dp phone, which clears
 * the 24dp floor of WCAG 2.5.8 with room, and the panel is the one surface where the terminal's own
 * lines are the scarce resource.
 */
private val CAP_HEIGHT = 40.dp

/** Gap between the two rows, and [GRID_HEIGHT] the total the search layer has to match. */
private val ROW_GAP = 6.dp
private val GRID_HEIGHT = CAP_HEIGHT * 2 + ROW_GAP

/**
 * Text key, named for a screen reader by [name] — the label alone is unpronounceable for half of
 * them. [accent] — special key (cyan at rest, like `ctrl`); [active] — sticky arming, which is
 * spoken as a state rather than shown only as colour.
 */
@Composable
private fun TextCap(
    label: String,
    name: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        active -> Skerry.colors.cyan
        accent -> Skerry.colors.cyan14
        else -> Skerry.colors.overlayMed
    }
    val fg = when {
        !enabled -> Skerry.colors.faint
        active -> Skerry.colors.ink
        accent -> Skerry.colors.cyanBright
        else -> Skerry.colors.textBright
    }
    val armed = stringResource(Res.string.term_key_armed)
    Box(
        modifier
            .height(CAP_HEIGHT)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = name
                if (active) stateDescription = armed
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = fg, size = if (label.length > 3) 11.sp else 12.5.sp, maxLines = 1, font = LocalFonts.current.mono)
    }
}

/**
 * Glyph key, named for a screen reader by [name]. [active] — a toggle that is currently on, spoken as
 * a state rather than shown only as colour.
 *
 * Not `ui/design/GlyphButton`, which the catalogue names for a touch glyph box: that one is
 * `Modifier.size` square, which cannot take the grid's `weight(1f)` with a fixed height, and it has
 * no disabled state, which the caps whose dependency is missing need.
 */
@Composable
private fun IconCap(
    icon: String,
    name: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val open = stringResource(Res.string.term_key_open)
    val fg = when {
        !enabled -> Skerry.colors.faint
        accent -> Skerry.colors.cyanBright
        else -> Skerry.colors.textBright
    }
    Box(
        modifier
            .height(CAP_HEIGHT)
            .clip(RoundedCornerShape(7.dp))
            .background(if (accent) Skerry.colors.cyan14 else Skerry.colors.overlayMed)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = name
                if (active) stateDescription = open
            },
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 16.sp, color = fg)
    }
}
