package app.skerry.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.vnc.VncAuth
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.terminal.CastPlayback
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.vnc.VncSessionController

/**
 * Sub-view of a session (tab-scoped): what's shown in its work area. Tunnels are not included here;
 * they're a global section, see [app.skerry.ui.app.DesktopView.isAppLevel]. [Vnc] is a
 * framebuffer tab (remote desktop) and [Player] a recording being replayed — neither has
 * terminal/SFTP sub-views.
 */
enum class SessionView { Terminal, Sftp, Vnc, Player }

/**
 * One open session — a titlebar tab. Owns its own [ConnectionController] (one shell per session).
 * [hostId] links the tab to a host-catalog profile so the sidebar can mark hosts with a live
 * session via a status dot; `null` for ad-hoc connections without a saved host. [title]/[subtitle]
 * are the tab label and the `user@host:port` string for the session bar.
 *
 * The connection fields ([hostId]/[title]/[subtitle]) are mutable (snapshot state): a blank tab
 * ([isBlank]) is created unfilled and gets bound by the first connection via
 * [SessionsController.connect] (can only be bound once — after that the connection has started).
 * [view] is the selected sub-view, tracked per tab.
 */
@Stable
class Session(
    val id: String,
    hostId: String?,
    title: String,
    subtitle: String,
    val controller: ConnectionController,
    /**
     * Set only for VNC tabs (a framebuffer session): when non-null, this tab renders a remote
     * desktop instead of a terminal, and [controller] is an idle, unused terminal controller kept
     * so the many `session.controller` read-sites (split/status/close) stay total. See [isVnc].
     */
    val vncController: VncSessionController? = null,
    /**
     * Set only for player tabs (a recording being watched): when non-null, this tab replays a
     * `.cast` instead of holding a connection, and [controller] is an idle, unused terminal
     * controller kept so the many `session.controller` read-sites stay total. See [isPlayer].
     */
    val playback: CastPlayback? = null,
) {
    var hostId: String? by mutableStateOf(hostId)
        private set

    /** Whether this is a VNC (remote-desktop) tab rather than a terminal one. */
    val isVnc: Boolean get() = vncController != null

    /** Whether this tab replays a recording rather than holding a session. */
    val isPlayer: Boolean get() = playback != null
    var title: String by mutableStateOf(title)
        private set
    var subtitle: String by mutableStateOf(subtitle)
        private set

    /** Selected sub-view of this tab (Terminal/SFTP), persists across tab switches. */
    var view: SessionView by mutableStateOf(SessionView.Terminal)
        private set

    /**
     * Panes: a tab can hold up to [MAX_PANES] independent sessions side by side. [panes] are the
     * extra ones (this session is always the first pane), each with its own [ConnectionController]
     * — own connection, terminal and selection. They are deliberately not in
     * [SessionsController.sessions]: a pane is owned by its tab and torn down with it, and the tab
     * bar lists tabs, not panes.
     *
     * [paneLayout] places them on the grid (see [PaneLayout]); [focusedPaneId] is the pane the user
     * is working in, which decides what the tab chip shows and where a snippet or a runbook lands.
     * [syncInput] mirrors typing into every connected pane of this tab (tmux `synchronize-panes`).
     */
    var panes: List<Session> by mutableStateOf(emptyList())
        private set
    var paneLayout: PaneLayout by mutableStateOf(PaneLayout.of(id))
        private set
    var focusedPaneId: String by mutableStateOf(id)
        private set
    var syncInput: Boolean by mutableStateOf(false)
        private set

    /** Every pane of this tab in creation order, starting with the tab's own (primary) session. */
    val allPanes: List<Session> get() = listOf(this) + panes

    /** Pane [paneId] of this tab (the tab itself included), or `null` if it holds no such pane. */
    fun pane(paneId: String): Session? = allPanes.firstOrNull { it.id == paneId }

    /** The pane the user is working in; falls back to the primary one if the focused pane is gone. */
    val focusedPane: Session get() = pane(focusedPaneId) ?: this

    /** Whether this tab is split at all — i.e. holds more than its primary pane. */
    val hasPanes: Boolean get() = panes.isNotEmpty()

    /**
     * A blank tab with no session: no host selected and no connection started yet (controller in
     * [ConnectionUiState.Form]). Created by the "+" button; the first connection fills it. A tab
     * with a host already bound does not become blank again after [ConnectionController.disconnect].
     */
    val isBlank: Boolean get() = hostId == null && vncController == null && playback == null &&
        controller.uiState is ConnectionUiState.Form

    internal fun setView(v: SessionView) { view = v }

    internal fun setPanes(list: List<Session>) { panes = list }
    internal fun setPaneLayout(layout: PaneLayout) { paneLayout = layout }
    internal fun setFocusedPane(paneId: String) { focusedPaneId = paneId }
    internal fun setSyncInput(on: Boolean) { syncInput = on }

    /**
     * Fill a blank tab with a profile before its first connection (see [SessionsController.connect]).
     * Only valid while the tab is blank ([isBlank]): can be bound once — after the connection starts,
     * rewriting hostId/title would break the tab's correspondence with its live session.
     */
    internal fun bind(hostId: String?, title: String, subtitle: String) {
        check(isBlank) { "bind() on a non-blank tab: connection already started" }
        this.hostId = hostId
        this.title = title
        this.subtitle = subtitle
    }

    /**
     * Tab title: the host's catalog name ([title]).
     *
     * The terminal's live OSC 0/1/2 title is intentionally not used here: on plain-bash servers it
     * reduces to a noisy `root@<hostname>` and would override a clear label inconsistently (busybox
     * routers don't send OSC titles at all). [effectiveTabTitle] exists for a future setting that
     * opts into it; until then the tab always shows the host label.
     */
    val displayTitle: String get() = title

    /**
     * Live window title from OSC 0/1/2 of this tab's connected terminal (`vim ~/app`, `root@host`…),
     * or `null` if no session is open or no title was ever set. Read from terminal snapshot state,
     * so the getter is reactive in Compose.
     */
    val liveTitle: String?
        get() = when (val s = controller.uiState) {
            is ConnectionUiState.Connected -> s.terminal.title.takeIf { it.isNotBlank() }
            is ConnectionUiState.Disconnected -> s.terminal.title.takeIf { it.isNotBlank() }
            else -> null
        }

    /** This tab's live terminal (Connected/Disconnected), or `null` while no session is open. */
    val liveTerminal: TerminalScreenState?
        get() = when (val s = controller.uiState) {
            is ConnectionUiState.Connected -> s.terminal
            is ConnectionUiState.Disconnected -> s.terminal
            else -> null
        }

    /**
     * Live terminals that synchronized input typed in [originPaneId] must also reach: every other
     * connected pane of this tab, and only while [syncInput] is on. A pane that is still connecting,
     * failed, or lost its session is skipped — mirrored keys would land in a screen that cannot
     * take them.
     */
    fun syncTargetsFrom(originPaneId: String): List<TerminalScreenState> {
        if (!syncInput) return emptyList()
        return allPanes.filter { it.id != originPaneId }
            .mapNotNull { (it.controller.uiState as? ConnectionUiState.Connected)?.terminal }
    }

    /**
     * Tab title honoring the "show terminal title on tabs" setting (Settings → Terminal). Off:
     * always the host label ([displayTitle]); on: the live OSC title ([liveTitle]) overrides the
     * label, falling back to it when absent (see [effectiveTabTitle]).
     */
    fun tabTitle(showLiveTitle: Boolean): String =
        if (showLiveTitle) effectiveTabTitle(liveTitle, displayTitle) else displayTitle
}

