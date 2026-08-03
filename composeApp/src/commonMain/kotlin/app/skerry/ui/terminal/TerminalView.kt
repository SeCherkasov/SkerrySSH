package app.skerry.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import app.skerry.ui.design.EmptyState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.design.GhostButton
import app.skerry.ui.desktop.matchDesktopShortcut
import app.skerry.ui.desktop.paneGridDirection
import app.skerry.ui.host.localTerminalHost
import app.skerry.ui.ai.AssistantPanel
import app.skerry.ui.ai.assistantModelLabel
import app.skerry.ui.app.LocalAi
import app.skerry.shared.host.Host
import app.skerry.ui.app.LocalConnectPane
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionErrorText
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_connection_lost
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_launch_local_shell
import app.skerry.ui.generated.resources.local_shell_name
import app.skerry.ui.generated.resources.term_no_host_selected
import app.skerry.ui.generated.resources.term_no_hosts_in_catalog
import app.skerry.ui.generated.resources.term_pane_change_host
import app.skerry.ui.generated.resources.term_pane_close
import app.skerry.ui.generated.resources.term_pane_menu
import app.skerry.ui.generated.resources.term_notice_not_connected
import app.skerry.ui.generated.resources.term_notice_pick_host_to_connect
import app.skerry.ui.generated.resources.term_notice_pick_or_new
import app.skerry.ui.generated.resources.term_notice_pick_side_by_side
import app.skerry.ui.generated.resources.term_reconnecting
import app.skerry.ui.generated.resources.term_select_host_placeholder
import app.skerry.ui.generated.resources.term_session_closed
import app.skerry.ui.session.PaneDragState
import app.skerry.ui.session.PaneEdge
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.Tab
import app.skerry.ui.session.draggablePaneHeader
import app.skerry.ui.session.paneBoundsAnchor
import app.skerry.ui.session.paneResizeCursor
import app.skerry.ui.session.sessionDotColor
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection
import app.skerry.ui.host.isProdHostId
import app.skerry.ui.host.prodOutline

/** Height of a pane's own header on a split grid; a single-pane tab is named by the [WorkBar]. */
internal val PANE_HEADER_HEIGHT = 26.dp

