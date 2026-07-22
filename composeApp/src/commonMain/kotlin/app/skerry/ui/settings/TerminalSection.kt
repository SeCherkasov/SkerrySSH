package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.design.Badge
import app.skerry.ui.design.D
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_badge_active
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
import app.skerry.ui.generated.resources.settings_terminal_cursor_bar_blink
import app.skerry.ui.generated.resources.settings_terminal_cursor_bar_steady
import app.skerry.ui.generated.resources.settings_terminal_cursor_block_blink
import app.skerry.ui.generated.resources.settings_terminal_cursor_block_steady
import app.skerry.ui.generated.resources.settings_terminal_cursor_style
import app.skerry.ui.generated.resources.settings_terminal_cursor_underline_blink
import app.skerry.ui.generated.resources.settings_terminal_cursor_underline_steady
import app.skerry.ui.generated.resources.settings_terminal_clipboard_write
import app.skerry.ui.generated.resources.settings_terminal_clipboard_write_desc
import app.skerry.ui.generated.resources.settings_terminal_scrollback
import app.skerry.ui.generated.resources.settings_terminal_scrollback_desc
import app.skerry.ui.generated.resources.settings_terminal_section_behavior
import app.skerry.ui.generated.resources.settings_terminal_section_font
import app.skerry.ui.generated.resources.settings_terminal_show_title
import app.skerry.ui.generated.resources.settings_terminal_show_title_desc
import app.skerry.ui.generated.resources.settings_terminal_subtitle
import app.skerry.ui.generated.resources.settings_terminal_title
import app.skerry.ui.generated.resources.settings_terminal_host_connect
import app.skerry.ui.generated.resources.settings_terminal_host_connect_single
import app.skerry.ui.generated.resources.settings_terminal_host_connect_double
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

// Terminal section: themes, font/metrics, scrollback, cursor style, live OSC title on tabs.

