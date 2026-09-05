package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_badge_active
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.theme_blackwater
import app.skerry.ui.generated.resources.theme_catppuccin_mocha
import app.skerry.ui.generated.resources.theme_dracula
import app.skerry.ui.generated.resources.theme_gruvbox_dark
import app.skerry.ui.generated.resources.theme_solarized_light
import app.skerry.ui.generated.resources.theme_tokyo_day
import app.skerry.ui.generated.resources.theme_tokyo_night
import app.skerry.ui.generated.resources.theme_dark
import app.skerry.ui.generated.resources.theme_light
import app.skerry.ui.generated.resources.theme_system
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_theme
import app.skerry.ui.generated.resources.appearance_custom_term_theme
import app.skerry.ui.generated.resources.appearance_custom_term_theme_desc
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.settings_hide_system_bars
import app.skerry.ui.generated.resources.settings_hide_system_bars_desc
import app.skerry.ui.generated.resources.settings_terminal_autofit
import app.skerry.ui.generated.resources.settings_terminal_autofit_desc
import app.skerry.ui.generated.resources.settings_terminal_open_paths
import app.skerry.ui.generated.resources.settings_terminal_open_paths_desc_mobile
import app.skerry.ui.generated.resources.settings_terminal_highlight_input
import app.skerry.ui.generated.resources.settings_terminal_highlight_input_desc
import app.skerry.ui.generated.resources.settings_terminal_highlight_output
import app.skerry.ui.generated.resources.settings_terminal_highlight_output_desc
import app.skerry.ui.generated.resources.settings_terminal_clipboard_write
import app.skerry.ui.generated.resources.settings_terminal_clipboard_write_desc
import app.skerry.ui.generated.resources.settings_terminal_sudo_password
import app.skerry.ui.generated.resources.settings_terminal_sudo_password_desc
import app.skerry.ui.generated.resources.settings_terminal_cursor_style
import app.skerry.ui.generated.resources.settings_terminal_scrollback
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
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
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Badge
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.settings.cursorStyleLabel
import app.skerry.ui.settings.formatDecimal
import app.skerry.ui.settings.formatScrollback
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.palette
import app.skerry.ui.theme.systemInDarkTheme
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.generated.resources.settings_terminal_prod_warnings
import app.skerry.ui.generated.resources.settings_terminal_prod_warnings_desc

/**
 * More -> Appearance push screen: terminal theme picker (cards), font, and size. Both fonts
 * render without ligatures (see [app.skerry.ui.terminal.TerminalAppearance]).
 */
