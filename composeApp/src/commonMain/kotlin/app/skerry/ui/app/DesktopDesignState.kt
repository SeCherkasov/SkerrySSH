package app.skerry.ui.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.section
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.settings.SETTINGS_NAV
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.session.BroadcastController
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.Tab
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
enum class DesktopView { Terminal, Sftp, Monitor, Ports, Snippets, Runbooks, Vault, Known, Teams }

/**
 * App-level views not tied to a specific SSH session: Ports(Tunnels)/Snippets/Vault/Known/Teams.
 * They open "over" the tabs ([DesktopDesignState.appOverlay]) and are shared across the app, while
 * Terminal/SFTP are sub-views of the active tab ([app.skerry.ui.session.Session.view]).
 *
 * Tunnels is a global list of saved forwards: a tunnel is self-contained and opens its own
 * connection to the host, so the section is shared rather than part of an open session.
 */
val DesktopView.isAppLevel: Boolean
    get() = this == DesktopView.Ports || this == DesktopView.Snippets || this == DesktopView.Runbooks ||
        this == DesktopView.Vault || this == DesktopView.Known || this == DesktopView.Teams

/** Rail item → session sub-view; app-level/Terminal map to Terminal. */
fun DesktopView.asSessionView(): SessionView = when (this) {
    DesktopView.Sftp -> SessionView.Sftp
    DesktopView.Monitor -> SessionView.Monitor
    else -> SessionView.Terminal
}

/** Session sub-view → rail item to highlight. */
fun SessionView.asDesktopView(): DesktopView = when (this) {
    SessionView.Terminal -> DesktopView.Terminal
    SessionView.Sftp -> DesktopView.Sftp
    SessionView.Monitor -> DesktopView.Monitor
    // VNC and the recording player have no dedicated rail item (they're work-area views, like
    // Terminal); don't highlight one.
    SessionView.Vnc -> DesktopView.Terminal
    SessionView.Player -> DesktopView.Terminal
}

/**
 * Section the work area renders, given the selected tab [active] and the section open in the rail
 * ([DesktopDesignState.section]). A remote-desktop tab ([Tab.isVnc]) renders as a framebuffer,
 * everything else (shells, SFTP, recording player) as a terminal-side view.
 *
 * The work area belongs to the tab, not to the rail: opening a section swaps the catalog in the
 * sidebar and leaves the live session on screen, so walking over to the desktops catalog while a
 * shell is running doesn't replace that shell with an empty state. It works the other way too —
 * tabbing between sessions swaps the screen without dragging the catalog along. Only with no tab
 * open does the rail decide the whole work area: there is nothing to keep.
 */
fun workAreaSection(active: Tab?, section: HostSection): HostSection = when {
    active == null -> section
    active.isVnc -> HostSection.RemoteDesktops
    else -> HostSection.Terminal
}

/** Settings panel tabs. */
enum class SettingsTab { AI, Sync, Security, Appearance, Terminal, Keyboard, Trash, About }

/**
 * Connection AI policy. Aliases the shared enum ([app.skerry.shared.host.Host.aiPolicy]) so the
 * modal's choice writes directly into the host profile.
 */
typealias AiPolicy = app.skerry.shared.ai.AiPolicy

/**
 * A destructive session action awaiting confirmation ([ConfirmActionDialog]). The action itself
 * (close/closePane) is performed by [DesktopChrome], which has access to the session manager.
 */
sealed interface PendingClose {
    /** Closing an entire session tab (power button in the toolbar). */
    data class Session(val id: String) : PendingClose

    /** Closing pane [paneId] of tab [tabId] (close icon in the pane's header). */
    data class Pane(val tabId: String, val paneId: String) : PendingClose
}

/**
 * A pane re-point awaiting confirmation: pointing pane [paneId] of tab [tabId] at [host] tears down
 * the session that pane already holds, so it is confirmed like the other destructive actions. Only
 * raised for a pane that holds one — a blank pane connects straight away.
 */