@Composable
internal fun TerminalSection(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    SectionTitle(stringResource(Res.string.settings_terminal_title), stringResource(Res.string.settings_terminal_subtitle))
    // Theme cards in a 2×N grid from the [TerminalThemes] catalog; selection applies to the terminal live.
    TerminalThemes.all.chunked(2).forEachIndexed { rowIndex, rowThemes ->
        if (rowIndex > 0) Box(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (theme in rowThemes) {
                ThemeCard(
                    theme = theme,
                    active = theme.id == state.terminalTheme.id,
                    mono = mono,
                    onClick = { state.chooseTerminalTheme(theme) },
                    modifier = Modifier.weight(1f),
                )
            }
            // Odd tail: pad with an empty cell so the last card doesn't stretch to full width.
            if (rowThemes.size == 1) Box(Modifier.weight(1f))
        }
    }
    // One setting per full-width row: label + default-value hint (with quick reset) on the left,
    // control on the right. Size/line-height/spacing are sub-settings of the font row (indented,
    // tighter spacing) — one block; they use a numeric stepper for precise input.
    SectionLabel(stringResource(Res.string.settings_terminal_section_font))
    SettingRow(label = stringResource(Res.string.appearance_font)) {
        Box(Modifier.width(180.dp)) { FontPicker(state.terminalFont, onPick = state::chooseTerminalFont) }
    }
    SettingRow(
        label = stringResource(Res.string.appearance_font_size),
        modifier = Modifier.padding(start = 14.dp, top = 10.dp),
        hasHint = true,
        isDefault = state.terminalFontSize == DEFAULT_TERMINAL_FONT_SIZE,
        defaultText = "$DEFAULT_TERMINAL_FONT_SIZE px",
        onReset = { state.chooseTerminalFontSize(DEFAULT_TERMINAL_FONT_SIZE) },
    ) {
        NumberStepper(
            value = state.terminalFontSize.toFloat(),
            onValueChange = { state.chooseTerminalFontSize(it.roundToInt().coerceIn(TERMINAL_FONT_SIZE_MIN, TERMINAL_FONT_SIZE_MAX)) },
            step = 1f,
            format = { it.roundToInt().toString() },
            parse = { it.trim().toIntOrNull()?.toFloat() },
            suffix = "px",
        )
    }
    SettingRow(
        label = stringResource(Res.string.appearance_line_height),
        modifier = Modifier.padding(start = 14.dp, top = 10.dp),
        hasHint = true,
        isDefault = formatDecimal(state.terminalLineHeight, 2) == formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
        defaultText = formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
        onReset = { state.chooseTerminalLineHeight(DEFAULT_TERMINAL_LINE_HEIGHT) },
    ) {
        NumberStepper(
            value = state.terminalLineHeight,
            onValueChange = state::chooseTerminalLineHeight,
            step = 0.05f,
            format = { formatDecimal(it, 2) },
            parse = { it.trim().replace(',', '.').toFloatOrNull() },
            fieldWidth = 52.dp,
        )
    }
    SettingRow(
        label = stringResource(Res.string.appearance_letter_spacing),
        modifier = Modifier.padding(start = 14.dp, top = 10.dp),
        hasHint = true,
        isDefault = formatDecimal(state.terminalLetterSpacing, 1) == formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1),
        defaultText = "${formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1)} px",
        onReset = { state.chooseTerminalLetterSpacing(DEFAULT_TERMINAL_LETTER_SPACING) },
    ) {
        NumberStepper(
            value = state.terminalLetterSpacing,
            onValueChange = state::chooseTerminalLetterSpacing,
            step = 0.1f,
            format = { formatDecimal(it, 1) },
            parse = { it.trim().replace(',', '.').toFloatOrNull() },
            suffix = "px",
            fieldWidth = 52.dp,
        )
    }
    SectionLabel(stringResource(Res.string.settings_terminal_section_behavior))
    // Scrollback depth for new sessions (preset picker to the right of the label).
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_terminal_scrollback), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.settings_terminal_scrollback_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(160.dp)) { ScrollbackPicker(state.terminalScrollback, onPick = state::chooseTerminalScrollback) }
    }
    HLine()
    // Cursor style: default shape x blink for new sessions.
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt(stringResource(Res.string.settings_terminal_cursor_style), color = D.text, size = 13.sp, weight = FontWeight.Medium) }
        Box(Modifier.width(200.dp)) { CursorStylePicker(state.terminalCursorStyle, onPick = state::chooseTerminalCursorStyle) }
    }
    HLine()
    // Live terminal OSC title on tabs: enables the effectiveTabTitle branch in Session.tabTitle.
    SettingToggleRow(
        stringResource(Res.string.settings_terminal_show_title),
        stringResource(Res.string.settings_terminal_show_title_desc),
        on = state.showTerminalTitleOnTabs,
        onToggle = state::toggleShowTerminalTitleOnTabs,
    )
    HLine()
    // Host-row click behavior: single click connects directly, double click requires a second
    // click (protects against accidental connects when just browsing the catalog). Desktop-only.
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt(stringResource(Res.string.settings_terminal_host_connect), color = D.text, size = 13.sp, weight = FontWeight.Medium) }
        // No fixed-width Box: DropdownField sizes itself to the longest option, matching the
        // neighbouring CursorStylePicker.
        Box(Modifier.width(200.dp)) { HostConnectModePicker(state.hostClickConnectMode, onPick = state::chooseHostClickConnectMode) }
    }
    HLine()
    // OSC 52 clipboard-write gate (default off, like xterm/kitty): keeps an untrusted host from
    // silently overwriting the system clipboard. Applies to new and already-open sessions.
    SettingToggleRow(
        stringResource(Res.string.settings_terminal_clipboard_write),
        stringResource(Res.string.settings_terminal_clipboard_write_desc),
        on = state.allowServerClipboardWrite,
        onToggle = state::toggleAllowServerClipboardWrite,
    )
}

/**
 * Formats a float with a fixed number of decimal places (KMP-common, no String.format).
 * Preserves the sign for negative fractions with a zero integer part (-0.5).
 */
