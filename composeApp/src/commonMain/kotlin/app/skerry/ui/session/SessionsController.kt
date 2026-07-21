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
     * Split: a tab can hold a second, independent session alongside the first. [splitOpen] is
     * whether the split pane is shown (toggled by the toolbar split button); [splitSession] is the
     * secondary pane with its own [ConnectionController] (own connection/terminal/selection), `null`
     * until a host is picked (the pane then shows the host picker). [focusedSplit] is which pane has
     * focus (false = primary, true = split), which determines what host the tab chip shows.
     */
    var splitOpen: Boolean by mutableStateOf(false)
        private set
    var splitSession: Session? by mutableStateOf(null)
        private set
    var focusedSplit: Boolean by mutableStateOf(false)
        private set

    /**
     * A blank tab with no session: no host selected and no connection started yet (controller in
     * [ConnectionUiState.Form]). Created by the "+" button; the first connection fills it. A tab
     * with a host already bound does not become blank again after [ConnectionController.disconnect].
     */
    val isBlank: Boolean get() = hostId == null && vncController == null && playback == null &&
        controller.uiState is ConnectionUiState.Form

    internal fun setView(v: SessionView) { view = v }

    internal fun setSplitOpen(open: Boolean) { splitOpen = open }
    internal fun setSplitSession(session: Session?) { splitSession = session }
    internal fun setFocusedSplit(focused: Boolean) { focusedSplit = focused }

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
) {
    var sessions: List<Session> by mutableStateOf(emptyList())
        private set

    var activeId: String? by mutableStateOf(null)
        private set

    val active: Session? get() = sessions.firstOrNull { it.id == activeId }

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
     * Toggle the split pane of tab [id] (active tab by default): no split open -> open an empty one
     * (shows the host picker); open -> close it via [closeSplit] (tearing down the secondary connection).
     */
    fun toggleSplit(id: String? = activeId) {
        val tab = sessions.firstOrNull { it.id == id } ?: return
        if (tab.splitOpen) closeSplit(tab.id) else tab.setSplitOpen(true)
    }

    /**
     * Connect a new, independent secondary session into the split pane of tab [parentId]: its own
     * [ConnectionController] (own connection/terminal), stored in [Session.splitSession] and given
     * focus. The secondary session is not added to [sessions] — it's owned by the parent tab.
     */
    fun connectSplit(parentId: String, hostId: String?, title: String, subtitle: String, target: SshTarget, auth: SshAuth) {
        val parent = sessions.firstOrNull { it.id == parentId } ?: return
        parent.splitSession?.controller?.disconnect() // replace the previous secondary session, if any
        val secondary = Session(newId(), hostId, title, subtitle, controllerFactory())
        parent.setSplitOpen(true)
        parent.setSplitSession(secondary)
        parent.setFocusedSplit(true)
        secondary.controller.connect(target, auth)
    }

    /** Close the split pane of tab [id]: tear down the secondary connection and reset split flags. */
    fun closeSplit(id: String) {
        val tab = sessions.firstOrNull { it.id == id } ?: return
        tab.splitSession?.controller?.disconnect()
        tab.setSplitSession(null)
        tab.setSplitOpen(false)
        tab.setFocusedSplit(false)
    }

    /** Focus a pane of tab [id]: false = primary, true = split. Determines the tab chip's title. */
    fun focusPane(id: String, split: Boolean) {
        sessions.firstOrNull { it.id == id }?.setFocusedSplit(split)
    }

    /** Close session [id]: disconnect it (and its split), remove the tab, select a neighbor. */
    fun close(id: String) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].controller.disconnect()
        sessions[index].vncController?.disconnect()
        sessions[index].playback?.stop()
        sessions[index].splitSession?.controller?.disconnect()
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

    /** Close all sessions (and their splits) — call on screen teardown to avoid leaking sockets. */
    fun disconnectAll() {
        sessions.forEach {
            it.controller.disconnect()
            it.vncController?.disconnect()
            it.playback?.stop()
            it.splitSession?.controller?.disconnect()
        }
        sessions = emptyList()
        activeId = null
    }
}
