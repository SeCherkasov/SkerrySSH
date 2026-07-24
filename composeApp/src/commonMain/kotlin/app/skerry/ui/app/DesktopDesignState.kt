package app.skerry.ui.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.host.Host
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.settings.SETTINGS_NAV
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.session.BroadcastController
import app.skerry.ui.session.SessionView
import app.skerry.ui.snippet.SnippetLibraryState
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.clampTerminalLetterSpacing
import app.skerry.ui.terminal.clampTerminalLineHeight
import app.skerry.shared.terminal.Asciicast
import app.skerry.ui.terminal.CastOpenResult
import app.skerry.ui.terminal.RecordingOutcome
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Left rail / top-level views of the layout. */
enum class DesktopView { Terminal, Sftp, Ports, Snippets, Vault, Known, Teams }

/**
 * App-level views not tied to a specific SSH session: Ports(Tunnels)/Snippets/Vault/Known/Teams.
 * They open "over" the tabs ([DesktopDesignState.appOverlay]) and are shared across the app, while
 * Terminal/SFTP are sub-views of the active tab ([app.skerry.ui.session.Session.view]).
 *
 * Tunnels is a global list of saved forwards: a tunnel is self-contained and opens its own
 * connection to the host, so the section is shared rather than part of an open session.
 */
val DesktopView.isAppLevel: Boolean
    get() = this == DesktopView.Ports || this == DesktopView.Snippets || this == DesktopView.Vault ||
        this == DesktopView.Known || this == DesktopView.Teams

/** Rail item → session sub-view; app-level/Terminal map to Terminal. */
fun DesktopView.asSessionView(): SessionView = when (this) {
    DesktopView.Sftp -> SessionView.Sftp
    else -> SessionView.Terminal
}

/** Session sub-view → rail item to highlight. */
fun SessionView.asDesktopView(): DesktopView = when (this) {
    SessionView.Terminal -> DesktopView.Terminal
    SessionView.Sftp -> DesktopView.Sftp
    // VNC and the recording player have no dedicated rail item (they're work-area views, like
    // Terminal); don't highlight one.
    SessionView.Vnc -> DesktopView.Terminal
    SessionView.Player -> DesktopView.Terminal
}

/** Settings panel tabs. */
enum class SettingsTab { AI, Sync, Security, Appearance, Terminal, Keyboard, About }

/**
 * Connection AI policy. Aliases the shared enum ([app.skerry.shared.host.Host.aiPolicy]) so the
 * modal's choice writes directly into the host profile.
 */
typealias AiPolicy = app.skerry.shared.ai.AiPolicy

/**
 * A destructive session action awaiting confirmation ([ConfirmActionDialog]). The action itself
 * (close/closeSplit) is performed by [DesktopChrome], which has access to the session manager.
 */
sealed interface PendingClose {
    /** Closing an entire session tab (power button in the toolbar). */
    data class Session(val id: String) : PendingClose

    /** Closing the split pane of tab [parentId] (close icon in the split header). */
    data class Split(val parentId: String) : PendingClose
}

/**
 * Open dialog for managing a sidebar host group: creating a new one ([Create]) or editing an
 * existing one by name ([Rename]). `null` in [DesktopDesignState.groupDialog] means no dialog.
 */
sealed interface GroupDialog {
    /** Creating a new (still empty) group. */
    data object Create : GroupDialog

    /** Editing group [name]: rename or delete (ungroups its hosts). */
    data class Rename(val name: String) : GroupDialog
}

/** Demo-tab status dot; resolved to a theme color at render time (state must stay theme-agnostic). */
enum class SessionDot { On, Warn, Off }

/** A session tab in the titlebar: host name + status dot. */
@Stable
data class SessionTab(val name: String, val dot: SessionDot)

/** A demo-terminal line: a command (with prompt) or output; [error] tints the output as a failure. */
@Stable
data class TermLine(val text: String, val isCmd: Boolean, val error: Boolean = false)

/**
 * UI state for the desktop app without a backend: demo terminal (`exec`) and toggles are stubs;
 * live functionality is wired in separately. Compose state via [mutableStateOf], mutators
 * encapsulated (`private set`), the same approach as [app.skerry.ui.session.SessionsController].
 */
