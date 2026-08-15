package app.skerry.ui.session

import app.skerry.ui.remote.RdpConnectRequest
import app.skerry.ui.remote.RemoteDesktopController
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.vnc.VncAuth
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.terminal.CastPlayback
import app.skerry.ui.terminal.TerminalScreenState

/**
 * Sub-view of a session (tab-scoped): what's shown in its work area. Tunnels are not included here;
 * they're a global section, see [app.skerry.ui.app.DesktopView.isAppLevel]. [Vnc] is a
 * framebuffer tab (remote desktop) and [Player] a recording being replayed — neither has
 * terminal/SFTP sub-views.
 */
enum class SessionView { Terminal, Sftp, Monitor, Vnc, Player, Runbook }

/**
 * One session — a single connection with its own [ConnectionController] (one shell per session),
 * shown as one pane of a [Tab]. [hostId] links it to a host-catalog profile so the sidebar can mark
 * hosts with a live session via a status dot; `null` for ad-hoc connections without a saved host.
 * [title]/[subtitle] are the label and the `user@host:port` string shown in the pane header.
 *
 * The connection fields ([hostId]/[title]/[subtitle]) are mutable (snapshot state): a blank pane
 * ([isBlank]) is created unfilled and gets bound by the first connection
 * ([SessionsController.connect]/[SessionsController.connectPane]) — once only, since after the
 * connection starts rewriting them would break the pane's correspondence with its live session.
 */
@Stable
class Session(
    val id: String,
    hostId: String?,
    title: String,
    subtitle: String,
    val controller: ConnectionController,
    /**
     * Set only for a VNC session (a framebuffer): when non-null, the pane renders a remote desktop
     * instead of a terminal, and [controller] is an idle, unused terminal controller kept so the
     * many `session.controller` read-sites (status/close) stay total. See [isVnc].
     */
    val vncController: RemoteDesktopController? = null,
    /**
     * Set only for a player session (a recording being watched): when non-null, the pane replays a
     * `.cast` instead of holding a connection, and [controller] is an idle, unused terminal
     * controller kept so the many `session.controller` read-sites stay total. See [isPlayer].
     */
    val playback: CastPlayback? = null,
) {
    var hostId: String? by mutableStateOf(hostId)
        private set

    /** Whether this is a VNC (remote-desktop) session rather than a terminal one. */
    val isVnc: Boolean get() = vncController != null

    /** Whether this replays a recording rather than holding a connection. */
    val isPlayer: Boolean get() = playback != null
    var title: String by mutableStateOf(title)
        private set
    var subtitle: String by mutableStateOf(subtitle)
        private set

    /**
     * Status for the chrome around this session (tab chip, host row). Reads whichever controller
     * actually holds the connection: a remote desktop's terminal [controller] is a placeholder that
     * never leaves its idle state, so going through it alone would paint a live desktop grey.
     */
    val status: SessionStatus
        get() = if (isVnc) vncController?.uiState.asSessionStatus() else controller.uiState.asSessionStatus()

    /**
     * A blank pane with no session: no host selected and no connection started yet (controller in
     * [ConnectionUiState.Form]). A pane with a host already bound does not become blank again after
     * [ConnectionController.disconnect].
     */
    val isBlank: Boolean get() = hostId == null && vncController == null && playback == null &&
        controller.uiState is ConnectionUiState.Form

    /**
     * Fill a blank pane with a profile before its first connection. Only valid while the pane is
     * blank ([isBlank]).
     */
    internal fun bind(hostId: String?, title: String, subtitle: String) {
        check(isBlank) { "bind() on a non-blank session: connection already started" }
        this.hostId = hostId
        this.title = title
        this.subtitle = subtitle
    }

    /**
     * Label of this session: the host's catalog name ([title]).
     *
     * The terminal's live OSC 0/1/2 title is intentionally not used here: on plain-bash servers it
     * reduces to a noisy `root@<hostname>` and would override a clear label inconsistently (busybox
     * routers don't send OSC titles at all). [effectiveTabTitle] exists for a future setting that
     * opts into it; until then the label is always the host's.
     */
    val displayTitle: String get() = title

    /**
     * Live window title from OSC 0/1/2 of this session's terminal (`vim ~/app`, `root@host`…), or
     * `null` if no session is open or no title was ever set. Read from terminal snapshot state, so
     * the getter is reactive in Compose.
     */
    val liveTitle: String?
        get() = when (val s = controller.uiState) {
            is ConnectionUiState.Connected -> s.terminal.title.takeIf { it.isNotBlank() }
            is ConnectionUiState.Disconnected -> s.terminal.title.takeIf { it.isNotBlank() }
            else -> null
        }

    /** This session's live terminal (Connected/Disconnected), or `null` while none is open. */
    val liveTerminal: TerminalScreenState?
        get() = when (val s = controller.uiState) {
            is ConnectionUiState.Connected -> s.terminal
            is ConnectionUiState.Disconnected -> s.terminal
            else -> null
        }

    /**
     * Label honoring the "show terminal title on tabs" setting (Settings → Terminal). Off: always
     * the host label ([displayTitle]); on: the live OSC title ([liveTitle]) overrides it, falling
     * back to the label when absent (see [effectiveTabTitle]).
     */
    fun tabTitle(showLiveTitle: Boolean): String =
        if (showLiveTitle) effectiveTabTitle(liveTitle, displayTitle) else displayTitle

    /** Tear this session down whatever kind it is; idempotent, safe to call on an idle pane. */
    internal fun teardown() {
        controller.disconnect()
        vncController?.disconnect()
        playback?.stop()
    }
}

