package app.skerry.ui.app

import app.skerry.shared.host.Host
import app.skerry.ui.host.HostSection
import app.skerry.ui.settings.SETTINGS_NAV
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TERMINAL_LETTER_SPACING_MIN
import app.skerry.ui.terminal.TERMINAL_LINE_HEIGHT_MAX
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.skerry.ui.theme.ThemeMode

/**
 * Preferences behaviour split out of [DesktopDesignStateTest] alongside the class it covers:
 * defaults, clamping, and the write-back callback firing exactly once per real change.
 */
class DesktopSettingsStateTest {
    @Test
    fun toggleOpenFilePathsInSftp_flips_and_reports() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopSettingsState(onOpenFilePathsInSftpChange = { seen += it })
        assertEquals(true, s.openFilePathsInSftp) // on by default
        s.toggleOpenFilePathsInSftp()
        assertEquals(false, s.openFilePathsInSftp)
        s.toggleOpenFilePathsInSftp()
        assertEquals(true, s.openFilePathsInSftp)
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun toggleHighlightCommandLine_flips_and_reports() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopSettingsState(onHighlightCommandLineChange = { seen += it })
        assertEquals(true, s.highlightCommandLine) // on by default: it only paints the user's own input
        s.toggleHighlightCommandLine()
        assertEquals(false, s.highlightCommandLine)
        s.toggleHighlightCommandLine()
        assertEquals(true, s.highlightCommandLine)
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun toggleHighlightOutput_flips_and_reports() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopSettingsState(onHighlightOutputChange = { seen += it })
        // Off by default: it repaints what the server printed, so it is opt-in.
        assertEquals(false, s.highlightOutput)
        s.toggleHighlightOutput()
        assertEquals(true, s.highlightOutput)
        s.toggleHighlightOutput()
        assertEquals(false, s.highlightOutput)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun toggleConfirmProductionWarnings_flips_and_reports() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopSettingsState(onConfirmProductionWarningsChange = { seen += it })
        // Off by default: sudo is a warning and half of what gets typed on a production box.
        assertFalse(s.confirmProductionWarnings)
        s.toggleConfirmProductionWarnings()
        assertTrue(s.confirmProductionWarnings)
        s.toggleConfirmProductionWarnings()
        assertFalse(s.confirmProductionWarnings)
        // Reported outward on every flip, or the setting would not survive a restart.
        assertEquals(listOf(true, false), seen)
        assertTrue(DesktopSettingsState(initialConfirmProductionWarnings = true).confirmProductionWarnings)
    }

    @Test
    fun recent_visibility_defaults_shown_full_cap() {
        val s = DesktopSettingsState()
        assertTrue(s.showRecent)
        assertEquals(DesktopSettingsState.MAX_RECENT_HOSTS, s.recentLimit)
    }

    @Test
    fun setRecentVisible_updates_and_reports_once() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopSettingsState(onShowRecentChange = { seen += it })
        s.setRecentVisible(false)
        s.setRecentVisible(false) // repeat call — no mutation, no callback
        assertFalse(s.showRecent)
        assertEquals(listOf(false), seen)
    }

    @Test
    fun chooseRecentLimit_coerces_into_range_and_reports() {
        val seen = mutableListOf<Int>()
        val s = DesktopSettingsState(onRecentLimitChange = { seen += it })
        s.chooseRecentLimit(3)
        s.chooseRecentLimit(99) // above the cap → clamped
        s.chooseRecentLimit(0)  // below 1 → 1
        s.chooseRecentLimit(1)  // already 1 — no-op
        assertEquals(1, s.recentLimit)
        assertEquals(listOf(3, DesktopSettingsState.MAX_RECENT_HOSTS, 1), seen)
    }

    @Test
    fun recentLimit_honours_initial_value_coerced() {
        assertEquals(2, DesktopSettingsState(initialRecentLimit = 2).recentLimit)
        assertEquals(DesktopSettingsState.MAX_RECENT_HOSTS, DesktopSettingsState(initialRecentLimit = 100).recentLimit)
        assertEquals(1, DesktopSettingsState(initialRecentLimit = 0).recentLimit)
    }

    @Test
    fun showRecent_honours_initial_value() {
        assertFalse(DesktopSettingsState(initialShowRecent = false).showRecent)
    }

    @Test
    fun terminal_font_defaults_to_hack_13px() {
        val s = DesktopSettingsState()
        assertEquals(TerminalFont.Hack, s.terminalFont)
        assertEquals(DEFAULT_TERMINAL_FONT_SIZE, s.terminalFontSize)
    }

    @Test
    fun terminal_font_honours_initial_values() {
        val s = DesktopSettingsState(initialTerminalFont = TerminalFont.JetBrainsMono, initialTerminalFontSize = 16)
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(16, s.terminalFontSize)
    }

    @Test
    fun setTerminalFont_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalFont>()
        val s = DesktopSettingsState(onTerminalFontChange = { seen += it })
        s.chooseTerminalFont(TerminalFont.JetBrainsMono)
        s.chooseTerminalFont(TerminalFont.JetBrainsMono) // repeat of the same value — no-op
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(listOf(TerminalFont.JetBrainsMono), seen)
    }

    @Test
    fun setTerminalTheme_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalTheme>()
        val s = DesktopSettingsState(onTerminalThemeChange = { seen += it })
        assertEquals(TerminalThemes.DEFAULT, s.terminalTheme) // default is Night Sea
        s.chooseTerminalTheme(TerminalThemes.GruvboxDark)
        s.chooseTerminalTheme(TerminalThemes.GruvboxDark) // repeat of the same theme — no-op
        assertEquals(TerminalThemes.GruvboxDark, s.terminalTheme)
        assertEquals(listOf(TerminalThemes.GruvboxDark), seen)
    }

    @Test
    fun setThemeMode_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<ThemeMode>()
        val s = DesktopSettingsState(onThemeModeChange = { seen += it })
        assertEquals(ThemeMode.DEFAULT, s.themeMode) // default is night-sea dark, preserving the prior look
        s.chooseThemeMode(ThemeMode.LIGHT)
        s.chooseThemeMode(ThemeMode.LIGHT) // repeat of the same mode — no-op
        assertEquals(ThemeMode.LIGHT, s.themeMode)
        assertEquals(listOf(ThemeMode.LIGHT), seen)
    }

    @Test
    fun setTerminalFontSize_updates_and_reports_skipping_repeat_and_out_of_range() {
        val seen = mutableListOf<Int>()
        val s = DesktopSettingsState(onTerminalFontSizeChange = { seen += it })
        s.chooseTerminalFontSize(16)
        s.chooseTerminalFontSize(16)   // repeat — no-op
        s.chooseTerminalFontSize(99)   // outside TERMINAL_FONT_SIZE_RANGE — no-op
        s.chooseTerminalFontSize(11)
        assertEquals(11, s.terminalFontSize)
        assertEquals(listOf(16, 11), seen)
    }

    @Test
    fun terminalFontSize_accepts_wide_range() {
        val s = DesktopSettingsState()
        s.chooseTerminalFontSize(TERMINAL_FONT_SIZE_MIN)
        assertEquals(TERMINAL_FONT_SIZE_MIN, s.terminalFontSize)
        s.chooseTerminalFontSize(TERMINAL_FONT_SIZE_MAX)
        assertEquals(TERMINAL_FONT_SIZE_MAX, s.terminalFontSize)
    }

    @Test
    fun setTerminalLineHeight_clamps_rounds_and_reports_skipping_repeat() {
        val seen = mutableListOf<Float>()
        val s = DesktopSettingsState(onTerminalLineHeightChange = { seen += it })
        assertEquals(DEFAULT_TERMINAL_LINE_HEIGHT, s.terminalLineHeight)
        s.chooseTerminalLineHeight(1.5f)
        s.chooseTerminalLineHeight(1.5f)   // repeat — no-op
        s.chooseTerminalLineHeight(5f)     // out of range → clamp to MAX
        assertEquals(TERMINAL_LINE_HEIGHT_MAX, s.terminalLineHeight)
        assertEquals(listOf(1.5f, TERMINAL_LINE_HEIGHT_MAX), seen)
    }

    @Test
    fun setTerminalLetterSpacing_clamps_rounds_and_reports_skipping_repeat() {
        val seen = mutableListOf<Float>()
        val s = DesktopSettingsState(onTerminalLetterSpacingChange = { seen += it })
        assertEquals(DEFAULT_TERMINAL_LETTER_SPACING, s.terminalLetterSpacing)
        s.chooseTerminalLetterSpacing(1f)
        s.chooseTerminalLetterSpacing(1f)  // repeat — no-op
        s.chooseTerminalLetterSpacing(-9f) // out of range → clamp to MIN
        assertEquals(TERMINAL_LETTER_SPACING_MIN, s.terminalLetterSpacing)
        assertEquals(listOf(1f, TERMINAL_LETTER_SPACING_MIN), seen)
    }

    @Test
    fun terminal_behaviour_honours_initial_values() {
        val s = DesktopSettingsState(
            initialTerminalScrollback = 50_000,
            initialTerminalCursorStyle = TerminalCursorStyle.BarSteady,
            initialShowTerminalTitleOnTabs = true,
        )
        assertEquals(50_000, s.terminalScrollback)
        assertEquals(TerminalCursorStyle.BarSteady, s.terminalCursorStyle)
        assertEquals(true, s.showTerminalTitleOnTabs)
    }

    @Test
    fun setTerminalScrollback_updates_and_reports_skipping_repeat_and_out_of_range() {
        val seen = mutableListOf<Int>()
        val s = DesktopSettingsState(onTerminalScrollbackChange = { seen += it })
        s.chooseTerminalScrollback(5_000)
        s.chooseTerminalScrollback(5_000)   // repeat — no-op
        s.chooseTerminalScrollback(1_234)   // outside TERMINAL_SCROLLBACK_OPTIONS — no-op
        s.chooseTerminalScrollback(1_000)
        assertEquals(1_000, s.terminalScrollback)
        assertEquals(listOf(5_000, 1_000), seen)
    }

    @Test
    fun setTerminalCursorStyle_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalCursorStyle>()
        val s = DesktopSettingsState(onTerminalCursorStyleChange = { seen += it })
        s.chooseTerminalCursorStyle(TerminalCursorStyle.UnderlineBlink)
        s.chooseTerminalCursorStyle(TerminalCursorStyle.UnderlineBlink) // repeat — no-op
        assertEquals(TerminalCursorStyle.UnderlineBlink, s.terminalCursorStyle)
        assertEquals(listOf(TerminalCursorStyle.UnderlineBlink), seen)
    }

    @Test
    fun toggleShowTerminalTitleOnTabs_flips_and_reports_each_change() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopSettingsState(onShowTerminalTitleOnTabsChange = { seen += it })
        assertEquals(false, s.showTerminalTitleOnTabs)
        s.toggleShowTerminalTitleOnTabs()
        s.toggleShowTerminalTitleOnTabs()
        assertEquals(false, s.showTerminalTitleOnTabs)
        assertEquals(listOf(true, false), seen)
    }
}
