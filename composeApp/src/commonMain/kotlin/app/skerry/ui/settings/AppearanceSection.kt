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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.Badge
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Dot
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.appearance_badge_active
import app.skerry.ui.generated.resources.appearance_custom_term_theme
import app.skerry.ui.generated.resources.appearance_custom_term_theme_desc
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.palette
import app.skerry.ui.theme.systemInDarkTheme
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_recent_count
import app.skerry.ui.generated.resources.appearance_recent_show
import app.skerry.ui.generated.resources.appearance_recent_show_desc
import app.skerry.ui.generated.resources.appearance_subtitle
import app.skerry.ui.generated.resources.appearance_section_theme
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
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import app.skerry.ui.theme.ThemeMode
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

// Appearance section: UI language and the RECENT section of the sidebar. Everything
// terminal-specific (theme, font, metrics) lives in [TerminalSection].

@Composable
internal fun AppearanceSection(state: DesktopDesignState) {
    SectionSubtitle(stringResource(Res.string.appearance_subtitle))
    SettingRow(label = stringResource(Res.string.appearance_language)) {
        Box(Modifier.width(180.dp)) { LanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage) }
    }
    // RECENT section in the sidebar: whether to show it and how many hosts. The count is a
    // sub-setting of the toggle (indented, attached right below) so it reads as one block.
    SettingToggleRow(
        stringResource(Res.string.appearance_recent_show),
        stringResource(Res.string.appearance_recent_show_desc),
        state.showRecent,
        { state.setRecentVisible(!state.showRecent) },
    )
    if (state.showRecent) {
        SettingRow(
            label = stringResource(Res.string.appearance_recent_count),
            modifier = Modifier.padding(start = 14.dp),
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
    // App theme cards in a 2×N grid, like the terminal theme cards: each renders a mini chrome
    // mock in its own palette. The OS flag resolves the SYSTEM card's preview (and keeps it live).
    SectionLabel(stringResource(Res.string.appearance_section_theme))
    val systemDark = systemInDarkTheme(enabled = true)
    ThemeMode.entries.chunked(2).forEachIndexed { rowIndex, rowModes ->
        if (rowIndex > 0) Box(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (mode in rowModes) {
                AppThemeCard(
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
    Box(Modifier.height(10.dp))
    // Unified theming: the terminal follows the app theme's twin unless this opt-in is set, which
    // reveals the separate terminal-theme cards (moved here from the Terminal section).
    SettingToggleRow(
        stringResource(Res.string.appearance_custom_term_theme),
        stringResource(Res.string.appearance_custom_term_theme_desc),
        state.customTerminalTheme,
        { state.toggleCustomTerminalTheme() },
    )
    if (state.customTerminalTheme) {
        val mono = LocalFonts.current.mono
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
                if (rowThemes.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** UI language dropdown (System / English / Russian). */
@Composable
private fun LanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    DropdownField(current, UiLanguage.entries, label = { it.label() }, onPick = onPick)
}

/**
 * App theme card: a mini chrome mock (tab pills, a host row) rendered in the mode's actual
 * palette, so the user sees the chrome before applying it — the counterpart of the terminal
 * theme card. The SYSTEM card previews whatever the OS resolves to right now.
 */
@Composable
private fun AppThemeCard(
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
            .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.cyan08, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(p.bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.cyan).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Txt("ssh", color = p.ink, size = 8.5.sp, weight = FontWeight.SemiBold)
                }
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.surface2).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Txt("sftp", color = p.dim, size = 8.5.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Dot(p.moss)
                Txt("prod-web-01", color = p.text, size = 9.5.sp)
                Txt(":22", color = p.faint, size = 9.5.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(mode.label(), color = Skerry.colors.text, size = 11.5.sp, weight = FontWeight.Medium, maxLines = 1)
            if (active) Badge(stringResource(Res.string.appearance_badge_active), bg = Skerry.colors.cyan14, fg = Skerry.colors.cyanBright, radius = 3, size = 9.sp)
        }
    }
}

/** Localized name for a theme mode. */
@Composable
private fun ThemeMode.label(): String = stringResource(
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
