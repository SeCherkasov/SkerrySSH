package app.skerry.ui.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.terminal.clampTerminalLetterSpacing
import app.skerry.ui.terminal.clampTerminalLineHeight
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.vault.AutoLockDuration

/**
 * The desktop app's persisted user preferences: everything Settings edits and the platform writes
 * back to storage. Split out of [DesktopDesignState], which was carrying navigation, overlays,
 * tabs and the demo terminal alongside these — a preference reaches the UI through
 * `state.settings.x` and changes through `state.settings.chooseX(...)`.
 *
 * Every initial value is read from persistence at startup and every mutator reports the new value
 * outward through its callback, which is where the write-back happens. Defaults (no-op callbacks)
 * are what mock, preview and test construction rely on.
 *
 * Repeating a value is a no-op everywhere: no state write, no callback, so persistence is not
 * touched by a settings screen re-emitting what it already shows.
 */
@Stable
class DesktopSettingsState(
    initialInfoPanel: Boolean = true,
    private val onInfoPanelChange: (Boolean) -> Unit = {},
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    private val onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    private val onTerminalFontSizeChange: (Int) -> Unit = {},
    initialTerminalLineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    private val onTerminalLineHeightChange: (Float) -> Unit = {},
    initialTerminalLetterSpacing: Float = DEFAULT_TERMINAL_LETTER_SPACING,
    private val onTerminalLetterSpacingChange: (Float) -> Unit = {},
    initialUiLanguage: UiLanguage = UiLanguage.DEFAULT,
    private val onUiLanguageChange: (UiLanguage) -> Unit = {},
    initialTerminalScrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    private val onTerminalScrollbackChange: (Int) -> Unit = {},
    initialTerminalCursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
    private val onTerminalCursorStyleChange: (TerminalCursorStyle) -> Unit = {},
    initialShowTerminalTitleOnTabs: Boolean = false,
    private val onShowTerminalTitleOnTabsChange: (Boolean) -> Unit = {},
    initialHostClickConnectMode: HostClickConnectMode = HostClickConnectMode.DEFAULT,
    private val onHostClickConnectModeChange: (HostClickConnectMode) -> Unit = {},
    initialAllowServerClipboardWrite: Boolean = false,
    private val onAllowServerClipboardWriteChange: (Boolean) -> Unit = {},
    initialReportTeamSessions: Boolean = true,
    private val onReportTeamSessionsChange: (Boolean) -> Unit = {},
    initialOpenFilePathsInSftp: Boolean = true,
    private val onOpenFilePathsInSftpChange: (Boolean) -> Unit = {},
    initialConfirmProductionWarnings: Boolean = false,
    private val onConfirmProductionWarningsChange: (Boolean) -> Unit = {},
    initialTerminalTheme: TerminalTheme = TerminalThemes.DEFAULT,
    private val onTerminalThemeChange: (TerminalTheme) -> Unit = {},
    initialCustomTerminalTheme: Boolean = false,
    private val onCustomTerminalThemeChange: (Boolean) -> Unit = {},
    initialThemeMode: ThemeMode = ThemeMode.DEFAULT,
    private val onThemeModeChange: (ThemeMode) -> Unit = {},
    initialLocalShellPath: String = "",
    private val onLocalShellPathChange: (String) -> Unit = {},
    initialAutoLock: AutoLockDuration = AutoLockDuration.DEFAULT,
    private val onAutoLockChange: (AutoLockDuration) -> Unit = {},
    initialShowRecent: Boolean = true,
    private val onShowRecentChange: (Boolean) -> Unit = {},
    initialRecentLimit: Int = MAX_RECENT_HOSTS,
    private val onRecentLimitChange: (Int) -> Unit = {},
) {
    /** Whether the host info panel is shown beside the work area. */
    var infoPanel: Boolean by mutableStateOf(initialInfoPanel); private set

    /** Selected terminal font (Appearance → Font). Threaded via [app.skerry.ui.terminal.LocalTerminalAppearance]. */
    var terminalFont: TerminalFont by mutableStateOf(initialTerminalFont); private set

    /** Terminal font size, px (Appearance → Font size). */
    var terminalFontSize: Int by mutableStateOf(initialTerminalFontSize); private set

    /** Terminal line height multiplier (Appearance → Line height). */
    var terminalLineHeight: Float by mutableStateOf(initialTerminalLineHeight); private set

    /** Terminal letter spacing, sp (Appearance → Letter spacing). */
    var terminalLetterSpacing: Float by mutableStateOf(initialTerminalLetterSpacing); private set

    /** Terminal theme (Appearance → cards). Threaded via [app.skerry.ui.terminal.LocalTerminalTheme]. */
    var terminalTheme: TerminalTheme by mutableStateOf(initialTerminalTheme); private set

    /** Whether the terminal theme is picked separately instead of following the app theme. */
    var customTerminalTheme: Boolean by mutableStateOf(initialCustomTerminalTheme); private set

    /** App theme (Settings → Appearance). Threaded into [app.skerry.ui.theme.SkerryTheme] at the root. */
    var themeMode: ThemeMode by mutableStateOf(initialThemeMode); private set

    /** Idle auto-lock threshold (Settings → Security). Threaded into [app.skerry.ui.vault.VaultGate]. */
    var autoLock: AutoLockDuration by mutableStateOf(initialAutoLock); private set

    /** UI language (Appearance → Language). Threaded to the root via [app.skerry.ui.i18n.AppLocaleProvider]. */
    var uiLanguage: UiLanguage by mutableStateOf(initialUiLanguage); private set

    /** Scrollback depth for new sessions, lines (Terminal → Scrollback buffer). Applies to new sessions. */
    var terminalScrollback: Int by mutableStateOf(initialTerminalScrollback); private set

    /** Default cursor style (Terminal → Cursor style). Applies to new sessions. */
    var terminalCursorStyle: TerminalCursorStyle by mutableStateOf(initialTerminalCursorStyle); private set

    /** Whether to show the live OSC title on terminal tabs (Terminal → Show title on tabs). */
    var showTerminalTitleOnTabs: Boolean by mutableStateOf(initialShowTerminalTitleOnTabs); private set

    /** How a host row connects: single or double click (Terminal → Behavior). Desktop-only. */
    var hostClickConnectMode: HostClickConnectMode by mutableStateOf(initialHostClickConnectMode); private set

    /**
     * Whether a server may write the system clipboard via OSC 52 (Terminal → Allow server clipboard
     * write). Off by default; snapshotted into new sessions and pushed live into open ones.
     */
    var allowServerClipboardWrite: Boolean by mutableStateOf(initialAllowServerClipboardWrite); private set

    /**
     * Whether opening a session on a host **shared with a team** is reported to that team's activity
     * feed (Security → Report sessions on shared hosts). On by default: a host somebody shared into a
     * team is shared infrastructure, and the feed is only useful if it is not full of holes. Never
     * covers hosts of one's own — those are reported nowhere regardless of this setting.
     */
    var reportTeamSessions: Boolean by mutableStateOf(initialReportTeamSessions); private set

    /**
     * Whether file paths printed in terminal output are clickable and open in the SFTP panel
     * (Terminal → Open file paths in SFTP). On by default; off is the way out for anyone whose
     * output makes the Ctrl+hover highlight a distraction.
     */
    var openFilePathsInSftp: Boolean by mutableStateOf(initialOpenFilePathsInSftp); private set

    /**
     * Whether the production guard also confirms [app.skerry.shared.ai.CommandRisk.Warn] commands
     * (Terminal → Confirm warnings on production). Off by default: `sudo` is a warning and makes up
     * half of what is typed on a production box, so asking every time turns the dialog into a
     * reflex. Dangerous commands are confirmed regardless.
     */
    var confirmProductionWarnings: Boolean by mutableStateOf(initialConfirmProductionWarnings); private set

    /** Shell binary for the local shell (blank = system default). Edited in Settings → Terminal → Local shell. */
    var localShellPath: String by mutableStateOf(initialLocalShellPath); private set

    /** Whether to show the RECENT section in the sidebar (Settings → Appearance → Interface). */
    var showRecent: Boolean by mutableStateOf(initialShowRecent); private set

    /** How many recent hosts to display (1..[MAX_RECENT_HOSTS]); trims display only, not storage. */
    var recentLimit: Int by mutableStateOf(initialRecentLimit.coerceIn(1, MAX_RECENT_HOSTS)); private set

    /** Show/hide the host info panel and report outward (for persistence). */
    fun toggleInfoPanel() {
        infoPanel = !infoPanel
        onInfoPanelChange(infoPanel)
    }

    /** Choose the terminal font and report outward (for persistence). */
    fun chooseTerminalFont(font: TerminalFont) {
        if (font == terminalFont) return
        terminalFont = font
        onTerminalFontChange(font)
    }

    /** Choose the terminal theme and report outward (for persistence). */
    fun chooseTerminalTheme(theme: TerminalTheme) {
        if (theme == terminalTheme) return
        terminalTheme = theme
        onTerminalThemeChange(theme)
    }

    /** Toggle the separately-picked terminal theme and report outward (for persistence). */
    fun toggleCustomTerminalTheme() {
        customTerminalTheme = !customTerminalTheme
        onCustomTerminalThemeChange(customTerminalTheme)
    }

    /** Choose the app theme and report outward (for persistence). */
    fun chooseThemeMode(mode: ThemeMode) {
        if (mode == themeMode) return
        themeMode = mode
        onThemeModeChange(mode)
    }

    /** Choose the auto-lock threshold and report outward (for persistence). */
    fun chooseAutoLock(duration: AutoLockDuration) {
        if (duration == autoLock) return
        autoLock = duration
        onAutoLockChange(duration)
    }

    /** Choose the UI language and report outward (for persistence). */
    fun chooseUiLanguage(language: UiLanguage) {
        if (language == uiLanguage) return
        uiLanguage = language
        onUiLanguageChange(language)
    }

    /**
     * Set the terminal font size and report outward (for persistence). A value outside
     * [TERMINAL_FONT_SIZE_RANGE] or equal to the current one is a no-op (no write, no callback).
     */
    fun chooseTerminalFontSize(px: Int) {
        if (px == terminalFontSize || px !in TERMINAL_FONT_SIZE_RANGE) return
        terminalFontSize = px
        onTerminalFontSizeChange(px)
    }

    /**
     * Set the line-height multiplier, clamped/stepped via [clampTerminalLineHeight]. Equal to the
     * current value is a no-op (no write, no callback).
     */
    fun chooseTerminalLineHeight(ratio: Float) {
        val v = clampTerminalLineHeight(ratio)
        if (v == terminalLineHeight) return
        terminalLineHeight = v
        onTerminalLineHeightChange(v)
    }

    /**
     * Set the letter spacing, clamped/stepped via [clampTerminalLetterSpacing]. Equal to the
     * current value is a no-op (no write, no callback).
     */
    fun chooseTerminalLetterSpacing(sp: Float) {
        val v = clampTerminalLetterSpacing(sp)
        if (v == terminalLetterSpacing) return
        terminalLetterSpacing = v
        onTerminalLetterSpacingChange(v)
    }

    /**
     * Set the scrollback depth and report outward (for persistence). A value outside
     * [TERMINAL_SCROLLBACK_OPTIONS] or equal to the current one is a no-op (no write, no callback).
     * Applies to subsequent sessions.
     */
    fun chooseTerminalScrollback(lines: Int) {
        if (lines == terminalScrollback || lines !in TERMINAL_SCROLLBACK_OPTIONS) return
        terminalScrollback = lines
        onTerminalScrollbackChange(lines)
    }

    /** Choose the cursor style and report outward (for persistence). */
    fun chooseTerminalCursorStyle(style: TerminalCursorStyle) {
        if (style == terminalCursorStyle) return
        terminalCursorStyle = style
        onTerminalCursorStyleChange(style)
    }

    /** Toggle showing the terminal's live OSC title on tabs and report outward (for persistence). */
    fun toggleShowTerminalTitleOnTabs() {
        showTerminalTitleOnTabs = !showTerminalTitleOnTabs
        onShowTerminalTitleOnTabsChange(showTerminalTitleOnTabs)
    }

    /** Choose how host rows connect (single/double click) and report outward (for persistence). */
    fun chooseHostClickConnectMode(mode: HostClickConnectMode) {
        if (mode == hostClickConnectMode) return
        hostClickConnectMode = mode
        onHostClickConnectModeChange(mode)
    }

    /** Toggle confirming Warn-level commands on production hosts and report outward (for persistence). */
    fun toggleConfirmProductionWarnings() {
        confirmProductionWarnings = !confirmProductionWarnings
        onConfirmProductionWarningsChange(confirmProductionWarnings)
    }

    /** Toggle opening clicked file paths in the SFTP panel and report outward (for persistence). */
    fun toggleOpenFilePathsInSftp() {
        openFilePathsInSftp = !openFilePathsInSftp
        onOpenFilePathsInSftpChange(openFilePathsInSftp)
    }

    /** Toggle honoring server OSC 52 clipboard writes and report outward (for persistence). */
    fun toggleAllowServerClipboardWrite() {
        allowServerClipboardWrite = !allowServerClipboardWrite
        onAllowServerClipboardWriteChange(allowServerClipboardWrite)
    }

    /** Toggle reporting sessions on team-shared hosts and report outward (for persistence). */
    fun toggleReportTeamSessions() {
        reportTeamSessions = !reportTeamSessions
        onReportTeamSessionsChange(reportTeamSessions)
    }

    /** Set the local shell binary (trimmed; blank = system default) and report outward. */
    fun chooseLocalShellPath(path: String) {
        val normalized = path.trim()
        if (normalized == localShellPath) return
        localShellPath = normalized
        onLocalShellPathChange(normalized)
    }

    /** Show/hide the RECENT section and report outward (for persistence). */
    fun setRecentVisible(on: Boolean) {
        if (on == showRecent) return
        showRecent = on
        onShowRecentChange(on)
    }

    /**
     * Change the number of recent hosts shown (clamped to 1..[MAX_RECENT_HOSTS]) and report outward.
     * The same (already-clamped) value is a no-op: no mutation, no write.
     */
    fun chooseRecentLimit(n: Int) {
        val next = n.coerceIn(1, MAX_RECENT_HOSTS)
        if (next == recentLimit) return
        recentLimit = next
        onRecentLimitChange(next)
    }

    internal companion object {
        /** Max entries in the sidebar's RECENT section; oldest are evicted by new connections. */
        const val MAX_RECENT_HOSTS = 8
    }
}
