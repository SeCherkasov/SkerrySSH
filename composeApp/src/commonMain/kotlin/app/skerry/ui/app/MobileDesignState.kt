package app.skerry.ui.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.host.Host
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.clampTerminalLetterSpacing
import app.skerry.ui.terminal.clampTerminalLineHeight
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.session.BroadcastController
import app.skerry.ui.snippet.SnippetLibraryState
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.shared.terminal.Asciicast
import app.skerry.ui.terminal.CastOpenResult
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.terminal.TerminalThemes

/**
 * Bottom navigation — exactly 4 root tabs ([showTabs]=true). [icon] is a Material Symbols ligature
 * (see [Sym]), aligned with the desktop rail ([RAIL]) where the section matches (Snippets/Vault).
 * No Files tab: SFTP opens as a push screen ([MobileRoute.Files]) from a host card's SFTP button —
 * a separate root tab would duplicate the terminal.
 */
// Labels are not part of the enum: they are localized in the tab bar (nav_tab_* resources).
enum class MobileTab(val icon: String) {
    Hosts("dns"),
    Snippets("code_blocks"),
    Vault("vpn_key"),
    More("more_horiz"),
}

/**
 * Full-screen push screens over the tab navigation (tab bar hidden): terminal and host detail open
 * from Hosts, SFTP (Files) via the host card's SFTP button, and Ports/Known/Team from the More tab.
 */
enum class MobileRoute { Terminal, Vnc, Files, HostDetail, Ports, Known, Team, Appearance, Sync, Ai, Security, About }

/** What the system back gesture should do in the mobile shell. */
enum class MobileBackAction {
    /** Close the open full-screen push screen (return to the underlying tab). */
    PopRoute,

    /** Leave a non-root tab for the root Hosts tab. */
    GoHome,
}

/**
 * Decides what the system back gesture does based on shell navigation state. `null` means there is
 * nothing to intercept (root Hosts tab, no push screen open): the event is left to the system, which
 * closes the Activity (normal app exit). Open sheets/dialogs are not considered here — they intercept
 * their own back press (their own `BackHandler` layered above this one), so the event never reaches
 * navigation while they're open.
 */
fun mobileBackAction(route: MobileRoute?, tab: MobileTab): MobileBackAction? = when {
    route != null -> MobileBackAction.PopRoute
    tab != MobileTab.Hosts -> MobileBackAction.GoHome
    else -> null
}

/**
 * Mobile layout state — navigation (current tab + open push screen) and the New connection sheet
 * overlay. Pure UI state, mutators encapsulated (`private set`), as in
 * [app.skerry.ui.session.SessionsController]. Vault locking lives in `VaultGate`, not here.
 */