/** Terminal view: hosts sidebar + work area (bar, panes) + info and assistant panels. */
@Composable
fun TerminalView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    val tab = sessions?.activeTerminal
    val liveAi = LocalAi.current
    // The assistant answers about the pane in focus: on a split it reads and runs there.
    val aiSession = tab?.focusedPane
    val aiPolicy = aiSession?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
    val aiTerminal = (aiSession?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    // Conversations are per pane and outlive this composition: the store belongs to the assistant
    // itself, so opening SFTP or the vault (which takes this view off screen) leaves the threads
    // intact, and a provider change closes them there (see AiAssistantController.sessionAssistants).
    val assistants = liveAi?.takeIf { it.enabled }?.sessionAssistants
    // Off for this host (or globally) hides the panel and its toolbar button entirely.
    val assistantController = aiSession?.let { session ->
        assistants?.takeIf { AiPolicyDecision.of(aiPolicy).aiEnabled }?.controller(session.id, aiPolicy)
    }
    val assistantVisible = state.assistantPanel && assistantController != null
    // A closed pane's conversation is dropped with it, so closing tabs doesn't accumulate
    // controllers (and a request left in flight there is cancelled).
    val openPaneIds = sessions?.tabs?.flatMap { it.panes.map { pane -> pane.id } }?.toSet()
    LaunchedEffect(assistants, openPaneIds) {
        if (openPaneIds != null) assistants?.retain(openPaneIds)
    }
    val density = LocalDensity.current
    // Width of the work area, which is the width of the bar over it: the action row collapses into
    // an overflow menu rather than squeezing the title out of the bar.
    var workAreaWidth by remember { mutableStateOf<Dp?>(null) }
    Row(Modifier.fillMaxSize()) {
        // Slides in/out when toggled from the bar's chevron (or the icon rail); expandFrom = End
        // keeps the right edge leading, so the panel emerges from under the rail instead of popping.
        AnimatedVisibility(
            visible = !state.sidebarHidden,
            enter = expandHorizontally(expandFrom = Alignment.End),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End),
        ) {
            // The catalog belongs to the rail, not to what's on screen: a shell keeps running while
            // the user browses the desktops list beside it (see workAreaSection).
            HostsSidebar(state, state.section)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                workAreaWidth = with(density) { it.size.width.toDp() }
            },
        ) {
            WorkBar(
                label = activeWorkBarLabel(state, tab, soloPlaceholder = stringResource(Res.string.term_select_host_placeholder)),
                tabKey = tab?.id,
                sidebarHidden = state.sidebarHidden,
                onToggleSidebar = state::toggleSidebar,
                onPickHost = soloHostPicker(state, tab),
                actions = {
                    SessionActions(state, available = workAreaWidth, assistantShown = assistantController != null)
                },
            )
            when {
                // Design preview (offscreen render without a session manager).
                sessions == null -> MockPanes(state)
                // Live, but nothing open: the "pick a host" screen under a bar with no title.
                tab == null -> LivePaneBody(state, pane = null, solo = true, modifier = Modifier.weight(1f).fillMaxWidth())
                else -> PaneGrid(sessions, tab, state)
            }
        }
        // Same treatment as the hosts sidebar: the panel slides out of the right edge instead of
        // popping into the layout. shrinkTowards = Start keeps its left edge leading, so the
        // terminal reflows smoothly as the panel widens. Both panels are siblings of the work area,
        // not of the terminal inside it: they run the full height beside the bar, not under it.
        // The panel is entirely about the active session (host / cipher / metrics), so with no
        // active session it would be a column of "—" placeholders next to the empty-state screen —
        // hide it there, like the pane headers. Mock preview keeps it.
        AnimatedVisibility(
            visible = state.infoPanel && infoPanelAvailable(
                hasSession = tab != null,
                watched = tab?.focusedPane?.controller?.isWatched == true,
                mock = sessions == null,
            ),
            enter = expandHorizontally(expandFrom = Alignment.Start),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) { InfoPanel() }
        // The assistant sits beside the terminal, the same way the info panel does: it is about this
        // session, and a question is asked while its output is in view. Nothing to talk about
        // without a session, so it stays closed there.
        AnimatedVisibility(
            visible = assistantVisible,
            enter = expandHorizontally(expandFrom = Alignment.Start),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            assistantController?.let { controller ->
                // Keyed on the conversation: the draft question, the feed's scroll position and the
                // context menu belong to the pane that was asked about. Without the key they would
                // sit in the same composition slot and follow the focus to another pane — a question
                // typed for one host would be sent to another, with that other host's output
                // attached.
                key(controller) {
                    AssistantPanel(
                        controller = controller,
                        terminal = aiTerminal,
                        focusPending = state.assistantFocusPending,
                        onFocusConsumed = state::consumeAssistantFocus,
                        modelLabel = liveAi?.let { assistantModelLabel(it) }.orEmpty(),
                    )
                }
            }
        }
    }
}

/**
 * What the bar over the work area says: the live tab's panes, the static preview's when there is no
 * session manager, or nothing at all while no tab is open. A pane with no host yet is named by
 * [soloPlaceholder] rather than by its empty label, since clicking that title is how a host is
 * picked for it.
 */
@Composable
private fun activeWorkBarLabel(state: DesktopDesignState, tab: Tab?, soloPlaceholder: String): WorkBarLabel? = when {
    LocalSessions.current == null -> mockWorkBarLabel(state.split)
    tab == null -> null
    else -> workBarLabel(
        tab.panes.map { pane ->
            paneFacts(pane.title, pane.subtitle, pane.status, blank = pane.isBlank, placeholder = soloPlaceholder)
        },
        syncInput = tab.syncInput,
    )
}