@Composable
fun MobileAppearanceScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobilePushHeader(stringResource(Res.string.appearance_title), onBack = state::pop)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(stringResource(Res.string.appearance_section_terminal), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            FontSettingRow(stringResource(Res.string.appearance_font)) {
                MobileFontPicker(state.terminalFont, onPick = state::chooseTerminalFont)
            }
            HLine()
            MobileStepperRow(
                label = stringResource(Res.string.appearance_font_size),
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
            MobileStepperRow(
                label = stringResource(Res.string.appearance_line_height),
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
            MobileStepperRow(
                label = stringResource(Res.string.appearance_letter_spacing),
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
            // Shrink-to-fit, right below the size sliders it overrides. Phone-only (there is no
            // desktop counterpart) and off by default: a font size chosen by hand should not be
            // rewritten by the first wide line unless the user asked for it.
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_autofit),
                desc = stringResource(Res.string.settings_terminal_autofit_desc),
                on = state.terminalAutoFit,
                onToggle = state::toggleTerminalAutoFit,
            )
            // Scrollback depth and default cursor style for new sessions (behaviour, desktop parity).
            // Both apply to new sessions at connect and are pushed live into already-open ones.
            HLine()
            FontSettingRow(stringResource(Res.string.settings_terminal_scrollback)) {
                MobileScrollbackPicker(state.terminalScrollback, onPick = state::chooseTerminalScrollback)
            }
            HLine()
            FontSettingRow(stringResource(Res.string.settings_terminal_cursor_style)) {
                MobileCursorStylePicker(state.terminalCursorStyle, onPick = state::chooseTerminalCursorStyle)
            }
            // Clickable file paths in output (desktop parity): here the affordance is a chip over a
            // selected path rather than Ctrl+click, and this switch governs both.
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_open_paths),
                desc = stringResource(Res.string.settings_terminal_open_paths_desc_mobile),
                on = state.openFilePathsInSftp,
                onToggle = state::toggleOpenFilePathsInSftp,
            )
            // Client-side syntax highlighting (desktop parity): input on by default, output off.
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_highlight_input),
                desc = stringResource(Res.string.settings_terminal_highlight_input_desc),
                on = state.highlightCommandLine,
                onToggle = state::toggleHighlightCommandLine,
            )
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_highlight_output),
                desc = stringResource(Res.string.settings_terminal_highlight_output_desc),
                on = state.highlightOutput,
                onToggle = state::toggleHighlightOutput,
            )
            // OSC 52 clipboard-write gate (default off, like xterm/kitty): keeps an untrusted host
            // from silently overwriting the system clipboard. Applies to new and already-open sessions.
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_clipboard_write),
                desc = stringResource(Res.string.settings_terminal_clipboard_write_desc),
                on = state.allowServerClipboardWrite,
                onToggle = state::toggleAllowServerClipboardWrite,
            )
            // Saved password offered back at a sudo prompt (issue #360, desktop parity). Default
            // off: nothing is sent without an explicit Enter, but the prompt is only recognised
            // heuristically and the secret is the session's own credential.
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_sudo_password),
                desc = stringResource(Res.string.settings_terminal_sudo_password_desc),
                on = state.offerSudoPassword,
                onToggle = state::toggleOfferSudoPassword,
            )
            // Production guard threshold (desktop parity): dangerous commands always confirm, the
            // warnings on top of them are opt-in.
            HLine()
            MobileToggleRow(
                title = stringResource(Res.string.settings_terminal_prod_warnings),
                desc = stringResource(Res.string.settings_terminal_prod_warnings_desc),
                on = state.confirmProductionWarnings,
                onToggle = state::toggleConfirmProductionWarnings,
            )
            Txt(stringResource(Res.string.appearance_section_interface), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            FontSettingRow(stringResource(Res.string.appearance_language)) {
                MobileLanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage)
            }
            HLine()
            // Whether a session takes the phone's status/navigation bars with it. Off by default —
            // they are the phone's, not ours; on, a swipe from the edge brings them back.
            MobileToggleRow(
                title = stringResource(Res.string.settings_hide_system_bars),
                desc = stringResource(Res.string.settings_hide_system_bars_desc),
                on = state.hideSessionSystemBars,
                onToggle = state::toggleHideSessionSystemBars,
            )
            HLine()
            // App theme cards in a 2xN grid — the chrome counterpart of the terminal cards above.
            Txt(stringResource(Res.string.appearance_section_theme), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            val systemDark = systemInDarkTheme(enabled = true)
            ThemeMode.entries.chunked(2).forEach { rowModes ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in rowModes) {
                        MobileAppThemeCard(
                            mode = mode,
                            active = mode == state.themeMode,
                            systemDark = systemDark,
                            onClick = { state.chooseThemeMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowModes.size == 1) Box(Modifier.weight(1f))
                }
            }
            // Unified theming: the terminal follows the app theme's twin unless this opt-in is
            // set, which reveals the separate terminal-theme cards.
            HLine(modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(Res.string.appearance_custom_term_theme), color = Skerry.colors.text, size = 14.5.sp)
                    Txt(stringResource(Res.string.appearance_custom_term_theme_desc), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Toggle(
                    on = state.customTerminalTheme,
                    onToggle = state::toggleCustomTerminalTheme,
                    label = stringResource(Res.string.appearance_custom_term_theme),
                )
            }
            if (state.customTerminalTheme) {
                TerminalThemes.all.chunked(2).forEach { rowThemes ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (theme in rowThemes) {
                            MobileThemeCard(
                                theme = theme,
                                active = theme.id == state.terminalTheme.id,
                                onClick = { state.chooseTerminalTheme(theme) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowThemes.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Mobile terminal theme picker card: a mini `ls -la` preview in [theme]'s real colors; click
 * selects, active shows a cyan border + ACTIVE badge. Mirrors the desktop card from SettingsPanel.
 */
@Composable
private fun MobileThemeCard(
    theme: TerminalTheme,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = LocalFonts.current.mono
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(theme.background).padding(8.dp)) {
            Row { Txt("~ ", color = theme.ansi[2], size = 9.sp, font = mono); Txt("ls -la", color = theme.foreground, size = 9.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = theme.ansi[6], size = 9.sp, font = mono); Txt("src", color = theme.ansi[4], size = 9.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = theme.ansi[8], size = 9.sp, font = mono); Txt(".env", color = theme.ansi[3], size = 9.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(theme.displayName, color = Skerry.colors.text, size = 11.sp, weight = FontWeight.Medium, maxLines = 1)
            if (active) Badge(stringResource(Res.string.appearance_badge_active), bg = Skerry.colors.cyan14, fg = Skerry.colors.cyanBright, radius = 3, size = 8.sp)
        }
    }
}

/**
 * Mobile app theme card: a mini chrome mock (tab pills, a host row) in the mode's actual palette;
 * mirrors [MobileThemeCard] for terminal themes. The SYSTEM card previews the current OS side.
 */
@Composable
private fun MobileAppThemeCard(
    mode: ThemeMode,
    active: Boolean,
    systemDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = mode.palette(systemDark)
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(p.bg).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.cyan).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Txt("ssh", color = p.ink, size = 8.sp, weight = FontWeight.SemiBold)
                }
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.surface2).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Txt("sftp", color = p.dim, size = 8.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(p.moss)
                Txt("prod-web-01", color = p.text, size = 9.sp)
                Txt(":22", color = p.faint, size = 9.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(mode.themeLabel(), color = Skerry.colors.text, size = 11.sp, weight = FontWeight.Medium, maxLines = 1)
            if (active) Badge(stringResource(Res.string.appearance_badge_active), bg = Skerry.colors.cyan14, fg = Skerry.colors.cyanBright, radius = 3, size = 8.sp)
        }
    }
}

/** UI language dropdown (System / English / Russian). */
@Composable
private fun MobileLanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.label(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                UiLanguage.entries.forEach { option ->
                    MobileDropdownOption(option.label(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

@Composable
private fun ThemeMode.themeLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> Res.string.theme_system
        ThemeMode.LIGHT -> Res.string.theme_light
        ThemeMode.DARK -> Res.string.theme_dark
        ThemeMode.BLACKWATER -> Res.string.theme_blackwater
        ThemeMode.TOKYO_NIGHT -> Res.string.theme_tokyo_night
        ThemeMode.TOKYO_DAY -> Res.string.theme_tokyo_day
        ThemeMode.CATPPUCCIN_MOCHA -> Res.string.theme_catppuccin_mocha
        ThemeMode.GRUVBOX_DARK -> Res.string.theme_gruvbox_dark
        ThemeMode.DRACULA -> Res.string.theme_dracula
        ThemeMode.SOLARIZED_LIGHT -> Res.string.theme_solarized_light
    }
)

/** Setting row: label on the left + a fixed-width control (dropdown) on the right. */
@Composable
private fun FontSettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(label, color = Skerry.colors.text, size = 14.5.sp, modifier = Modifier.weight(1f))
        Box(Modifier.width(180.dp)) { control() }
    }
}

/** Terminal font dropdown (Hack / JetBrains Mono) — both without ligatures. */
@Composable
private fun MobileFontPicker(current: TerminalFont, onPick: (TerminalFont) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.displayName, onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TerminalFont.entries.forEach { option ->
                    MobileDropdownOption(option.displayName, selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Scrollback-depth dropdown ([TERMINAL_SCROLLBACK_OPTIONS], lines; formatted as "10 000"). */
@Composable
private fun MobileScrollbackPicker(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(formatScrollback(current), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TERMINAL_SCROLLBACK_OPTIONS.forEach { option ->
                    MobileDropdownOption(formatScrollback(option), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Cursor-style dropdown (shape × blink, [TerminalCursorStyle.entries]). */
@Composable
private fun MobileCursorStylePicker(current: TerminalCursorStyle, onPick: (TerminalCursorStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.cursorStyleLabel(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TerminalCursorStyle.entries.forEach { option ->
                    MobileDropdownOption(option.cursorStyleLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/**
 * One settings switch on mobile: title, secondary line, toggle. The same row shape appears for every
 * behaviour setting on this screen, so it lives here once — the desktop twin is
 * [app.skerry.ui.settings.SettingToggleRow], which differs only in its type scale.
 */
@Composable
private fun MobileToggleRow(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
            Txt(desc, color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Toggle(on = on, onToggle = onToggle, label = title)
    }
}