@Stable
class MobileDesignState(
    // Collapsed host-list folders (group names). Initial value is read from persistence at startup,
    // the callback writes it back — folder state survives restart. Defaults (all expanded, no-op)
    // preserve prior behavior for previews/tests.
    initialCollapsedGroups: Set<String> = emptySet(),
    private val onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Terminal font (More -> Appearance -> Font) and its size. Initial values are read from
    // persistence at startup, callbacks write back — the choice survives restart. Defaults
    // (Hack 13px, no-op) are for previews/tests.
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    private val onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    private val onTerminalFontSizeChange: (Int) -> Unit = {},
    // Terminal line height and letter spacing (More -> Appearance). Not yet persisted on mobile
    // (in-memory only) — callbacks default to no-op, like the other Appearance settings.
    initialTerminalLineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    private val onTerminalLineHeightChange: (Float) -> Unit = {},
    initialTerminalLetterSpacing: Float = DEFAULT_TERMINAL_LETTER_SPACING,
    private val onTerminalLetterSpacingChange: (Float) -> Unit = {},
    // Terminal color theme (More -> Appearance -> theme cards). Threaded to the terminal via
    // [app.skerry.ui.terminal.LocalTerminalTheme]; persisted per device (`terminal_theme`).
    initialTerminalTheme: TerminalTheme = TerminalThemes.DEFAULT,
    private val onTerminalThemeChange: (TerminalTheme) -> Unit = {},
    // Unified theming: by default the terminal follows the app theme's twin; this flag opts into
    // a separately-picked terminal theme ([terminalTheme]).
    initialCustomTerminalTheme: Boolean = false,
    private val onCustomTerminalThemeChange: (Boolean) -> Unit = {},
    // App theme (More -> Appearance). Default (night-sea dark, no-op) preserves the prior look.
    initialThemeMode: ThemeMode = ThemeMode.DEFAULT,
    private val onThemeModeChange: (ThemeMode) -> Unit = {},
    // Whether the server may write the system clipboard via OSC 52 (More -> Appearance -> Terminal).
    // Off by default (like xterm/kitty): an untrusted host must not silently overwrite the clipboard
    // until the user opts in. Snapshotted into new sessions via [app.skerry.ui.terminal.TerminalSessionPrefs]
    // and pushed live into open ones. Persisted on Android; no-op default for previews/tests.
    initialAllowServerClipboardWrite: Boolean = false,
    private val onAllowServerClipboardWriteChange: (Boolean) -> Unit = {},
    // UI language (More -> Appearance -> Language). Initial value is read from persistence at
    // startup, the callback writes it back — the choice survives restart. Defaults (System, no-op)
    // auto-detect from the OS locale for previews/tests.
    initialUiLanguage: UiLanguage = UiLanguage.DEFAULT,
    private val onUiLanguageChange: (UiLanguage) -> Unit = {},
    // Idle auto-lock threshold (More -> Security). Initial value from persistence, callback writes
    // back; threaded into [app.skerry.ui.vault.VaultGate] as the timer's idleMs.
    initialAutoLock: AutoLockDuration = AutoLockDuration.DEFAULT,
    private val onAutoLockChange: (AutoLockDuration) -> Unit = {},
    // Terminal behaviour (More -> Appearance -> Terminal): scrollback depth and default cursor style.
    // Read from persistence at startup, written back via the callbacks. Defaults (10,000 lines,
    // blinking block, no-op) are for previews/tests. Both apply to NEW sessions on connect (see
    // [app.skerry.ui.terminal.TerminalSessionPrefs]) and are also pushed live into already-open sessions.
    initialTerminalScrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    private val onTerminalScrollbackChange: (Int) -> Unit = {},
    initialTerminalCursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
    private val onTerminalCursorStyleChange: (TerminalCursorStyle) -> Unit = {},
) {
    var tab: MobileTab by mutableStateOf(MobileTab.Hosts); private set
    var route: MobileRoute? by mutableStateOf(null); private set
    var sheetNewConn: Boolean by mutableStateOf(false); private set

    /**
     * Host open in the New connection sheet in edit mode (Edit from the detail screen), or `null`
     * for create mode. The sheet prefills the form from it ([NewConnectionFormState.fromHost]) and
     * keeps [Host.id] on save (parity with the desktop modal's `editHost` parameter).
     */
    var editingHost: Host? by mutableStateOf(null); private set

    /**
     * Host the New connection sheet is prefilled from as a copy ("Duplicate" from the detail
     * screen); saving creates a new profile (parity with the desktop modal's `duplicateOf`).
     */
    var duplicatingHost: Host? by mutableStateOf(null); private set

    /** Id of the host open on [MobileRoute.HostDetail] — the screen reads it from the store by id. */
    var selectedHostId: String? by mutableStateOf(null); private set

    /**
     * Whether a tab's modal overlay is open (e.g. a vault Generate/Import dialog) — hides the tab
     * bar, otherwise it floats over the dialog and covers input fields above the keyboard. Mutated
     * only via [modalOverlay] (encapsulation, like the other fields).
     */
    var modalOpen: Boolean by mutableStateOf(false); private set

    /** Mark whether the current tab's modal overlay is open (vault dialogs/detail sheet) — hides the tab bar. */
    fun modalOverlay(open: Boolean) { modalOpen = open }

    /** Tab bar is visible only on root screens with no modal open: push screens are full-screen. */
    val showTabs: Boolean get() = route == null && !modalOpen

    /** Switch the root tab — closes any open push screen and clears the selected host. */
    fun select(t: MobileTab) {
        tab = t
        route = null
        selectedHostId = null
    }

    /** Open a full-screen sub-screen over the current tab. */
    fun push(r: MobileRoute) { route = r }

    /** Open a specific host's detail (tap on a list row): remembers the id and pushes the screen. */
    fun openHost(id: String) {
        selectedHostId = id
        route = MobileRoute.HostDetail
    }

    /** Return from a push screen to the current tab (shell back arrow); clears the selected host. */
    fun pop() {
        route = null
        selectedHostId = null
    }

    /**
     * Which sessions a broadcast addresses. Held here, not in the sheet, so a selection survives
     * closing and reopening it (see [app.skerry.ui.session.BroadcastController]).
     */
    val broadcast = BroadcastController()

    /**
     * View state of the snippet library (search, category chip, collapsed sections). Lives here so
     * switching tabs doesn't reset the view; not persisted across restarts (see
     * [app.skerry.ui.snippet.SnippetLibraryState]).
     */
    val snippetLibrary = SnippetLibraryState()

    /** Recording being played back over the app, or `null` when the player is closed. */
    var castRecording: Asciicast? by mutableStateOf(null); private set

    /** Whether the last picked file turned out not to be a recording (shown as a notice). */
    var castInvalid: Boolean by mutableStateOf(false); private set

    fun showCast(result: CastOpenResult) {
        when (result) {
            is CastOpenResult.Loaded -> castRecording = result.cast
            CastOpenResult.Invalid -> castInvalid = true
            CastOpenResult.Cancelled -> Unit // the user backed out; nothing to report
        }
    }

    fun closeCast() { castRecording = null }

    fun dismissCastError() { castInvalid = false }

    /** Names of collapsed host-list folders (their host list is hidden). */
    var collapsedGroups: Set<String> by mutableStateOf(initialCollapsedGroups); private set

    /** Whether folder [name] is collapsed (its host list is hidden). */
    fun isGroupCollapsed(name: String): Boolean = name in collapsedGroups

    /** Collapse/expand folder [name] and report the new set outward (for persistence). */
    fun toggleGroupCollapsed(name: String) {
        collapsedGroups = if (name in collapsedGroups) collapsedGroups - name else collapsedGroups + name
        onCollapsedGroupsChange(collapsedGroups)
    }

    /**
     * Name of the group open in the "Rename group" dialog (pencil next to the folder header), or
     * `null` if closed. [app.skerry.ui.host.HostManagerController] renames/deletes profiles; this
     * store only syncs the collapsed-state side channel ([onGroupRenamed]/[onGroupDeleted]).
     */
    var renamingGroup: String? by mutableStateOf(null); private set

    /** Open the rename dialog for group [name] (pencil next to the folder header). */
    fun openRenameGroup(name: String) { renamingGroup = name }

    /** Close the group rename dialog. */
    fun dismissRenameGroup() { renamingGroup = null }

    /**
     * Sync collapsed state when a group is renamed [old]->[new] (the controller renames profiles):
     * a collapsed folder stays collapsed under the new name. Name is trimmed; empty/unchanged is a
     * no-op. The persistence callback fires only on an actual change.
     */
    fun onGroupRenamed(old: String, new: String) {
        val n = new.trim().filterNot { it == '\n' || it == '\r' }
        if (n.isEmpty() || n == old) return
        if (old in collapsedGroups) {
            collapsedGroups = collapsedGroups - old + n
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Sync collapsed state when group [name] is deleted (the controller ungroups its profiles). */
    fun onGroupDeleted(name: String) {
        if (name in collapsedGroups) {
            collapsedGroups = collapsedGroups - name
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Open the sheet in create-new-host mode (empty form). */
    fun openNewConn() { editingHost = null; duplicatingHost = null; sheetNewConn = true }

    /** Open the sheet in edit mode for [host] (form prefilled, save keeps its id). */
    fun openEditConn(host: Host) { editingHost = host; duplicatingHost = null; sheetNewConn = true }

    /** Open the sheet prefilled as a copy of [host] (save creates a new profile). */
    fun openDuplicateConn(host: Host) { editingHost = null; duplicatingHost = host; sheetNewConn = true }

    fun closeSheet() { sheetNewConn = false; editingHost = null; duplicatingHost = null }

    /** Selected terminal font (More -> Appearance -> Font). Threaded to the terminal via [app.skerry.ui.terminal.LocalTerminalAppearance]. */
    var terminalFont: TerminalFont by mutableStateOf(initialTerminalFont); private set

    /** Terminal font size, px (More -> Appearance -> Font size). */
    var terminalFontSize: Int by mutableStateOf(initialTerminalFontSize); private set

    /** Terminal line-height multiplier (More -> Appearance -> Line height). */
    var terminalLineHeight: Float by mutableStateOf(initialTerminalLineHeight); private set

    /** Terminal letter spacing, sp (More -> Appearance -> Letter spacing). */
    var terminalLetterSpacing: Float by mutableStateOf(initialTerminalLetterSpacing); private set

    /** Terminal theme (More -> Appearance -> cards). Threaded via [app.skerry.ui.terminal.LocalTerminalTheme]. */
    var terminalTheme: TerminalTheme by mutableStateOf(initialTerminalTheme); private set

    /** Whether the terminal theme is picked separately instead of following the app theme. */
    var customTerminalTheme: Boolean by mutableStateOf(initialCustomTerminalTheme); private set

    /** App theme (More -> Appearance). Threaded into [app.skerry.ui.theme.SkerryTheme] at the root. */
    var themeMode: ThemeMode by mutableStateOf(initialThemeMode); private set

    /**
     * Whether a server may write the system clipboard via OSC 52 (More -> Appearance -> Terminal).
     * Off by default; snapshotted into new sessions and pushed live into open ones.
     */
    var allowServerClipboardWrite: Boolean by mutableStateOf(initialAllowServerClipboardWrite); private set

    /** UI language (More -> Appearance -> Language). Threaded to the root via [app.skerry.ui.i18n.AppLocaleProvider]. */
    var uiLanguage: UiLanguage by mutableStateOf(initialUiLanguage); private set

    /** Idle auto-lock threshold (More -> Security). Threaded into [app.skerry.ui.vault.VaultGate]. */
    var autoLock: AutoLockDuration by mutableStateOf(initialAutoLock); private set

    /** Scrollback depth for new sessions, lines (More -> Appearance -> Terminal). Applies to new sessions. */
    var terminalScrollback: Int by mutableStateOf(initialTerminalScrollback); private set

    /** Default cursor style (More -> Appearance -> Terminal). Applies to new sessions. */
    var terminalCursorStyle: TerminalCursorStyle by mutableStateOf(initialTerminalCursorStyle); private set

    /** Choose the auto-lock threshold and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseAutoLock(duration: AutoLockDuration) {
        if (duration == autoLock) return
        autoLock = duration
        onAutoLockChange(duration)
    }

    /** Choose the terminal font and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseTerminalFont(font: TerminalFont) {
        if (font == terminalFont) return
        terminalFont = font
        onTerminalFontChange(font)
    }

    /** Choose the terminal theme and report outward. Repeating the same value is a no-op. */
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

    /** Choose the app theme and report outward. Repeating the same value is a no-op. */
    fun chooseThemeMode(mode: ThemeMode) {
        if (mode == themeMode) return
        themeMode = mode
        onThemeModeChange(mode)
    }

    /** Toggle honoring server OSC 52 clipboard writes and report outward (for persistence). */
    fun toggleAllowServerClipboardWrite() {
        allowServerClipboardWrite = !allowServerClipboardWrite
        onAllowServerClipboardWriteChange(allowServerClipboardWrite)
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

    /** Set the line-height multiplier (clamped via [clampTerminalLineHeight]); equal value is a no-op. */
    fun chooseTerminalLineHeight(ratio: Float) {
        val v = clampTerminalLineHeight(ratio)
        if (v == terminalLineHeight) return
        terminalLineHeight = v
        onTerminalLineHeightChange(v)
    }

    /** Set the letter spacing (clamped via [clampTerminalLetterSpacing]); equal value is a no-op. */
    fun chooseTerminalLetterSpacing(sp: Float) {
        val v = clampTerminalLetterSpacing(sp)
        if (v == terminalLetterSpacing) return
        terminalLetterSpacing = v
        onTerminalLetterSpacingChange(v)
    }

    /** Choose the UI language and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseUiLanguage(language: UiLanguage) {
        if (language == uiLanguage) return
        uiLanguage = language
        onUiLanguageChange(language)
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

    /** Choose the cursor style and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseTerminalCursorStyle(style: TerminalCursorStyle) {
        if (style == terminalCursorStyle) return
        terminalCursorStyle = style
        onTerminalCursorStyleChange(style)
    }
}