/**
 * One titlebar tab: a grid of up to [MAX_PANES] [Session]s, each an independent connection with its
 * own terminal and selection. Panes are equal — the first one is not privileged, so any of them can
 * be pointed at another host or closed, and closing the last one closes the tab
 * ([SessionsController.closePane]).
 *
 * [layout] places the panes on the grid (see [PaneLayout]); [focusedPaneId] is the pane the user is
 * working in, which decides what the tab chip shows and where a snippet or a runbook lands.
 * [syncInput] mirrors typing into every connected pane of this tab (tmux `synchronize-panes`).
 * [view] is the selected sub-view (Terminal/SFTP), tracked per tab.
 *
 * Panes are deliberately not in [SessionsController.tabs]: a pane is owned by its tab and torn down
 * with it, and the tab bar lists tabs, not panes.
 */
@Stable
class Tab(val id: String, first: Session) {
    var panes: List<Session> by mutableStateOf(listOf(first))
        private set
    var layout: PaneLayout by mutableStateOf(PaneLayout.of(first.id))
        private set
    var focusedPaneId: String by mutableStateOf(first.id)
        private set
    var syncInput: Boolean by mutableStateOf(false)
        private set

    /** Selected sub-view of this tab (Terminal/SFTP), persists across tab switches. */
    var view: SessionView by mutableStateOf(SessionView.Terminal)
        private set

    /** Pane [paneId] of this tab, or `null` if it holds no such pane. */
    fun pane(paneId: String): Session? = panes.firstOrNull { it.id == paneId }

    /** The pane the user is working in; falls back to the first one if the focused pane is gone. */
    val focusedPane: Session get() = pane(focusedPaneId) ?: panes.first()

    /** Whether this tab is split at all — i.e. holds more than one pane. */
    val isSplit: Boolean get() = panes.size > 1

    /**
     * Whether this is a remote-desktop tab rather than a terminal one. A VNC session never shares a
     * tab with shells ([SessionsController.addPane] refuses to split it), so the kind of the tab is
     * the kind of its only pane.
     */
    val isVnc: Boolean get() = panes.first().isVnc

