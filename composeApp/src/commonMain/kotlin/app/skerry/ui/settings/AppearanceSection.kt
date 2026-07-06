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
import app.skerry.ui.design.Badge
import app.skerry.ui.design.D
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_badge_active
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
import app.skerry.ui.generated.resources.appearance_recent_count
import app.skerry.ui.generated.resources.appearance_recent_show
import app.skerry.ui.generated.resources.appearance_recent_show_desc
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.appearance_subtitle
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

// Appearance section: terminal themes, font/metrics, UI language, RECENT section.

@Composable
internal fun AppearanceSection(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    SectionTitle(stringResource(Res.string.appearance_title), stringResource(Res.string.appearance_subtitle))
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
    // control on the right. Size/line-height/spacing use a numeric stepper for precise input.
    SectionLabel(stringResource(Res.string.appearance_section_terminal))
    SettingRow(label = stringResource(Res.string.appearance_font)) {
        Box(Modifier.width(180.dp)) { FontPicker(state.terminalFont, onPick = state::chooseTerminalFont) }
    }
    SettingRow(
        label = stringResource(Res.string.appearance_font_size),
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
    SectionLabel(stringResource(Res.string.appearance_section_interface))
    SettingRow(label = stringResource(Res.string.appearance_language)) {
        Box(Modifier.width(180.dp)) { LanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage) }
    }
    // RECENT section in the sidebar: whether to show it and how many hosts (stepper only when enabled).
    SettingToggleRow(
        stringResource(Res.string.appearance_recent_show),
        stringResource(Res.string.appearance_recent_show_desc),
        state.showRecent,
        { state.setRecentVisible(!state.showRecent) },
    )
    if (state.showRecent) {
        SettingRow(
            label = stringResource(Res.string.appearance_recent_count),
            hasHint = true,
            isDefault = state.recentLimit == DesktopDesignState.MAX_RECENT_HOSTS,
            defaultText = DesktopDesignState.MAX_RECENT_HOSTS.toString(),
            onReset = { state.chooseRecentLimit(DesktopDesignState.MAX_RECENT_HOSTS) },
        ) {
            NumberStepper(
                value = state.recentLimit.toFloat(),
                onValueChange = { state.chooseRecentLimit(it.roundToInt()) },
                step = 1f,
                format = { it.roundToInt().toString() },
                parse = { it.trim().toIntOrNull()?.toFloat() },
                fieldWidth = 52.dp,
            )
        }
    }
}

/**
 * Full-width setting row: label on the left, optional ([hasHint]) default-value hint with quick
 * reset below it; control ([NumberStepper]/dropdown) on the right of the same line.
 */
@Composable
private fun SettingRow(
    label: String,
    hasHint: Boolean = false,
    isDefault: Boolean = true,
    defaultText: String = "",
    onReset: () -> Unit = {},
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Txt(label, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            if (hasHint) DefaultValueHint(isDefault, defaultText, onReset)
        }
        control()
    }
}

/** Settings group heading: small caps in a muted color, top padding to separate sections. */
@Composable
private fun SectionLabel(text: String) {
    Txt(
        text,
        color = D.faint,
        size = 11.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

/**
 * Default-value hint: static gray text when the value is already default; a clickable cyan row
 * with a reset icon when changed (click restores [defaultText] via [onReset]).
 */
@Composable
private fun DefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
    } else {
        Row(
            Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Sym("restart_alt", size = 13.sp, color = D.cyan)
            Txt(text, color = D.cyan, size = 11.sp)
        }
    }
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

/** UI language dropdown (System / English / Russian). */
@Composable
private fun LanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    DropdownField(current, UiLanguage.entries, label = { it.label() }, onPick = onPick)
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