/**
 * Re-points a single-pane tab from the bar's title, which is that pane's header. A split tab has a
 * header per pane and picks there instead, so this is `null` — as it is with nothing open, where
 * there is no pane to point anywhere.
 */
@Composable
private fun soloHostPicker(state: DesktopDesignState, tab: Tab?): ((Host) -> Unit)? {
    val connectPane = LocalConnectPane.current
    if (tab == null || tab.isSplit || tab.isPlayer) return null
    val pane = tab.panes.first()
    return { host ->
        // A pane that already holds a session is re-pointed only after a confirmation: the old
        // connection goes down with it. An empty pane has nothing to lose and connects straight away.
        if (pane.isBlank) connectPane(host, pane.id) else state.requestPaneConnect(tab.id, pane.id, host)
    }
}

/**
 * Slim reopen strip shown at a view's left edge while the hosts sidebar is collapsed. Painted in
 * the sidebar's own surface so it reads as the panel peeking out; clicking it restores the panel.
 * The terminal reopens from the work bar's chevron instead; this is what the remote-desktop view
 * still uses, which has no bar of its own yet.
 */
@Composable
internal fun SidebarReopenHandle(onClick: () -> Unit) {
    Box(
        Modifier.width(16.dp).fillMaxHeight().background(Skerry.colors.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym("chevron_right", size = 16.sp, color = Skerry.colors.faint)
    }
}

// Pane body.

/**
 * The terminal area of one pane: the session's grid via [TerminalScreen], or a placeholder for the
 * other connection states. [pane] is `null` only when the tab bar is empty (nothing is open at all).
 *
 * [solo] means the pane is the tab's only one, which is what the empty-state text speaks to: a tab
 * that never connected reads as "pick a host or open a tab", while an empty pane on a split grid is
 * about that pane alone.
 */
@Composable
private fun LivePaneBody(
    state: DesktopDesignState,
    pane: Session?,
    solo: Boolean,
    modifier: Modifier = Modifier,
    tabId: String? = null,
    focused: Boolean = true,
) {
    val sessions = LocalSessions.current
    val st = pane?.controller?.uiState
    // Ctrl+click on a path opens it in the file panel — which follows the focused pane, so the pane
    // is focused first and the path is resolved on the host the user clicked in, not on a sibling.
    val openPath: ((String) -> Unit)? = remember(sessions, pane?.id, tabId, state.openFilePathsInSftp, pane?.controller?.supportsSftp) {
        val controller = pane?.controller
        // No SFTP channel on Mosh/Telnet/serial/local/container sessions — offering the path there
        // would only open a panel that can't list anything.
        if (sessions == null || !state.openFilePathsInSftp || controller == null || !controller.supportsSftp) null
        else ({ path: String ->
            if (tabId != null) sessions.focusPane(tabId, pane.id)
            controller.requestReveal(path)
            state.clearOverlay()
            sessions.setActiveView(SessionView.Sftp)
        })
    }
    // A live or frozen screen sits on the terminal's own background; every notice (no session /
    // connecting / error) sits on the app background, so the empty terminal matches other sections.
    val onScreen = st is ConnectionUiState.Connected || st is ConnectionUiState.Disconnected
    // "Launch local shell": on an empty terminal, open a local-shell session on this machine (its
    // shell path comes from Settings → Terminal → Local shell). LOCAL needs no auth, so the connect
    // reuses the current blank tab in place (SessionsController.connect).
    val connect = LocalConnectHost.current
    val localName = stringResource(Res.string.local_shell_name)
    val launchLocalShell: (@Composable () -> Unit) = {
        GhostButton(
            stringResource(Res.string.term_launch_local_shell),
            onClick = { connect(localTerminalHost(state.localShellPath, localName)) },
            icon = "terminal",
        )
    }
    // Production sessions get a red outline around the whole pane — the guard's resting state, so a
    // command lands in the wrong window only after ignoring a full-height red frame.
    Box(
        modifier.fillMaxHeight().fillMaxWidth()
            .background(if (onScreen) Skerry.colors.terminalBg else Skerry.colors.bg)
            .prodOutline(isProdHostId(pane?.hostId)),
    ) {
        when (st) {
            null -> TerminalNotice("terminal", stringResource(Res.string.term_no_active_session), stringResource(Res.string.term_notice_pick_host_to_connect), action = launchLocalShell)
            // Form state means no connection started yet: on a tab's only pane that is an empty
            // ("+") tab, on an added pane it is one waiting for a host to be picked in its header.
            ConnectionUiState.Form -> when {
                pane.isBlank && solo -> TerminalNotice("terminal", stringResource(Res.string.term_notice_not_connected), stringResource(Res.string.term_notice_pick_or_new), action = launchLocalShell)
                pane.isBlank -> TerminalNotice("splitscreen_right", stringResource(Res.string.term_no_host_selected), stringResource(Res.string.term_notice_pick_side_by_side))
                else -> TerminalNotice("terminal", stringResource(Res.string.term_session_closed), pane.subtitle)
            }
            ConnectionUiState.Connecting -> TerminalNotice("sync", stringResource(Res.string.term_connecting), pane.subtitle)
            // The "… is typing" hint rides along inside the screen, which is what knows where the
            // cursor is; the share's controls live in the toolbar's panel, not over the terminal.
            is ConnectionUiState.Connected -> TerminalScreen(
                st.terminal,
                Modifier.fillMaxSize(),
                focused = focused,
                cursorOverlay = rememberTypingHint(pane.id),
                onOpenPath = openPath,
            )
            is ConnectionUiState.Error -> TerminalNotice("error", stringResource(Res.string.term_connection_failed), connectionErrorText(st), color = Skerry.colors.sunset)
            // Disconnected: screen is frozen at the moment of loss ([ConnectionUiState.Disconnected.terminal]),
            // shown under the disconnect banner so output isn't lost and status (reconnecting/gave up) stays visible.
            // No path affordance here: the SFTP channel died with the session, so a click would only
            // open a panel that can't list anything.
            is ConnectionUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                TerminalScreen(st.terminal, Modifier.fillMaxSize(), focused = focused)
                DisconnectedBanner(st, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/**
 * Closed-state banner over the frozen terminal. Clean shell exit (`exit`) shows neutral
 * "Session closed"; during auto-reconnect, amber "Reconnecting… #N"; once attempts are
 * exhausted, red "Connection lost".
 */
@Composable
private fun DisconnectedBanner(state: ConnectionUiState.Disconnected, modifier: Modifier = Modifier) {
    val color = when {
        state.cleanExit -> Skerry.colors.dim
        state.reconnecting -> Skerry.colors.amber
        else -> Skerry.colors.sunset
    }
    val icon = when {
        state.cleanExit -> "power_settings_new"
        state.reconnecting -> "sync"
        else -> "link_off"
    }
    val text = when {
        state.cleanExit -> stringResource(Res.string.term_session_closed)
        state.reconnecting -> stringResource(Res.string.term_reconnecting, state.attempt)
        else -> stringResource(Res.string.term_connection_lost)
    }
    TerminalOverlayBanner(icon = icon, text = text, accent = color, background = Skerry.colors.bannerScrim, modifier = modifier)
}

/**
 * Centered message over the terminal background (no session / connecting / error). Delegates to the
 * shared [EmptyState] so the terminal's empty screen matches every other section's; [color] tints
 * the glyph (red for errors).
 */
@Composable
private fun TerminalNotice(icon: String, title: String, subtitle: String, color: Color = Skerry.colors.dim, action: (@Composable () -> Unit)? = null) {
    EmptyState(icon = icon, title = title, subtitle = subtitle, tint = color, action = action)
}

// Pane grid.

/** Grabbable width of a divider between panes; the line drawn inside it stays a hairline. */
private val PANE_DIVIDER_GRIP = 6.dp

/**
 * The active tab's panes on its grid ([Session.layout]): rows top to bottom, panes left to
 * right inside a row, dividers in between. Each pane is an independent session with its own header
 * and terminal; the focused one carries the accent strip, and headers drag panes to another slot.
 */
@Composable
private fun ColumnScope.PaneGrid(
    sessions: SessionsController,
    tab: Tab,
    state: DesktopDesignState,
) {
    // Per tab: one tab's pane geometry must not resolve drops on another's grid.
    val drag = remember(tab.id) { PaneDragState() }
    // Divider drags arrive in pixels but the layout is in shares of the grid, so its size is the
    // conversion factor — measured here, since rows are full-width and the column is full-height.
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = tab.layout
    // Keyboard navigation between panes. Preview events reach here on their way down to the focused
    // terminal, so the chord is claimed only while the keyboard is inside the grid — in a text field,
    // on the file panel or on a remote desktop the same keys keep their usual meaning. Claimed even
    // when there is no pane that way (unsplit tab, edge of the grid): letting it reach the terminal
    // would send ESC[1;6D, which a shell that doesn't know the sequence echoes as a stray "D".
    val onGridKey: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val shortcut = matchDesktopShortcut(
                event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
            )
            val direction = paneGridDirection(shortcut, searchOpen = tab.focusedPane.liveTerminal?.searchQuery != null)
            if (direction == null) false else { sessions.focusNeighborPane(direction); true }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent(onGridKey).onGloballyPositioned { gridSize = it.size },
    ) {
        layout.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                PaneDivider(vertical = false) { px ->
                    if (gridSize.height > 0) sessions.resizePaneRows(tab.id, rowIndex - 1, px / gridSize.height)
                }
            }
            Row(Modifier.weight(row.weight).fillMaxWidth()) {
                row.cells.forEachIndexed { columnIndex, cell ->
                    if (columnIndex > 0) {
                        PaneDivider(vertical = true) { px ->
                            if (gridSize.width > 0) sessions.resizePaneCells(tab.id, rowIndex, columnIndex - 1, px / gridSize.width)
                        }
                    }
                    val pane = tab.pane(cell.paneId)
                    if (pane != null) {
                        key(pane.id) {
                            PaneCell(
                                sessions, tab, pane, state, drag,
                                row = rowIndex,
                                column = columnIndex,
                                modifier = Modifier.weight(cell.weight).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One pane of the grid: its header, its terminal, and the drop indicator while a pane is dragged. */
@Composable
private fun PaneCell(
    sessions: SessionsController,
    tab: Tab,
    pane: Session,
    state: DesktopDesignState,
    drag: PaneDragState,
    row: Int,
    column: Int,
    modifier: Modifier = Modifier,
) {
    val focused = tab.focusedPaneId == pane.id
    // Geometry is dropped when the pane leaves the grid, which also aborts a drag of that pane: a
    // pane can go away from outside its own header (closing its tab, locking the vault), and the
    // gesture coroutine dies with it without an onDragEnd to clear the indicator.
    DisposableEffect(pane.id) { onDispose { drag.paneClosed(pane.id) } }
    // A pane that draws no live terminal (blank, connecting, failed, or a frozen dead session) never
    // claims the keyboard ([TerminalScreen] requests focus only for a live, focused one). Focusing it
    // must still take the keyboard away from the pane that had it — otherwise the accent border, the
    // tab chip and the snippet target move here while typing keeps going into a sibling's live shell.
    // The empty pane itself holds the focus (rather than it being cleared), so the grid's key handler
    // still sees the arrow chord and the user can move on to a pane that does have a shell.
    // Keyed on takesKeyboard too: a focused pane whose session drops keeps its id and stays focused,
    // but its live [TerminalScreen] is composed from another branch and dies with the keyboard focus,
    // and the frozen one that replaces it never claims it back. Without this key nothing inside the
    // grid holds focus after a drop, and the arrow chord goes dead until the user clicks a pane.
    val paneFocus = remember { FocusRequester() }
    val takesKeyboard = pane.controller.uiState is ConnectionUiState.Connected
    LaunchedEffect(focused, takesKeyboard) { if (focused && !takesKeyboard) paneFocus.requestFocus() }
    Column(
        modifier
            .paneBoundsAnchor(drag, pane.id, row, column)
            .focusPaneOnPress(sessions, tab.id, pane.id)
            .focusRequester(paneFocus)
            .focusable(),
    ) {
        // A single-pane tab has nothing to tell apart and no header of its own: the work bar names
        // it, and the pane starts at the terminal. Only a split grid needs a header per pane.
        if (tab.isSplit) {
            PaneHeader(sessions, tab, pane, state, drag, focused)
            HLine()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LivePaneBody(state, pane, solo = !tab.isSplit, modifier = Modifier.fillMaxSize(), tabId = tab.id, focused = focused)
            drag.drop?.takeIf { it.overPaneId == pane.id }?.let { PaneDropIndicator(it.edge) }
        }
    }
}

/**
 * A pane's header on a split grid: status dot, host label, and the "⋮" menu that re-points or closes
 * it. Dragging the header moves the pane to another slot; the drag only claims the pointer past a
 * dead zone, so the picker still opens on a click.
 *
 * The address is left to the work bar, which lists every host of the tab: four panes side by side
 * make the strip narrow, and a host name that ellipsises into `prod-w…` says less than nothing.
 * [focused] marks the pane the keyboard is in with an accent strip on the header's leading edge.
 */
@Composable
private fun PaneHeader(
    sessions: SessionsController,
    tab: Tab,
    pane: Session,
    state: DesktopDesignState,
    drag: PaneDragState,
    focused: Boolean,
) {
    val mono = LocalFonts.current.mono
    val connectPane = LocalConnectPane.current
    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // A pane that already holds a session is re-pointed only after a confirmation: the old
    // connection goes down with it, and the header is one stray click away from the host list. An
    // empty pane has nothing to lose, so it connects straight away.
    val pick: (Host) -> Unit = { host ->
        if (pane.isBlank) connectPane(host, pane.id) else state.requestPaneConnect(tab.id, pane.id, host)
        pickerOpen = false
    }
    Box(Modifier.fillMaxWidth().background(Skerry.colors.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PANE_HEADER_HEIGHT)
                .draggablePaneHeader(drag, pane.id) { slot -> sessions.movePane(tab.id, pane.id, slot) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The focus marker is the strip rather than a border around the whole pane: on a grid of
            // four the borders met in the middle and it took a second look to see which one was lit.
            Box(Modifier.width(2.dp).fillMaxHeight().background(if (focused) Skerry.colors.teal else Color.Transparent))
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable { pickerOpen = !pickerOpen }.padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(sessionDotColor(pane.status))
                if (pane.isBlank) {
                    Txt(stringResource(Res.string.term_select_host_placeholder), color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Txt(
                        pane.title,
                        color = if (focused) Skerry.colors.text else Skerry.colors.dim,
                        size = 11.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Box {
                IconBtn("more_vert", onClick = { menuOpen = !menuOpen }, box = 20, icon = 13.sp, tint = Skerry.colors.faint, tooltip = stringResource(Res.string.term_pane_menu))
                if (menuOpen) PaneMenu(onDismiss = { menuOpen = false }, onChangeHost = { pickerOpen = true }) {
                    // Closing a pane that holds a session is confirmed (its connection goes with
                    // it); an empty one has nothing to lose and closes straight away.
                    if (pane.isBlank) sessions.closePane(tab.id, pane.id) else state.requestClosePane(tab.id, pane.id)
                }
            }
        }
        if (pickerOpen) {
            Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                PaneHostPicker(onPick = pick)
            }
        }
    }
}

/** What can be done to one pane of a split: point it at another host, or close it. */
@Composable
private fun PaneMenu(onDismiss: () -> Unit, onChangeHost: () -> Unit, onClose: () -> Unit) {
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .padding(top = PANE_HEADER_HEIGHT)
                .width(180.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Skerry.colors.surface2)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                .padding(4.dp),
        ) {
            MenuActionRow("dns", stringResource(Res.string.term_pane_change_host)) { onDismiss(); onChangeHost() }
            MenuActionRow("close", stringResource(Res.string.term_pane_close)) { onDismiss(); onClose() }
        }
    }
}

/**
 * Host picker from the catalog ([LocalHosts]): clicking a host hands it to [onPick], which either
 * connects it into the pane or asks first (see the call site). Empty outside the vault gate (no
 * live catalog).
 */
@Composable
internal fun PaneHostPicker(onPick: (Host) -> Unit) {
    val mono = LocalFonts.current.mono
    // Terminal profiles only: a pane is a shell, and a remote desktop picked here would be dialled
    // as SSH on its RFB port.
    val hosts = LocalHosts.current?.hosts?.inSection(HostSection.Terminal) ?: emptyList()
    Column(
        Modifier
            .width(240.dp)
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        if (hosts.isEmpty()) {
            Txt(stringResource(Res.string.term_no_hosts_in_catalog), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        hosts.forEach { host ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { onPick(host) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("dns", size = 14.sp, color = Skerry.colors.cyanBright)
                Txt(host.label, color = Skerry.colors.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Divider between two panes: a hairline drawn inside a wider grip, so it can be grabbed without
 * aiming at a single pixel. Dragging it hands the travel (in pixels) to the caller, which turns it
 * into a share of the grid.
 */
@Composable
private fun PaneDivider(vertical: Boolean, onDrag: (Float) -> Unit) {
    val dragState = rememberDraggableState { onDrag(it) }
    // The hairline is easy to miss, so the cursor is what announces the divider: it turns into the
    // resize arrow of the axis it moves on as soon as the pointer is over the grip.
    val cursor = remember(vertical) { paneResizeCursor(vertical) }
    if (vertical) {
        Box(
            Modifier.width(PANE_DIVIDER_GRIP).fillMaxHeight()
                .pointerHoverIcon(cursor)
                .draggable(dragState, Orientation.Horizontal),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(1.dp).fillMaxHeight().background(Skerry.colors.cyan14)) }
    } else {
        Box(
            Modifier.height(PANE_DIVIDER_GRIP).fillMaxWidth()
                .pointerHoverIcon(cursor)
                .draggable(dragState, Orientation.Vertical),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.height(1.dp).fillMaxWidth().background(Skerry.colors.cyan14)) }
    }
}

/** Where a dragged pane would land: an accent bar on the edge of the pane under the pointer. */
@Composable
private fun BoxScope.PaneDropIndicator(edge: PaneEdge) {
    val bar = when (edge) {
        PaneEdge.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp)
        PaneEdge.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
        PaneEdge.Left -> Modifier.align(Alignment.CenterStart).fillMaxHeight().width(3.dp)
        PaneEdge.Right -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp)
    }
    Box(bar.background(Skerry.colors.cyanBright))
}

/** Both panes of the design preview: the mock terminal and, behind the demo flag, a second one. */
@Composable
private fun ColumnScope.MockPanes(state: DesktopDesignState) {
    Row(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Headers only on the split, the same rule the live grid follows.
            if (state.split) {
                MockPaneHeader("root@prod-web-01", focused = true)
                HLine()
            }
            MockTerminalPane(state, Modifier.weight(1f).fillMaxWidth())
        }
        if (state.split) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Skerry.colors.cyan14))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                MockPaneHeader("root@db-master", focused = false)
                HLine()
                SplitPane(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * Intercepts press in [PointerEventPass.Initial] (without consuming it): focuses pane [paneId] of
 * tab [tabId], so the tab chip title and everything else that follows the focused pane keep up with
 * where the user is working. Keyboard routing stays with [TerminalScreen] (its own focusRequester on
 * pointer-down).
 */
private fun Modifier.focusPaneOnPress(sessions: SessionsController, tabId: String, paneId: String): Modifier =
    this.pointerInput(sessions, tabId, paneId) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) sessions.focusPane(tabId, paneId)
            }
        }
    }