    /** Whether this tab replays a recording rather than holding sessions. */
    val isPlayer: Boolean get() = panes.first().isPlayer

    /**
     * A blank tab with nothing connected: one pane, no host picked yet. Created by the "+" button;
     * the first connection fills it in place ([SessionsController.connect]).
     */
    val isBlank: Boolean get() = panes.size == 1 && panes.first().isBlank

    /** Tab label: the focused pane's, so a split tab is named after the pane being worked in. */
    val displayTitle: String get() = focusedPane.displayTitle

    /** Tab label honoring the "show terminal title on tabs" setting; see [Session.tabTitle]. */
    fun tabTitle(showLiveTitle: Boolean): String = focusedPane.tabTitle(showLiveTitle)

    internal fun setView(v: SessionView) { view = v }

    /**
     * A tab always holds at least one pane — half its getters read [panes]`.first()`, and the grid
     * has nothing to draw without one. Emptying it is closing the tab
     * ([SessionsController.closePane] does exactly that), so an empty list here is a bug upstream
     * and is refused loudly rather than surfacing later as a failure inside a getter.
     */
    internal fun setPanes(list: List<Session>) {
        require(list.isNotEmpty()) { "a tab cannot lose its last pane: close the tab instead" }
        panes = list
    }
    internal fun setLayout(l: PaneLayout) { layout = l }
    internal fun setFocusedPane(paneId: String) { focusedPaneId = paneId }
    internal fun setSyncInput(on: Boolean) { syncInput = on }