data class PendingPaneConnect(val tabId: String, val paneId: String, val host: Host)

/**
 * Open dialog for managing a sidebar host group: creating a new one ([Create]) or editing an
 * existing one by name ([Rename]). `null` in [DesktopDesignState.groupDialog] means no dialog.
 */
sealed interface GroupDialog {
    /** Creating a new (still empty) group in the sidebar of [section]. */
    data class Create(val section: HostSection) : GroupDialog

    /** Editing group [name]: rename or delete (ungroups its hosts). */
    data class Rename(val name: String) : GroupDialog
}

/**
 * An empty host folder: a group [name] the user created that no profile carries yet, remembered for
 * the sidebar of [section]. Once a profile joins the group its folder is derived from the hosts
 * (in whichever section those hosts belong to), so this side channel only holds the empty ones.
 */
data class CustomGroup(val name: String, val section: HostSection)

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
    /**
     * Persisted user preferences (everything Settings edits). Held rather than inlined: they are
     * their own concern with their own persistence callbacks, and the UI reaches them as
     * `state.settings.x`.
     */
    val settings: DesktopSettingsState = DesktopSettingsState(),
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
    initialCustomGroups: List<CustomGroup> = emptyList(),
    private val onCustomGroupsChange: (List<CustomGroup>) -> Unit = {},
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

    /** Whether the session panel beside a live remote desktop is hidden. */
    var remotePanelHidden: Boolean by mutableStateOf(false); private set

    /**
     * Which catalog the work area is showing: terminal-style connections or remote desktops (the
     * rail's two session-level items). Each section has its own host sidebar, its own "New
     * connection" form and its own session tabs; [appOverlay] renders over whichever is selected.
     */
    var section: HostSection by mutableStateOf(HostSection.Terminal); private set

    /**
     * Whether the assistant panel is open beside the terminal. Session-scoped like the info panel,
     * and not persisted: the assistant is opened for a question, not left standing.
     */
    var assistantPanel: Boolean by mutableStateOf(false); private set

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

    /** Custom (still empty) host groups, shown as folders alongside host-derived ones. */
    var customGroups: List<CustomGroup> by mutableStateOf(initialCustomGroups); private set

    /** Names of the empty folders belonging to [section]'s sidebar, in creation order. */
    fun customGroupsIn(section: HostSection): List<String> =
        customGroups.filter { it.section == section }.map { it.name }

    /**
     * Replace the empty-folder list wholesale without writing back ([onCustomGroupsChange]). This is
     * an external load, not a user edit: called after vault unlock, once empty folders are read from
     * the synced layout record ([app.skerry.shared.vault.WorkspaceLayout]) — the list starts empty
     * while the vault is locked. Must not write back here, or it would clobber the synced value.
     */
    fun loadCustomGroups(groups: List<CustomGroup>) {
        customGroups = groups
    }

    /** Open group management dialog (create/edit), or `null`. */
    var groupDialog: GroupDialog? by mutableStateOf(null); private set
    var selectedHost: String by mutableStateOf("prod-web-01"); private set

    /** Host sidebar search text (by name/address/user/group/tags). Empty means no filter. */
    var hostSearchQuery: String by mutableStateOf(""); private set
    var activeTab: Int by mutableStateOf(0); private set
    var modalPolicy: AiPolicy by mutableStateOf(AiPolicy.Strict); private set

    /** Host open in the modal for editing (null means the modal is in "New connection" mode). */
    var editingHost: Host? by mutableStateOf(null); private set

    /** Which section's protocols the open connection form offers (see [openModal]). */
    var modalSection: HostSection by mutableStateOf(HostSection.Terminal); private set

    /** Host the modal is prefilled from as a copy ("Duplicate"); saving creates a new profile. */
    var duplicatingHost: Host? by mutableStateOf(null); private set

    /**
     * ssh_config import: hosts parsed from a picked file, awaiting the user's selection in the import
     * modal; `null` means the modal is closed. Held on the state (not local Compose state) so the
     * sidebar coroutine that picks and parses the file can hand the result to the modal rendered at
     * the app root.
     */
    var sshImport: app.skerry.shared.ssh.SshConfigParseResult? by mutableStateOf(null); private set
    val sshImportOpen: Boolean get() = sshImport != null

    /** `.rdp` import: the profile read from a picked file, awaiting confirmation; `null` = closed. */
    var rdpImport: app.skerry.shared.rdp.RdpFileImportResult? by mutableStateOf(null); private set
    val rdpImportOpen: Boolean get() = rdpImport != null

    /** Host for which the delete-confirmation dialog is shown (null means no dialog). */
    var pendingDeleteHost: Host? by mutableStateOf(null); private set

    /** Destructive session action awaiting confirmation (null means no dialog). */
    var pendingClose: PendingClose? by mutableStateOf(null); private set
    var pendingPaneConnect: PendingPaneConnect? by mutableStateOf(null); private set

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

    /**
     * Open a work-area section from the rail (terminal / remote desktops). Clears the app overlay —
     * the click asks for the work area, which an open Vault/Teams section would otherwise hide. The
     * session sub-view ([view]) is left alone, so returning to the terminal lands on the SFTP panel
     * if that is where the user was. The selected tab is left alone too — see
     * [app.skerry.ui.desktop.openRailSection] for why the rail doesn't touch it.
     */
    fun showSection(s: HostSection) {
        appOverlay = null
        section = s
    }
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
    /**
     * Open the connection form for [section]: it offers only that section's protocols and starts on
     * its default one, so the list a profile is created from decides what kind of profile it is.
     */
    fun openModal(section: HostSection = HostSection.Terminal) {
        editingHost = null; duplicatingHost = null; modalSection = section; modalOpen = true
    }

    // Editing/duplicating follows the profile itself: its transport already says which section it
    // belongs to, whichever list the action was invoked from.
    fun openEditModal(host: Host) {
        editingHost = host; duplicatingHost = null; modalSection = host.section; modalOpen = true
    }
    fun openDuplicateModal(host: Host) {
        editingHost = null; duplicatingHost = host; modalSection = host.section; modalOpen = true
    }
    fun closeModal() { modalOpen = false; editingHost = null; duplicatingHost = null }
    fun beginSshImport(result: app.skerry.shared.ssh.SshConfigParseResult) { sshImport = result }
    fun closeSshImport() { sshImport = null }
    fun beginRdpImport(result: app.skerry.shared.rdp.RdpFileImportResult) { rdpImport = result }
    fun closeRdpImport() { rdpImport = null }
    fun requestDeleteHost(host: Host) { pendingDeleteHost = host }
    fun dismissDeleteHost() { pendingDeleteHost = null }
    fun requestCloseSession(id: String) { pendingClose = PendingClose.Session(id) }
    fun requestClosePane(tabId: String, paneId: String) { pendingClose = PendingClose.Pane(tabId, paneId) }
    fun dismissClose() { pendingClose = null }
    fun requestPaneConnect(tabId: String, paneId: String, host: Host) {
        pendingPaneConnect = PendingPaneConnect(tabId, paneId, host)
    }
    fun dismissPaneConnect() { pendingPaneConnect = null }
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

    fun toggleRemotePanel() { remotePanelHidden = !remotePanelHidden }

    fun toggleAssistant() { assistantPanel = !assistantPanel }

    /**
     * Hotkey (Cmd+/ or Ctrl+Shift+/): open the assistant panel and put the caret in its input. Opens
     * the panel rather than toggling it — the shortcut means "ask something", and hitting it with the
     * panel already open must not close it under the user.
     */
    fun openAssistant() {
        assistantPanel = true
        assistantFocusPending = true
    }

    /**
     * A focus request the assistant's input has not taken yet. A flag rather than the one-shot
     * [SharedFlow] the always-mounted AI bar used: the chord usually fires while the panel is closed,
     * so there is no collector at that moment and an emitted event would simply be dropped — the
     * panel would open with the caret still in the terminal. The ask row clears it via
     * [consumeAssistantFocus] once it has the focus, so a later remount can't take it retroactively.
     */
    var assistantFocusPending: Boolean by mutableStateOf(false); private set

    /** The ask row took the pending focus request (see [assistantFocusPending]). */
    fun consumeAssistantFocus() { assistantFocusPending = false }

    // Hotkeys for the toolbar buttons that own their own state (snippet palette popup, recording
    // toggle, file picker). Same one-shot signal as the AI bar above rather than a flag on the
    // state: a boolean would have to be reset by the button and could re-fire on recomposition.
    private val _snippetPaletteRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val snippetPaletteRequests: SharedFlow<Unit> = _snippetPaletteRequests
    fun requestSnippetPalette() { _snippetPaletteRequests.tryEmit(Unit) }

    private val _recordingToggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recordingToggleRequests: SharedFlow<Unit> = _recordingToggleRequests
    fun requestRecordingToggle() { _recordingToggleRequests.tryEmit(Unit) }

    private val _sharePanelRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sharePanelRequests: SharedFlow<Unit> = _sharePanelRequests
    fun requestSharePanel() { _sharePanelRequests.tryEmit(Unit) }

    private val _runbookPaletteRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val runbookPaletteRequests: SharedFlow<Unit> = _runbookPaletteRequests
    fun requestRunbookPalette() { _runbookPaletteRequests.tryEmit(Unit) }

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
     * [DesktopSettingsState.MAX_RECENT_HOSTS], and report outward (for persistence). Reconnecting to
     * is a no-op (no mutation, no write). Blank id is ignored.
     */
    fun recordRecentHost(id: String) {
        if (id.isBlank()) return
        val next = (listOf(id) + recentHostIds.filterNot { it == id })
            .take(DesktopSettingsState.MAX_RECENT_HOSTS)
        if (next == recentHostIds) return
        recentHostIds = next
        onRecentHostIdsChange(recentHostIds)
    }

    fun openCreateGroup(section: HostSection) { groupDialog = GroupDialog.Create(section) }
    fun openRenameGroup(name: String) { groupDialog = GroupDialog.Rename(name) }
    fun dismissGroupDialog() { groupDialog = null }

    /**
     * Create a new (initially empty) group in [section]'s sidebar — the one section it shows in until
     * a profile joins it. Name is trimmed and stripped of newlines (not storable line-by-line in
     * persistence). Empty or exactly matching an existing custom group of that section is ignored.
     * Case-exact matching, consistent with `Host.group`/[groupHostsByFolder]/[collapsedGroups]
     * throughout the system; a duplicate of a group derived from hosts (exact name) is deduplicated at
     * render by folder merging. Persisted via callback.
     */
    fun addCustomGroup(name: String, section: HostSection) {
        val n = name.trim().filterNot { it == '\n' || it == '\r' }
        val group = CustomGroup(n, section)
        if (n.isEmpty() || group in customGroups) return
        customGroups = customGroups + group
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
        // A group name is global (Host.group), so the rename reaches the empty folders of both sections.
        if (customGroups.any { it.name == old }) {
            customGroups = customGroups.map { if (it.name == old) it.copy(name = n) else it }.distinct()
            onCustomGroupsChange(customGroups)
        }
        if (old in collapsedGroups) {
            collapsedGroups = collapsedGroups - old + n
            onCollapsedGroupsChange(collapsedGroups)
        }
    }

    /** Remove custom group [name] from the side channel (empty-group list + collapsed set). */
    fun removeCustomGroup(name: String) {
        if (customGroups.any { it.name == name }) {
            customGroups = customGroups.filterNot { it.name == name }
            onCustomGroupsChange(customGroups)
        }
        if (name in collapsedGroups) {
            collapsedGroups = collapsedGroups - name
            onCollapsedGroupsChange(collapsedGroups)
        }
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

    internal companion object {
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