/**
 * Effective tab title: a non-blank live [liveTitle] overrides [fallback]. Used by
 * [Session.tabTitle] when the "show terminal title on tabs" setting (Settings → Terminal) is on;
 * off, the tab always shows the host label ([Session.displayTitle]).
 */
fun effectiveTabTitle(liveTitle: String?, fallback: String): String =
    liveTitle?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Manager for open sessions over [ConnectionController] — the desktop tab model. Each tab is
 * isolated with its own controller (one session = one shell); [activeId] points at the one shown
 * in the main area.
 *
 * Controllers are created by [controllerFactory] (prod: `ConnectionController(transport, scope)`;
 * tests: with a test dispatcher); tab ids come from [newId], injected by the platform entry point
 * (UUID), same approach as [app.skerry.ui.host.HostManagerController].
 *
 * [close] picks the neighbor to the right after removing the active tab, else the one to the left,
 * else none. The closed tab's connection is torn down explicitly ([ConnectionController.disconnect]
 * is idempotent), otherwise the socket would leak.
 */
@Stable
class SessionsController(
    private val newId: () -> String,
    private val controllerFactory: () -> ConnectionController,
    // VNC tabs use their own controller. Defaulted to a no-op factory so tests and non-VNC entry
    // points that don't wire a VNC transport keep compiling; the desktop/Android entry points pass a
    // real one (VncSessionController over VncTcpTransport).
    private val vncControllerFactory: (() -> VncSessionController)? = null,
    /**
     * Called with the catalog host id whenever a session to it actually starts — every path here
     * that opens a connection, and no others (a blank tab, a player, or an ad-hoc target typed into
     * the form belong to no host). Wired by the entry points to the Teams activity report; must not
     * throw or block, since a connection is already under way.
     */
    private val onHostSessionOpened: (String) -> Unit = {},
) {
    var sessions: List<Session> by mutableStateOf(emptyList())
        private set

    var activeId: String? by mutableStateOf(null)
        private set

    val active: Session? get() = sessions.firstOrNull { it.id == activeId }

    /**
     * The active tab as seen by the terminal section — `null` when a remote-desktop tab is active.
     * The two sections have their own work areas, so each reads the active tab through its own lens
     * instead of rendering a tab that belongs to the other one (a VNC tab under the terminal would
     * show its idle placeholder controller as "not connected"). A player tab counts as terminal: it
     * lives beside the shells, not in the remote-desktop catalog.
     */
    val activeTerminal: Session? get() = active?.takeIf { !it.isVnc }

    /** The active tab as seen by the remote-desktop section, `null` when a terminal tab is active. */
    val activeDesktop: Session? get() = active?.takeIf { it.isVnc }

    /**
     * Newest tab of a section, or `null` if it has none. Switching sections from the rail activates
     * this one, so returning to a section lands back on a live session instead of an empty area.
     */
    fun lastSessionIn(remoteDesktop: Boolean): Session? = sessions.lastOrNull { it.isVnc == remoteDesktop }

    /** Open a new session to [target] and make it active; connects immediately. Returns the new tab's id. */
    fun open(
        hostId: String?,
        title: String,
        subtitle: String,
        target: SshTarget,
        auth: SshAuth,
        onConnected: ((TerminalScreenState) -> Unit)? = null,
    ): String {
        val controller = controllerFactory()
        val session = Session(newId(), hostId, title, subtitle, controller)
        sessions = sessions + session
        activeId = session.id
        reportHostSession(hostId)
        controller.connect(target, auth, onConnected)
        return session.id
    }

    /**
     * Open a blank tab with no session (the "+" button): no connection starts, controller stays in
     * [ConnectionUiState.Form]. Becomes active; gets filled by the first [connect]. Returns its id.
     *
     * [title] is the placeholder tab label; the calling composable resolves the localized label
     * (stringResource is unavailable in the controller). `null` gives an empty label (tests/ad-hoc).
     */
    fun openBlank(title: String? = null): String {
        val controller = controllerFactory()
        val session = Session(newId(), hostId = null, title = title ?: "", subtitle = "", controller)
        sessions = sessions + session
        activeId = session.id
        return session.id
    }

    /**
     * Connect to [target]: if the active tab is blank ([Session.isBlank]), fill and connect it in
     * place (no new tab); otherwise open a new one via [open]. Returns the id of the tab the
     * connection started in.
     */
    fun connect(
        hostId: String?,
        title: String,
        subtitle: String,
        target: SshTarget,
        auth: SshAuth,
        onConnected: ((TerminalScreenState) -> Unit)? = null,
    ): String {
        val blank = active?.takeIf { it.isBlank }
        if (blank != null) {
            blank.bind(hostId, title, subtitle)
            reportHostSession(hostId)
            blank.controller.connect(target, auth, onConnected)
            return blank.id
        }
        return open(hostId, title, subtitle, target, auth, onConnected)
    }

    /**
     * Open a new VNC (remote-desktop) tab and connect it. Always a fresh tab (a VNC session never
     * reuses a blank terminal tab), with [SessionView.Vnc]. Requires a VNC controller factory
     * (wired at the entry point); a no-op if none was provided. Returns the new tab's id, or null.
     */
    fun openVnc(
        hostId: String?,
        title: String,
        subtitle: String,
        target: SshTarget,
        auth: VncAuth,
        remoteResize: Boolean = false,
        onRemoteResizeChanged: (Boolean) -> Unit = {},
    ): String? {
        val vncFactory = vncControllerFactory ?: return null
        val vnc = vncFactory()
        // An idle terminal controller keeps `session.controller` non-null for the shared read-sites.
        val session = Session(newId(), hostId, title, subtitle, controllerFactory(), vncController = vnc)
        session.setView(SessionView.Vnc)
        sessions = sessions + session
        activeId = session.id
        reportHostSession(hostId)
        vnc.connect(target, auth, remoteResize, onRemoteResizeChanged)
        return session.id
    }

    /**
     * Open a recording in its own tab (never reuses a blank one), locked to [SessionView.Player].
     * A player tab lives beside the sessions instead of over them, so a shell stays reachable while
     * a recording is watched. [title] is the tab label, resolved by the caller (the recording's own
     * title, else a localized default). Returns the new tab's id.
     */
    fun openPlayer(title: String, cast: Asciicast): String {
        // An idle terminal controller keeps `session.controller` non-null for the shared read-sites.
        val session = Session(
            newId(), hostId = null, title = title, subtitle = "", controllerFactory(),
            playback = CastPlayback(cast),
        )
        session.setView(SessionView.Player)
        sessions = sessions + session
        activeId = session.id
        return session.id
    }

    /** Switch the active tab's sub-view (Terminal/SFTP); no-op on a VNC/player tab or with none active. */
    fun setActiveView(view: SessionView) {
        val tab = active ?: return
        // VNC and player tabs are locked to their own view — there is no shell behind them.
        if (tab.isVnc || tab.isPlayer) return
        tab.setView(view)
    }

    /** Make session [id] active; an unknown id is ignored. */
    fun activate(id: String) {
        if (sessions.any { it.id == id }) activeId = id
    }

    /**
     * Move the tab at [fromIndex] to [toIndex] (titlebar drag-reorder). Both indices must be valid;
     * moving to the same position is a no-op. [activeId] addresses a tab by id, so the active tab
     * doesn't change when reordering.
     */
    fun moveTab(fromIndex: Int, toIndex: Int) {
        val indices = sessions.indices
        if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return
        sessions = sessions.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    /**
     * Add an empty pane to tab [id] (active tab by default) and focus it; the pane shows the host
     * picker until something is connected into it ([connectPane]). [slot] places it explicitly (a
     * drop from the pane grid); without one it goes where [PaneLayout.defaultSlot] puts it.
     *
     * Returns the new pane's id, or `null` when the tab is already at [MAX_PANES], holds a remote
     * desktop or a recording (neither has a shell beside it), or does not exist.
     */
    fun addPane(id: String? = activeId, slot: PaneSlot? = null): String? {
        val tab = sessions.firstOrNull { it.id == id } ?: return null
        if (tab.isVnc || tab.isPlayer || tab.paneLayout.isFull) return null
        val pane = Session(newId(), hostId = null, title = "", subtitle = "", controllerFactory())
        tab.setPanes(tab.panes + pane)
        tab.setPaneLayout(tab.paneLayout.add(pane.id, slot ?: tab.paneLayout.defaultSlot()))
        tab.setFocusedPane(pane.id)
        return pane.id
    }

    /**
     * Connect [target] into pane [paneId] of tab [tabId] and focus it. An empty pane is filled in
     * place; a pane that already holds a session has it disconnected and replaced by a fresh one in
     * the same slot (pointing a pane at another host is how it is re-used). Panes are not in
     * [sessions] — they belong to the tab.
     *
     * The tab's own (primary) pane goes through [connect] instead, so [paneId] naming it is refused.
     */
    fun connectPane(
        tabId: String,
        paneId: String,
        hostId: String?,
        title: String,
        subtitle: String,
        target: SshTarget,
        auth: SshAuth,
    ) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        if (paneId == tab.id) return
        val existing = tab.panes.firstOrNull { it.id == paneId } ?: return
        reportHostSession(hostId)
        if (existing.isBlank) {
            existing.bind(hostId, title, subtitle)
            tab.setFocusedPane(existing.id)
            existing.controller.connect(target, auth)
            return
        }
        // A pane that already ran a session is replaced wholesale: the controller keeps the state of
        // the connection it opened, so re-using it for another host would carry that history over.
        existing.controller.disconnect()
        val replacement = Session(newId(), hostId, title, subtitle, controllerFactory())
        tab.setPanes(tab.panes.map { if (it.id == paneId) replacement else it })
        tab.setPaneLayout(tab.paneLayout.replace(paneId, replacement.id))
        tab.setFocusedPane(replacement.id)
        replacement.controller.connect(target, auth)
    }

    /**
     * Move pane [paneId] of tab [tabId] to [slot] — the drop of a pane drag. Panes only move within
     * their own tab; the tab's primary pane moves like any other (only closing it is special).
     */
    fun movePane(tabId: String, paneId: String, slot: PaneSlot) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        tab.setPaneLayout(tab.paneLayout.move(paneId, slot))
    }

    /** Drag the divider under row [boundary] of tab [tabId] by [delta] (share of the tab's height). */
    fun resizePaneRows(tabId: String, boundary: Int, delta: Float) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        tab.setPaneLayout(tab.paneLayout.resizeRows(boundary, delta))
    }

    /** Drag the divider after pane [boundary] of row [row] by [delta] (share of the row's width). */
    fun resizePaneCells(tabId: String, row: Int, boundary: Int, delta: Float) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        tab.setPaneLayout(tab.paneLayout.resizeCells(row, boundary, delta))
    }

    /**
     * Toggle synchronized input on tab [tabId] (active tab by default): while on, what is typed in
     * one pane is mirrored into every other connected pane of the tab. Turning it on with a single
     * pane is allowed — it stays armed for the panes added next.
     */
    fun toggleSyncInput(tabId: String? = activeId) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        tab.setSyncInput(!tab.syncInput)
    }

    /**
     * Reports a starting session on a catalog host (see [onHostSessionOpened]). A null [hostId] is an
     * ad-hoc target with no catalog record behind it, so there is nothing to report it against.
     */
    private fun reportHostSession(hostId: String?) {
        if (hostId != null) onHostSessionOpened(hostId)
    }

    /**
     * Close pane [paneId] of tab [tabId]: tear down its connection and take it off the grid; focus
     * falls back to the tab's primary pane. Closing the primary pane is refused — it is the tab's
     * own session, so closing that one means closing the tab ([close]).
     */
    fun closePane(tabId: String, paneId: String) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        if (paneId == tab.id) return
        val pane = tab.panes.firstOrNull { it.id == paneId } ?: return
        pane.controller.disconnect()
        tab.setPanes(tab.panes - pane)
        tab.setPaneLayout(tab.paneLayout.remove(paneId))
        if (tab.focusedPaneId == paneId) tab.setFocusedPane(tab.id)
    }

    /** Focus pane [paneId] of tab [tabId]; a pane this tab doesn't hold is ignored. */
    fun focusPane(tabId: String, paneId: String) {
        val tab = sessions.firstOrNull { it.id == tabId } ?: return
        if (tab.pane(paneId) != null) tab.setFocusedPane(paneId)
    }

    /** Close session [id]: disconnect it (and its panes), remove the tab, select a neighbor. */
    fun close(id: String) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].controller.disconnect()
        sessions[index].vncController?.disconnect()
        sessions[index].playback?.stop()
        sessions[index].panes.forEach { it.controller.disconnect() }
        val remaining = sessions.toMutableList().apply { removeAt(index) }
        if (activeId == id) {
            // The right neighbor shifted into the freed index; else take the left one, else none.
            activeId = remaining.getOrNull(index)?.id ?: remaining.getOrNull(index - 1)?.id
        }
        sessions = remaining
    }

    /** State of the most recent session for host [hostId] (for the sidebar status dot), or null. */
    fun statusFor(hostId: String): ConnectionUiState? =
        sessions.lastOrNull { it.hostId == hostId }?.controller?.uiState

    /** Close all sessions (and their panes) — call on screen teardown to avoid leaking sockets. */
    fun disconnectAll() {
        sessions.forEach { session ->
            session.controller.disconnect()
            session.vncController?.disconnect()
            session.playback?.stop()
            session.panes.forEach { it.controller.disconnect() }
        }
        sessions = emptyList()
        activeId = null
    }
}