    /**
     * Live terminals that synchronized input typed in [originPaneId] must also reach: every other
     * connected pane of this tab, and only while [syncInput] is on. A pane that is still connecting,
     * failed, or lost its session is skipped — mirrored keys would land in a screen that cannot
     * take them.
     */
    fun syncTargetsFrom(originPaneId: String): List<TerminalScreenState> {
        if (!syncInput) return emptyList()
        return panes.filter { it.id != originPaneId }
            .mapNotNull { (it.controller.uiState as? ConnectionUiState.Connected)?.terminal }
    }
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
    // Remote-desktop tabs use their own controller. Defaulted to null so tests and entry points
    // that wire no remote-desktop transport keep compiling; the desktop/Android entry points pass a
    // real one.
    private val vncControllerFactory: (() -> RemoteDesktopController)? = null,
    /** Opens a VNC session for a target; null when no RFB transport is wired. */
    private val openVncSession: (suspend (SshTarget, VncAuth) -> RemoteDesktopSession)? = null,
    /** Opens an RDP session for a host profile; null when no RDP transport is wired. */
    private val openRdpSession: (suspend (RdpConnectRequest) -> RemoteDesktopSession)? = null,
    /**
     * Called with the catalog host id whenever a session to it actually starts — every path here
     * that opens a connection, and no others (a blank tab, a player, or an ad-hoc target typed into
     * the form belong to no host). Wired by the entry points to the Teams activity report; must not
     * throw or block, since a connection is already under way.
     */
    private val onHostSessionOpened: (String) -> Unit = {},
) {
    var tabs: List<Tab> by mutableStateOf(emptyList())
        private set

    var activeId: String? by mutableStateOf(null)
        private set

    val active: Tab? get() = tabs.firstOrNull { it.id == activeId }

    /** Tab [id], or `null` if no tab has it. */
    fun tab(id: String?): Tab? = tabs.firstOrNull { it.id == id }

    /** Every open session, panes included — what the sidebar and the host status dots read. */
    val allSessions: List<Session> get() = tabs.flatMap { it.panes }

    /**
     * The session being worked in: the focused pane of the active tab. What every single-session
     * read-site wants (the status bar, the info panel, the mobile screens, which have no panes).
     */
    val activeSession: Session? get() = active?.focusedPane

    /**
     * The active tab as seen by the terminal section — `null` when a remote-desktop tab is active.
     * The two sections have their own work areas, so each reads the active tab through its own lens
     * instead of rendering a tab that belongs to the other one (a VNC tab under the terminal would
     * show its idle placeholder controller as "not connected"). A player tab counts as terminal: it
     * lives beside the shells, not in the remote-desktop catalog.
     */
    val activeTerminal: Tab? get() = active?.takeIf { !it.isVnc }

    /** The active tab as seen by the remote-desktop section, `null` when a terminal tab is active. */
    val activeDesktop: Tab? get() = active?.takeIf { it.isVnc }

    /**
     * Put [session] in a tab of its own, append it to the bar and make it active.
     *
     * The tab takes its first session's id. The two are addressed separately everywhere (a pane is
     * always reached as `tabId` + `paneId`), so sharing the id costs nothing and keeps a tab's id
     * stable and recognizable — replacing that pane later gives it an id of its own.
     */
    private fun openTab(session: Session): Tab {
        val tab = Tab(session.id, session)
        tabs = tabs + tab
        activeId = tab.id
        return tab
    }

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
        val tab = openTab(Session(newId(), hostId, title, subtitle, controller))
        controller.bindSessionId(tab.id)
        reportHostSession(hostId)
        controller.connect(target, auth, onConnected)
        return tab.id
    }

    /**
     * Open a blank tab with no session (the "+" button): no connection starts, controller stays in
     * [ConnectionUiState.Form]. Becomes active; gets filled by the first [connect]. Returns its id.
     *
     * [title] is the placeholder tab label; the calling composable resolves the localized label
     * (stringResource is unavailable in the controller). `null` gives an empty label (tests/ad-hoc).
     */
    fun openBlank(title: String? = null): String =
        openTab(Session(newId(), hostId = null, title = title ?: "", subtitle = "", controllerFactory())).id

    /**
     * Connect to [target]: if the active tab is blank ([Tab.isBlank]), fill and connect its pane in
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
            val pane = blank.panes.first()
            pane.bind(hostId, title, subtitle)
            reportHostSession(hostId)
            pane.controller.bindSessionId(blank.id)
            pane.controller.connect(target, auth, onConnected)
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
        val open = openVncSession ?: return null
        return openRemoteDesktop(hostId, title, subtitle, remoteResize, onRemoteResizeChanged) {
            open(target, auth)
        }
    }

    /**
     * Open an RDP tab for [request]. Same shape as [openVnc] — the protocols differ only in what
     * they need to dial, which is why the session itself is opened by an injected lambda.
     */
    fun openRdp(
        hostId: String?,
        title: String,
        subtitle: String,
        request: RdpConnectRequest,
        remoteResize: Boolean = false,
        onRemoteResizeChanged: (Boolean) -> Unit = {},
    ): String? {
        val open = openRdpSession ?: return null
        return openRemoteDesktop(hostId, title, subtitle, remoteResize, onRemoteResizeChanged) {
            open(request)
        }
    }

    /** Shared tab bookkeeping for both remote-desktop protocols. */
    private fun openRemoteDesktop(
        hostId: String?,
        title: String,
        subtitle: String,
        remoteResize: Boolean = false,
        onRemoteResizeChanged: (Boolean) -> Unit = {},
        openSession: suspend () -> RemoteDesktopSession,
    ): String? {
        val controller = vncControllerFactory?.invoke() ?: return null
        // An idle terminal controller keeps `session.controller` non-null for the shared read-sites.
        val session = Session(newId(), hostId, title, subtitle, controllerFactory(), vncController = controller)
        val tab = openTab(session)
        tab.setView(SessionView.Vnc)
        reportHostSession(hostId)
        controller.connect(remoteResize, onRemoteResizeChanged, openSession)
        return tab.id
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
        val tab = openTab(session)
        tab.setView(SessionView.Player)
        return tab.id
    }

    /**
     * Open a colleague's shared session in a tab of its own (never reuses a blank one): the pane
     * shows [viewer] as a live terminal without owning a connection ([ConnectionController.attachSession]).
     * Closing the tab closes the viewer, which is what releases the relay socket. Returns the tab's id.
     */
    fun openShared(title: String, subtitle: String, viewer: TerminalSession): String {
        val controller = controllerFactory()
        val tab = openTab(Session(newId(), hostId = null, title = title, subtitle = subtitle, controller))
        controller.attachSession(viewer)
        return tab.id
    }

    /** Switch the active tab's sub-view (Terminal/SFTP); no-op on a VNC/player tab or with none active. */
    fun setActiveView(view: SessionView) {
        val tab = active ?: return
        // VNC and player tabs are locked to their own view — there is no shell behind them.
        if (tab.isVnc || tab.isPlayer) return
        tab.setView(view)
    }

    /** Make tab [id] active; an unknown id is ignored. */
    fun activate(id: String) {
        if (tabs.any { it.id == id }) activeId = id
    }

    /**
     * Move the tab at [fromIndex] to [toIndex] (titlebar drag-reorder). Both indices must be valid;
     * moving to the same position is a no-op. [activeId] addresses a tab by id, so the active tab
     * doesn't change when reordering.
     */
    fun moveTab(fromIndex: Int, toIndex: Int) {
        val indices = tabs.indices
        if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return
        tabs = tabs.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
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
        val tab = tab(id) ?: return null
        if (tab.isVnc || tab.isPlayer || tab.layout.isFull) return null
        val pane = Session(newId(), hostId = null, title = "", subtitle = "", controllerFactory())
        pane.controller.bindSessionId(pane.id)
        tab.setPanes(tab.panes + pane)
        tab.setLayout(tab.layout.add(pane.id, slot ?: tab.layout.defaultSlot()))
        tab.setFocusedPane(pane.id)
        return pane.id
    }

    /**
     * Connect [target] into pane [paneId] of tab [tabId] and focus it. An empty pane is filled in
     * place; a pane that already holds a session has it disconnected and replaced by a fresh one in
     * the same slot (pointing a pane at another host is how it is re-used). Every pane can be
     * re-pointed, the first one included.
     *
     * A remote desktop or a recording is refused: those tabs hold no shell, and swapping a
     * framebuffer for a terminal under the same tab would leave the tab in neither section.
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
        val tab = tab(tabId) ?: return
        val existing = tab.pane(paneId) ?: return
        if (existing.isVnc || existing.isPlayer) return
        reportHostSession(hostId)
        if (existing.isBlank) {
            existing.bind(hostId, title, subtitle)
            existing.controller.bindSessionId(existing.id)
            tab.setFocusedPane(existing.id)
            existing.controller.connect(target, auth)
            return
        }
        // A pane that already ran a session is replaced wholesale: the controller keeps the state of
        // the connection it opened, so re-using it for another host would carry that history over.
        // Torn down through the one path every removal uses, so a pane kind added later can't be
        // half-released here (the guard above is what keeps a framebuffer out today, not this line).
        existing.teardown()
        val replacement = Session(newId(), hostId, title, subtitle, controllerFactory())
        replacement.controller.bindSessionId(replacement.id)
        tab.setPanes(tab.panes.map { if (it.id == paneId) replacement else it })
        tab.setLayout(tab.layout.replace(paneId, replacement.id))
        tab.setFocusedPane(replacement.id)
        replacement.controller.connect(target, auth)
    }

    /**
     * Move pane [paneId] of tab [tabId] to [slot] — the drop of a pane drag. Panes only move within
     * their own tab.
     */
    fun movePane(tabId: String, paneId: String, slot: PaneSlot) {
        val tab = tab(tabId) ?: return
        tab.setLayout(tab.layout.move(paneId, slot))
    }

    /** Drag the divider under row [boundary] of tab [tabId] by [delta] (share of the tab's height). */
    fun resizePaneRows(tabId: String, boundary: Int, delta: Float) {
        val tab = tab(tabId) ?: return
        tab.setLayout(tab.layout.resizeRows(boundary, delta))
    }

    /** Drag the divider after pane [boundary] of row [row] by [delta] (share of the row's width). */
    fun resizePaneCells(tabId: String, row: Int, boundary: Int, delta: Float) {
        val tab = tab(tabId) ?: return
        tab.setLayout(tab.layout.resizeCells(row, boundary, delta))
    }

    /**
     * Toggle synchronized input on tab [tabId] (active tab by default): while on, what is typed in
     * one pane is mirrored into every other connected pane of the tab. Turning it on with a single
     * pane is allowed — it stays armed for the panes added next.
     */
    fun toggleSyncInput(tabId: String? = activeId) {
        val tab = tab(tabId) ?: return
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
     * Close pane [paneId] of tab [tabId]: tear down its session and take it off the grid; focus
     * moves to the neighbor that follows it on the grid. Closing the last pane of a tab closes the
     * tab itself ([close]) — a tab without panes has nothing to show.
     */
    fun closePane(tabId: String, paneId: String) {
        val tab = tab(tabId) ?: return
        val pane = tab.pane(paneId) ?: return
        if (tab.panes.size == 1) {
            close(tabId)
            return
        }
        // The neighbor is picked before the removal, while the closed pane still has a position.
        val order = tab.layout.paneIds
        val at = order.indexOf(paneId)
        val neighbor = order.getOrNull(at + 1) ?: order.getOrNull(at - 1)
        pane.teardown()
        tab.setPanes(tab.panes - pane)
        tab.setLayout(tab.layout.remove(paneId))
        if (tab.focusedPaneId == paneId) tab.setFocusedPane(neighbor ?: tab.panes.first().id)
    }

    /** Focus pane [paneId] of tab [tabId]; a pane this tab doesn't hold is ignored. */
    fun focusPane(tabId: String, paneId: String) {
        val tab = tab(tabId) ?: return
        if (tab.pane(paneId) != null) tab.setFocusedPane(paneId)
    }

    /**
     * Move the active tab's focus one pane in [direction] ([PaneLayout.neighbor]); `false` when
     * there is no pane that way — an unsplit tab or the edge of the grid.
     */
    fun focusNeighborPane(direction: PaneDirection): Boolean {
        val tab = active ?: return false
        // Only while the grid is what the tab shows: over the file panel or a recording the panes
        // aren't on screen, and moving the focus there would silently re-point the file panel.
        if (tab.view != SessionView.Terminal) return false
        val next = tab.layout.neighbor(tab.focusedPaneId, direction) ?: return false
        if (tab.pane(next) == null) return false
        tab.setFocusedPane(next)
        return true
    }

    /** Close tab [id]: tear down every pane it holds, remove it, select a neighbor. */
    fun close(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs[index].panes.forEach { it.teardown() }
        val remaining = tabs.toMutableList().apply { removeAt(index) }
        if (activeId == id) {
            // The right neighbor shifted into the freed index; else take the left one, else none.
            activeId = remaining.getOrNull(index)?.id ?: remaining.getOrNull(index - 1)?.id
        }
        tabs = remaining
    }

    /**
     * Status of the most recent session for host [hostId] — what the catalog's status dot reads.
     * Remote desktops included: their state lives in their own controller (see [Session.status]).
     */
    fun sessionStatusFor(hostId: String): SessionStatus =
        allSessions.lastOrNull { it.hostId == hostId }?.status ?: SessionStatus.Idle

    /** Close all sessions (panes included) — call on screen teardown to avoid leaking sockets. */
    fun disconnectAll() {
        tabs.forEach { tab -> tab.panes.forEach { it.teardown() } }
        tabs = emptyList()
        activeId = null
    }
}