internal fun formatDecimal(value: Float, decimals: Int): String {
    val factor = if (decimals <= 1) 10 else 100
    val scaled = (value * factor).roundToInt()
    val whole = scaled / factor
    val frac = abs(scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0 && whole == 0) "-" else ""
    return "$sign$whole.$frac"
}

/** Terminal font dropdown (Hack / JetBrains Mono) — neither has ligatures. */
@Composable
private fun FontPicker(current: TerminalFont, onPick: (TerminalFont) -> Unit) {
    DropdownField(current, TerminalFont.entries, label = { it.displayName }, onPick = onPick)
}

/**
 * Terminal theme card: a mini `ls -la` preview rendered in the actual [theme] colors
 * (background/text/ANSI) so the user sees the palette before applying it. Click selects the
 * theme; the active one gets a cyan border and badge.
 */
@Composable
private fun ThemeCard(
    theme: TerminalTheme,
    active: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) D.cyan else D.cyan08, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(theme.background).padding(10.dp)) {
            Row { Txt("~ ", color = theme.ansi[2], size = 10.sp, font = mono); Txt("ls -la", color = theme.foreground, size = 10.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = theme.ansi[6], size = 10.sp, font = mono); Txt("src", color = theme.ansi[4], size = 10.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = theme.ansi[8], size = 10.sp, font = mono); Txt(".env", color = theme.ansi[3], size = 10.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(theme.displayName, color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            if (active) Badge(stringResource(Res.string.appearance_badge_active), bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 9.sp)
        }
    }
}

/** Localized cursor style label (shape + blink) for the dropdown and its trigger. */
@Composable
internal fun TerminalCursorStyle.cursorStyleLabel(): String = stringResource(
    when (this) {
        TerminalCursorStyle.BlockBlink -> Res.string.settings_terminal_cursor_block_blink
        TerminalCursorStyle.BlockSteady -> Res.string.settings_terminal_cursor_block_steady
        TerminalCursorStyle.UnderlineBlink -> Res.string.settings_terminal_cursor_underline_blink
        TerminalCursorStyle.UnderlineSteady -> Res.string.settings_terminal_cursor_underline_steady
        TerminalCursorStyle.BarBlink -> Res.string.settings_terminal_cursor_bar_blink
        TerminalCursorStyle.BarSteady -> Res.string.settings_terminal_cursor_bar_steady
    },
)

/** Dropdown for scrollback depth ([TERMINAL_SCROLLBACK_OPTIONS], lines; formatted as "10 000"). */
@Composable
private fun ScrollbackPicker(current: Int, onPick: (Int) -> Unit) {
    DropdownField(current, TERMINAL_SCROLLBACK_OPTIONS, label = { formatScrollback(it) }, onPick = onPick)
}

/** Dropdown for cursor style ([TerminalCursorStyle.entries]). */
@Composable
private fun CursorStylePicker(current: TerminalCursorStyle, onPick: (TerminalCursorStyle) -> Unit) {
    DropdownField(current, TerminalCursorStyle.entries, label = { it.cursorStyleLabel() }, onPick = onPick)
}

/** Localized host-connect-mode label for the dropdown and its trigger. */
@Composable
internal fun HostClickConnectMode.hostConnectModeLabel(): String = stringResource(
    when (this) {
        HostClickConnectMode.SingleClick -> Res.string.settings_terminal_host_connect_single
        HostClickConnectMode.DoubleClick -> Res.string.settings_terminal_host_connect_double
    },
)

/** Dropdown for host-row connect click behavior (single/double click). */
@Composable
private fun HostConnectModePicker(current: HostClickConnectMode, onPick: (HostClickConnectMode) -> Unit) {
    DropdownField(current, HostClickConnectMode.entries, label = { it.hostConnectModeLabel() }, onPick = onPick)
}

/** "10000" -> "10 000" (space between thousands) for readable line counts. */
internal fun formatScrollback(lines: Int): String =
    lines.toString().reversed().chunked(3).joinToString(" ").reversed()