@Stable
class DesktopDesignState(
    // Initial info-panel visibility (read from persistence at desktop startup) plus a callback for
    // changes (written back there too), so the user's choice survives a restart. Defaults preserve
    // prior behavior for mock/preview/tests.
    initialInfoPanel: Boolean = true,
    private val onInfoPanelChange: (Boolean) -> Unit = {},
    // Collapsed host folders in the sidebar (group names). Read from persistence at startup, written
    // back via the callback, so folder state survives a restart. Defaults (all expanded, no-op)
    // preserve prior behavior for mock/preview/tests.
    initialCollapsedGroups: Set<String> = emptySet(),
    private val onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Recent connections (RECENT section in the sidebar): host ids, newest first. Read from
    // persistence, written back via the callback, so the list survives a restart. Defaults (empty,
    // no-op) preserve prior behavior for mock/preview/tests.
    initialRecentHostIds: List<String> = emptyList(),
    private val onRecentHostIdsChange: (List<String>) -> Unit = {},
    // Custom host groups without profiles yet (created via the "+folder" button before a host is
    // dragged in). Groups with hosts are derived from [Host.group]; empty groups can't live there, so
    // they're kept by name here and persisted. Defaults (empty, no-op) are for mock/preview/tests.
    initialCustomGroups: List<String> = emptyList(),
    private val onCustomGroupsChange: (List<String>) -> Unit = {},
    // Terminal font (Appearance → Font) and its size. Read from persistence at startup, written back
    // via the callbacks. Defaults (Hack 13px, no-op) are for mock/preview/tests.
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    private val onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    private val onTerminalFontSizeChange: (Int) -> Unit = {},
    // Terminal line height (multiplier) and letter spacing (Appearance → Line height / Letter
    // spacing). Also persisted externally (desktop main). Defaults (18/13, 0, no-op) are for
    // mock/preview/tests.
    initialTerminalLineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    private val onTerminalLineHeightChange: (Float) -> Unit = {},
    initialTerminalLetterSpacing: Float = DEFAULT_TERMINAL_LETTER_SPACING,
    private val onTerminalLetterSpacingChange: (Float) -> Unit = {},
    // UI language (Appearance → Language). Read from persistence at startup, written back via the
    // callback. Defaults (System, no-op) preserve OS-locale auto-detection for mock/preview/tests.
    initialUiLanguage: UiLanguage = UiLanguage.DEFAULT,
    private val onUiLanguageChange: (UiLanguage) -> Unit = {},
    // Terminal settings (Settings → Terminal): scrollback depth, cursor style, and showing the live
    // OSC title on tabs. Read from persistence at startup, written back via the callbacks. Defaults
    // (10,000 lines, blinking block, title off, no-op) are for mock/preview/tests. The first two apply
    // to NEW sessions on connect (see [app.skerry.ui.terminal.TerminalSessionPrefs]) and are also
    // pushed live into already-open sessions.
    initialTerminalScrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    private val onTerminalScrollbackChange: (Int) -> Unit = {},
    initialTerminalCursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
    private val onTerminalCursorStyleChange: (TerminalCursorStyle) -> Unit = {},
    initialShowTerminalTitleOnTabs: Boolean = false,
    private val onShowTerminalTitleOnTabsChange: (Boolean) -> Unit = {},
    // Host-row click behavior (Settings → Terminal → Behavior): single click connects directly,
    // double click requires a second click (protects against accidental connects). Desktop-only.
    initialHostClickConnectMode: HostClickConnectMode = HostClickConnectMode.DEFAULT,
    private val onHostClickConnectModeChange: (HostClickConnectMode) -> Unit = {},
    // Whether the server may write the system clipboard via OSC 52 (Terminal → "Allow server
    // clipboard write"). Off by default (like xterm/kitty). Snapshotted into new sessions via
    // [app.skerry.ui.terminal.TerminalSessionPrefs] and pushed live into open ones.
    initialAllowServerClipboardWrite: Boolean = false,
    private val onAllowServerClipboardWriteChange: (Boolean) -> Unit = {},
    // Terminal color theme (Appearance → theme cards). Read from persistence at startup, written back
    // via the callback. Threaded into the terminal via [app.skerry.ui.terminal.LocalTerminalTheme] and
    // applied to open sessions live. Default (Night Sea, no-op) preserves the prior look for
    // mock/preview/tests.
    initialTerminalTheme: TerminalTheme = TerminalThemes.DEFAULT,
    private val onTerminalThemeChange: (TerminalTheme) -> Unit = {},
    // Unified theming: by default the terminal follows the app theme's twin; this flag opts into
    // a separately-picked terminal theme ([terminalTheme]). Persisted like the other appearance bits.
    initialCustomTerminalTheme: Boolean = false,
    private val onCustomTerminalThemeChange: (Boolean) -> Unit = {},
    // App theme (Settings → Appearance). Default (night-sea dark, no-op) preserves the prior look.
    initialThemeMode: ThemeMode = ThemeMode.DEFAULT,
    private val onThemeModeChange: (ThemeMode) -> Unit = {},
    // Idle auto-lock threshold (Settings → Security). Read from persistence, written back via the
    // callback; threaded into [app.skerry.ui.vault.VaultGate] as the timer's idleMs.
    initialAutoLock: AutoLockDuration = AutoLockDuration.DEFAULT,
    private val onAutoLockChange: (AutoLockDuration) -> Unit = {},
    // Visibility and size of the RECENT sidebar section (Settings → Appearance → Interface). Read
    // from persistence, written back via the callbacks. Defaults (shown, full cap) preserve prior
    // behavior for mock/preview/tests. [recentLimit] only trims the display: the recent-hosts store
    // still accumulates up to [MAX_RECENT_HOSTS], the limit applies at render time.
    initialShowRecent: Boolean = true,
    private val onShowRecentChange: (Boolean) -> Unit = {},
    initialRecentLimit: Int = MAX_RECENT_HOSTS,
    private val onRecentLimitChange: (Int) -> Unit = {},
) {
    // Session-level view (Terminal/SFTP/Ports): mock/preview fallback when there are no live
    // sessions; in live mode each tab holds its own sub-view ([app.skerry.ui.session.Session.view]).
    var view: DesktopView by mutableStateOf(DesktopView.Terminal); private set

    /**
     * App-level view open over the tabs (Vault/Known/Teams/Snippets), or `null` to show the active
     * tab's sub-view. These sections are shared across the app, so they're kept separate from [view]
     * and independent of which tab is active (see [DesktopView.isAppLevel]).
     */
    var appOverlay: DesktopView? by mutableStateOf(null); private set

    var locked: Boolean by mutableStateOf(false); private set
    var modalOpen: Boolean by mutableStateOf(false); private set

    /**
     * Outcome of the last finished session recording, shown as a notice; `null` when there is
     * nothing to report. A silent stop would leave the user unsure whether the file was written.
     */
    var recordingNotice: RecordingOutcome? by mutableStateOf(null); private set

    /** Recording being played back over the shell, or `null` when the player is closed. */
    var castRecording: Asciicast? by mutableStateOf(null); private set

    /** Whether the last picked file turned out not to be a recording (shown as a notice). */
    var castInvalid: Boolean by mutableStateOf(false); private set
    /** Whether the command palette (⌘K / Ctrl+Shift+K) is open over the active session. */
    var commandPaletteOpen: Boolean by mutableStateOf(false); private set

    /** Whether the broadcast panel (⌘B / Ctrl+Shift+B) is open. */
    var broadcastOpen: Boolean by mutableStateOf(false); private set

    /**
     * Which sessions a broadcast addresses. Lives here, not in the panel, so a selection survives
     * closing and reopening it — re-picking eight hosts for every command would make the feature
     * unusable.
     */
    val broadcast = BroadcastController()
    var settingsOpen: Boolean by mutableStateOf(false); private set

    /** Whether the sync setup onboarding modal is open (Settings → Sync → "Set up sync"). */
    var syncSetupOpen: Boolean by mutableStateOf(false); private set

    /** Whether the "Link a device" dialog is open (quick-pairing code/QR — Settings → Account). */
    var pairingOpen: Boolean by mutableStateOf(false); private set
    var settingsTab: SettingsTab by mutableStateOf(SETTINGS_NAV.first().tab); private set
    var split: Boolean by mutableStateOf(false); private set
    /** Whether the terminal's left host sidebar is hidden (toggled from the icon rail). */
    var sidebarHidden: Boolean by mutableStateOf(false); private set

    /**
     * Whether the VNC view's slide-over host drawer is open. Separate from [sidebarHidden]: the VNC
     * framebuffer wants the full work area, so its drawer overlays the render and defaults to closed
     * instead of reserving layout space like the terminal sidebar.
     */
    var vncSidebar: Boolean by mutableStateOf(false); private set
    var infoPanel: Boolean by mutableStateOf(initialInfoPanel); private set

    /**
     * View state of the snippet library (search, category chip, collapsed sections). Lives here so
     * leaving the Snippets section and coming back doesn't reset the view; not persisted across
     * restarts (see [app.skerry.ui.snippet.SnippetLibraryState]).
     */
    val snippetLibrary = SnippetLibraryState()

    /** Names of collapsed host folders in the sidebar (their host lists are hidden). */
    var collapsedGroups: Set<String> by mutableStateOf(initialCollapsedGroups); private set

    /** Ids of recently connected hosts, newest first (RECENT section in the sidebar). */
    var recentHostIds: List<String> by mutableStateOf(initialRecentHostIds); private set

    /** Whether to show the RECENT section in the sidebar (Settings → Appearance → Interface). */
    var showRecent: Boolean by mutableStateOf(initialShowRecent); private set

    /** How many recent hosts to display (1..[MAX_RECENT_HOSTS]); trims display only, not storage. */
    var recentLimit: Int by mutableStateOf(initialRecentLimit.coerceIn(1, MAX_RECENT_HOSTS)); private set

    /** Names of custom (still empty) host groups, shown as folders alongside host-derived ones. */
    var customGroups: List<String> by mutableStateOf(initialCustomGroups); private set

    /**
     * Replace the empty-folder list wholesale without writing back ([onCustomGroupsChange]). This is
     * an external load, not a user edit: called after vault unlock, once empty folders are read from
     * the synced layout record ([app.skerry.shared.vault.WorkspaceLayout]) — the list starts empty
     * while the vault is locked. Must not write back here, or it would clobber the synced value.
     */
    fun loadCustomGroups(groups: List<String>) {
        customGroups = groups
    }

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
    var hostClickConnectMode: HostClickConnectMode by mutableStateOf(initialHostClickConnectMode); private set

    /**
     * Whether a server may write the system clipboard via OSC 52 (Terminal → Allow server clipboard
     * write). Off by default; snapshotted into new sessions and pushed live into open ones.
     */
    var allowServerClipboardWrite: Boolean by mutableStateOf(initialAllowServerClipboardWrite); private set

    /** Open group management dialog (create/edit), or `null`. */
    var groupDialog: GroupDialog? by mutableStateOf(null); private set
    var selectedHost: String by mutableStateOf("prod-web-01"); private set

    /** Host sidebar search text (by name/address/user/group/tags). Empty means no filter. */
    var hostSearchQuery: String by mutableStateOf(""); private set
    var activeTab: Int by mutableStateOf(0); private set
    var modalPolicy: AiPolicy by mutableStateOf(AiPolicy.Strict); private set

    /** Host open in the modal for editing (null means the modal is in "New connection" mode). */
    var editingHost: Host? by mutableStateOf(null); private set

    /** Host the modal is prefilled from as a copy ("Duplicate"); saving creates a new profile. */
    var duplicatingHost: Host? by mutableStateOf(null); private set

    /** Host for which the delete-confirmation dialog is shown (null means no dialog). */
    var pendingDeleteHost: Host? by mutableStateOf(null); private set

    /** Destructive session action awaiting confirmation (null means no dialog). */
    var pendingClose: PendingClose? by mutableStateOf(null); private set

    var tabs: List<SessionTab> by mutableStateOf(
        listOf(
            SessionTab("prod-web-01", SessionDot.On),
            SessionTab("db-master", SessionDot.On),
            SessionTab("homelab-pi", SessionDot.Warn),
            SessionTab("staging-web", SessionDot.Off),
        ),
    )
        private set

    var sanitize: Boolean by mutableStateOf(true); private set
    var preview: Boolean by mutableStateOf(true); private set
    var confirm: Boolean by mutableStateOf(true); private set

    var cmd: String by mutableStateOf(""); private set
    var termLines: List<TermLine> by mutableStateOf(emptyList()); private set

    /**
     * Open a view from the rail: app-level (Vault/Known/Teams/Snippets) raises the overlay over the
     * tabs; session-level (Terminal/SFTP/Ports) clears the overlay and sets the sub-view (in live mode
     * the caller also sets it on the active tab).
     */
    fun showView(v: DesktopView) {
        if (v.isAppLevel) {
            appOverlay = v
        } else {
            appOverlay = null
            view = v
        }
    }

    /**
     * Clear the app overlay, returning to the active tab's sub-view without touching [view]. In live
     * mode the sub-view is held by [app.skerry.ui.session.Session.view], the source of truth; [view]
     * is only a mock/preview fallback and must not be overwritten when navigating with live sessions.
     */
    fun clearOverlay() { appOverlay = null }
    fun selectHost(name: String) { selectedHost = name }
    fun onHostSearch(value: String) { hostSearchQuery = value }
    fun setTab(i: Int) { if (i in tabs.indices) activeTab = i }

    /**
     * Close tab [i]: the active index is clamped into the new range (the neighbor on the right shifts
     * into the freed index, else the nearest one on the left, else 0).
     */
    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val next = tabs.toMutableList().apply { removeAt(i) }
        var a = activeTab
        if (a >= next.size) a = next.size - 1
        if (a < 0) a = 0
        tabs = next
        activeTab = a
    }

    fun lock() { locked = true; hostSearchQuery = "" }
    fun unlock() { locked = false }
    fun openModal() { editingHost = null; duplicatingHost = null; modalOpen = true }
    fun openEditModal(host: Host) { editingHost = host; duplicatingHost = null; modalOpen = true }
    fun openDuplicateModal(host: Host) { editingHost = null; duplicatingHost = host; modalOpen = true }
    fun closeModal() { modalOpen = false; editingHost = null; duplicatingHost = null }
    fun requestDeleteHost(host: Host) { pendingDeleteHost = host }
    fun dismissDeleteHost() { pendingDeleteHost = null }
    fun requestCloseSession(id: String) { pendingClose = PendingClose.Session(id) }
    fun requestCloseSplit(parentId: String) { pendingClose = PendingClose.Split(parentId) }
    fun dismissClose() { pendingClose = null }
    fun choosePolicy(p: AiPolicy) { modalPolicy = p }
    fun showRecordingNotice(outcome: RecordingOutcome) { recordingNotice = outcome.takeIf { it.worthReporting } }
    fun dismissRecordingNotice() { recordingNotice = null }
    fun showCast(result: CastOpenResult) {
        when (result) {
            is CastOpenResult.Loaded -> castRecording = result.cast
            CastOpenResult.Invalid -> castInvalid = true
            CastOpenResult.Cancelled -> Unit // the user backed out; nothing to report
        }
    }
    fun closeCast() { castRecording = null }
    fun dismissCastError() { castInvalid = false }
    fun openCommandPalette() { commandPaletteOpen = true }
    fun closeCommandPalette() { commandPaletteOpen = false }
    fun openBroadcast() { broadcastOpen = true }
    fun closeBroadcast() { broadcastOpen = false }
    // Reset to the first nav tab on every open: settings always start from the top item,
    // not whatever tab was left selected last time.
    fun openSettings() { settingsTab = SETTINGS_NAV.first().tab; settingsOpen = true }
    fun closeSettings() { settingsOpen = false }
    fun openSyncSetup() { syncSetupOpen = true }
    fun closeSyncSetup() { syncSetupOpen = false }
    fun openPairing() { pairingOpen = true }
    fun closePairing() { pairingOpen = false }
    fun showSettingsTab(t: SettingsTab) { settingsTab = t }
    fun toggleSplit() { split = !split }
    fun toggleSidebar() { sidebarHidden = !sidebarHidden }
    fun toggleVncSidebar() { vncSidebar = !vncSidebar }
    fun toggleInfo() { infoPanel = !infoPanel; onInfoPanelChange(infoPanel) }

    // Signal to focus the AI bar's input (hotkey Cmd// Ctrl+Shift+/). SharedFlow rather than a counter
    // state: it does not replay to a new subscriber, so remounting the AI bar (tab switch) can't steal
    // focus retroactively — focus is requested only on a NEW event. extraBufferCapacity=1 so tryEmit
    // isn't dropped when no collector is active at the moment of the keypress.
    private val _aiBarFocusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val aiBarFocusRequests: SharedFlow<Unit> = _aiBarFocusRequests
    fun requestAiBarFocus() { _aiBarFocusRequests.tryEmit(Unit) }

    // Hotkeys for the toolbar buttons that own their own state (snippet palette popup, recording
    // toggle, file picker). Same one-shot signal as the AI bar above rather than a flag on the
    // state: a boolean would have to be reset by the button and could re-fire on recomposition.
    private val _snippetPaletteRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val snippetPaletteRequests: SharedFlow<Unit> = _snippetPaletteRequests
    fun requestSnippetPalette() { _snippetPaletteRequests.tryEmit(Unit) }

    private val _recordingToggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recordingToggleRequests: SharedFlow<Unit> = _recordingToggleRequests
    fun requestRecordingToggle() { _recordingToggleRequests.tryEmit(Unit) }

    private val _castOpenRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val castOpenRequests: SharedFlow<Unit> = _castOpenRequests
    fun requestCastOpen() { _castOpenRequests.tryEmit(Unit) }

    /** Whether folder [name] is collapsed (its host list hidden). */
    fun isGroupCollapsed(name: String): Boolean = name in collapsedGroups

    /** Toggle folder [name] collapsed/expanded and report the new set outward (for persistence). */
    fun toggleGroupCollapsed(name: String) {
        collapsedGroups = if (name in collapsedGroups) collapsedGroups - name else collapsedGroups + name
        onCollapsedGroupsChange(collapsedGroups)
    }
    /**
     * Mark host [id] as recently connected: move it to the front of the list (no duplicate), trim to
     * [MAX_RECENT_HOSTS], and report outward (for persistence). Reconnecting to the already-first host
     * is a no-op (no mutation, no write). Blank id is ignored.
     */
    fun recordRecentHost(id: String) {
        if (id.isBlank()) return
        val next = (listOf(id) + recentHostIds.filterNot { it == id }).take(MAX_RECENT_HOSTS)
        if (next == recentHostIds) return
        recentHostIds = next
        onRecentHostIdsChange(recentHostIds)
    }

    /** Show/hide the RECENT section and report outward (for persistence). Repeating the same value is a no-op. */
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

    fun openCreateGroup() { groupDialog = GroupDialog.Create }
    fun openRenameGroup(name: String) { groupDialog = GroupDialog.Rename(name) }
    fun dismissGroupDialog() { groupDialog = null }

    /**
     * Create a new (initially empty) group. Name is trimmed and stripped of newlines (not storable
     * line-by-line in persistence). Empty or exactly matching an existing custom group is ignored.
     * Case-exact matching, consistent with `Host.group`/[groupHostsByFolder]/[collapsedGroups]
     * throughout the system; a duplicate of a group derived from hosts (exact name) is deduplicated at
     * render by folder merging. Persisted via callback.
     */
    fun addCustomGroup(name: String) {
        val n = name.trim().filterNot { it == '\n' || it == '\r' }
        if (n.isEmpty() || n in customGroups) return
        customGroups = customGroups + n
        onCustomGroupsChange(customGroups)
    }

    /**
     * Rename a group in the side channel: updates the empty-group list and the collapsed set
     * ([old]->[new]). Rewriting `Host.group` on real profiles is done by
     * [app.skerry.ui.host.HostManagerController.renameGroup] — the calling UI invokes both. Case-exact
     * matching, as in [app.skerry.ui.host.renameHostGroup], so the side channel doesn't drift from
     * profiles (including case-only edits). Name is trimmed and stripped of newlines; empty/unchanged
     * [new] is a no-op.
     */
    fun renameGroupName(old: String, new: String) {
        val n = new.trim().filterNot { it == '\n' || it == '\r' }
        if (n.isEmpty() || n == old) return
        if (old in customGroups) {
            customGroups = customGroups.map { if (it == old) n else it }.distinct()
            onCustomGroupsChange(customGroups)
        }
        if (old in collapsedGroups) {
            collapsedGroups = collapsedGroups - old + n
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Remove custom group [name] from the side channel (empty-group list + collapsed set). */
    fun removeCustomGroup(name: String) {
        if (name in customGroups) {
            customGroups = customGroups.filterNot { it == name }
            onCustomGroupsChange(customGroups)
        }
        if (name in collapsedGroups) {
            collapsedGroups = collapsedGroups - name
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Choose the terminal font and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseTerminalFont(font: TerminalFont) {
        if (font == terminalFont) return
        terminalFont = font
        onTerminalFontChange(font)
    }

    /** Choose the terminal theme and report outward (for persistence). Repeating the same value is a no-op. */
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

    /** Choose the app theme and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseThemeMode(mode: ThemeMode) {
        if (mode == themeMode) return
        themeMode = mode
        onThemeModeChange(mode)
    }

    /** Choose the auto-lock threshold and report outward (for persistence). Repeating the same value is a no-op. */
    fun chooseAutoLock(duration: AutoLockDuration) {
        if (duration == autoLock) return
        autoLock = duration
        onAutoLockChange(duration)
    }

    /** Choose the UI language and report outward (for persistence). Repeating the same value is a no-op. */
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

    /** Choose the cursor style and report outward (for persistence). Repeating the same value is a no-op. */
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

    /** Toggle honoring server OSC 52 clipboard writes and report outward (for persistence). */
    fun toggleAllowServerClipboardWrite() {
        allowServerClipboardWrite = !allowServerClipboardWrite
        onAllowServerClipboardWriteChange(allowServerClipboardWrite)
    }

    fun toggleSanitize() { sanitize = !sanitize }
    fun togglePreview() { preview = !preview }
    fun toggleConfirm() { confirm = !confirm }

    fun onCmd(value: String) { cmd = value }

    /** Demo command execution (mock `exec`): known commands produce output, otherwise not found. */
    fun runCmd() {
        val c = cmd.trim()
        if (c == "clear") { termLines = emptyList(); cmd = ""; return }
        val out = exec(c)
        val lines = termLines.toMutableList()
        lines += TermLine(text = c.ifEmpty { " " }, isCmd = true)
        if (out != null) lines += out
        termLines = lines
        cmd = ""
    }

    private fun exec(c: String): TermLine? {
        if (c.isEmpty()) return null
        DEMO_OUTPUT[c]?.let { return TermLine(text = it, isCmd = false) }
        return TermLine(text = "${c.substringBefore(' ')}: command not found", isCmd = false, error = true)
    }

    // internal (not private): MAX_RECENT_HOSTS is read by settings/persistence/tests in this module
    // as the cap on the number of recents shown (Settings -> Appearance -> Interface).
    internal companion object {
        /** Max entries in the sidebar's RECENT section; oldest are evicted by new connections. */
        const val MAX_RECENT_HOSTS = 8

        val DEMO_OUTPUT = mapOf(
            "ls" to "app  deploy  logs  backup.tar.gz",
            "ls -la" to "total 24\ndrwxr-xr-x  5 root root  app\ndrwxr-xr-x  2 root root  deploy\n-rw-r--r--  1 root root  backup.tar.gz",
            "pwd" to "/root",
            "whoami" to "root",
            "hostname" to "prod-web-01",
            "df -h" to "Filesystem  Size  Used Avail Use%\n/dev/sda1    50G   42G  5.2G  87%",
            "uptime" to "14:25:30 up 6 days,  load average: 0.42, 0.51, 0.48",
            "date" to "Sat Jun 21 14:25:30 UTC 2026",
            "free -h" to "              total        used        free\nMem:           4.0Gi       2.1Gi       1.9Gi",
            "help" to "Demo commands: ls, ls -la, pwd, whoami, hostname, df -h, free -h, uptime, date, clear",
        )
    }
}
