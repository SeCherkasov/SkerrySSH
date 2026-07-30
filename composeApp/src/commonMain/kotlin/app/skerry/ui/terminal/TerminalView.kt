package app.skerry.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.LocalAi
import app.skerry.shared.host.Host
import app.skerry.ui.app.LocalConnectPane
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSessionShare
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.share.ShareSessionButton
import app.skerry.ui.share.shareableTeams
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionErrorText
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.share_session
import app.skerry.ui.generated.resources.shell_tip_disconnect
import app.skerry.ui.generated.resources.shell_tip_files
import app.skerry.ui.generated.resources.shell_tip_info
import app.skerry.ui.generated.resources.shell_tip_more_actions
import app.skerry.ui.generated.resources.shell_tip_play
import app.skerry.ui.generated.resources.shell_tip_record
import app.skerry.ui.generated.resources.shell_tip_snippets
import app.skerry.ui.generated.resources.shell_tip_ports
import app.skerry.ui.generated.resources.shell_tip_add_pane
import app.skerry.ui.generated.resources.shell_tip_sync_panes
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_connection_lost
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_launch_local_shell
import app.skerry.ui.generated.resources.local_shell_name
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.generated.resources.term_no_host_selected
import app.skerry.ui.generated.resources.term_pane_sync_badge
import app.skerry.ui.generated.resources.term_no_hosts_in_catalog
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
import app.skerry.ui.runbook.RunbookPaletteButton
import app.skerry.ui.generated.resources.runbook_toolbar_tip
import org.jetbrains.compose.resources.StringResource

/**
 * One entry of the session action row. Panes narrow the row, so when the icons stop fitting the
 * ones listed here give way in this order — the rarely-reached first, the ones a session is steered
 * with last. [Sync], [AddPane] and [Disconnect] are not in the list: they never overflow.
 */
internal enum class ToolbarAction { Play, Record, Share, Runbook, Snippets, Tunnels, Info, Files }

/** Width one icon claims in the row: the button box plus the spacing in front of it. */
private val ACTION_SLOT_WIDTH = 30.dp

/**
 * Room the pane under the row keeps for its own header. Enough for the host label, its address and
 * the status dot — the row gives way into its overflow menu before a pane stops saying which host
 * it is, since that is what the header is there for.
 */
private val PANE_HEADER_ROOM = 220.dp

/**
 * Session action icons (sync / add pane / SFTP / tunnels / snippets / runbooks / recording / player
 * / info panel / disconnect). Pinned to the top-right corner of the terminal area rather than living
 * in a pane header: opening the info panel or a pane narrows the panes, and icons that shift under
 * the pointer are hard to hit twice.
 *
 * [available] is the width of the pane the row floats over, or `null` when there is no grid under it
 * (design preview / empty tab). Once the icons no longer fit beside that pane's own header they
 * collapse into an overflow menu, in the order of [ToolbarAction] — otherwise the row would draw
 * over the header of a pane it does not belong to.
 */
@Composable
private fun SessionActions(state: DesktopDesignState, available: Dp?, modifier: Modifier = Modifier) {
    val sessions = LocalSessions.current
    val tab = sessions?.activeTerminal
    // Session-scoped actions (snippets, runbooks, recording) act on the pane the user is working
    // in, not on the tab's first pane — on a split those are different sessions. Tab-scoped ones
    // (the sync/add-pane toggles and the power button) keep using the tab itself.
    val active = tab?.focusedPane
    val teams = LocalTeams.current
    val syncShown = tab != null && tab.isSplit
    val hidden = overflowedActions(available, syncShown)

    // Files / tunnels / info are stateless, so the overflow menu can run them directly. The palettes
    // and the recorder own their popups and save dialogs, so those are parked below instead and
    // reached through the request signals they already listen on.
    val openSftp = {
        if (sessions != null) { state.clearOverlay(); sessions.setActiveView(SessionView.Sftp) } else state.showView(DesktopView.Sftp)
    }
    val infoAvailable = infoPanelAvailable(
        hasSession = tab != null,
        watched = active?.controller?.isWatched == true,
        mock = sessions == null,
    )
    val playerTabTitle = stringResource(Res.string.term_player_title)
    val onCastOpened: (CastOpenResult) -> Unit = { result ->
        if (result is CastOpenResult.Loaded && sessions != null) {
            state.clearOverlay()
            // The file name labels the tab: it says "recording", and two recordings of the same
            // host stay apart (their in-file titles are both just the host name).
            sessions.openPlayer(result.fileName.ifBlank { playerTabTitle }, result.cast)
        } else {
            state.showCast(result)
        }
    }

    Box(modifier) {
        Row(
            Modifier.height(PANE_HEADER_HEIGHT).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Synchronized input: typing in one pane reaches every connected pane of this tab. Lit
            // while on, since it changes where every keystroke goes. Shown only once the tab is
            // actually split — with a single pane there is nothing to synchronize it with.
            if (syncShown && tab != null) {
                IconBtn(
                    "sync_alt",
                    onClick = { sessions?.toggleSyncInput(tab.id) },
                    tint = if (tab.syncInput) Skerry.colors.cyanBright else Skerry.colors.dim,
                    tooltip = stringResource(Res.string.shell_tip_sync_panes),
                )
            }
            // Add pane: live mode puts another independent session on the active tab's grid (up to
            // MAX_PANES); mock/preview toggles the demo split. Dimmed and inert once the tab is
            // full — the same treatment the info button gets when there is nothing for it to open.
            val canAddPane = tab?.layout?.isFull != true && tab?.isPlayer != true
            IconBtn(
                "splitscreen_right",
                onClick = { if (sessions == null) state.toggleSplit() else if (canAddPane) sessions.addPane() },
                tint = if (canAddPane) Skerry.colors.dim else Skerry.colors.faint,
                tooltip = stringResource(Res.string.shell_tip_add_pane),
            )
            // Switches the active tab's subview (live mode, plus overlay reset) / mock fallback.
            if (ToolbarAction.Files !in hidden) {
                IconBtn("folder", onClick = openSftp, tooltip = stringResource(Res.string.shell_tip_files))
            }
            // Tunnels is a global section, always opens as an overlay.
            if (ToolbarAction.Tunnels !in hidden) {
                IconBtn("lan", onClick = { state.showView(DesktopView.Ports) }, tooltip = stringResource(Res.string.shell_tip_ports))
            }
            // Quick snippet launch into the active session without leaving for the Snippets section.
            if (ToolbarAction.Snippets !in hidden) SnippetPaletteButton(active, state.snippetPaletteRequests)
            // Same idea one size up: start a saved procedure here instead of going to its section.
            if (ToolbarAction.Runbook !in hidden) RunbookPaletteButton(active, state.runbookPaletteRequests)
            // Streams this session to a team over the sync relay (viewers watch; the host decides
            // whether they may type).
            if (ToolbarAction.Share !in hidden) {
                ShareSessionButton(active, LocalSessionShare.current, shareableTeams(), state.sharePanelRequests)
            }
            // Asciinema recording of this session; the stop click offers a Save-As for the .cast.
            if (ToolbarAction.Record !in hidden) {
                RecordSessionButton(
                    active,
                    state.recordingToggleRequests,
                    onSaved = { hostId, seconds -> teams?.reportSessionRecorded(hostId, seconds) },
                ) { state.showRecordingNotice(it) }
            }
            // Plays a .cast back. Not tied to a session (a recording is watched, not run), which is
            // why it sits here rather than behind a connected-only guard. Live mode opens the
            // recording in its own tab, so the shells stay reachable while it plays; the mock path
            // (no session manager) has no tabs and falls back to the overlay.
            if (ToolbarAction.Play !in hidden) PlayRecordingButton(state.castOpenRequests, onCastOpened)
            // Lit while the info panel is open — the only action here with a visible on/off state.
            // The panel is session-scoped, so with no active session there is nothing to show: the
            // button dims and no-ops rather than toggling a panel that can't appear.
            if (ToolbarAction.Info !in hidden) {
                IconBtn(
                    "info",
                    onClick = { if (infoAvailable) state.toggleInfo() },
                    tint = if (state.infoPanel && infoAvailable) Skerry.colors.cyanBright else Skerry.colors.dim,
                    tooltip = stringResource(Res.string.shell_tip_info),
                )
            }
            if (hidden.isNotEmpty()) {
                OverflowActionsButton(
                    hidden = hidden,
                    state = state,
                    infoAvailable = infoAvailable,
                    onOpenSftp = openSftp,
                )
            }
            // Power: closes the active session (live path) with a confirmation prompt
            // (destructive, no auto-reconnect); no-op stub in mock mode.
            IconBtn("power_settings_new", onClick = { if (tab != null) state.requestCloseSession(tab.id) }, tint = Skerry.colors.sunset, tooltip = stringResource(Res.string.shell_tip_disconnect))
        }
        // Parked out of sight, still in composition: these buttons own the palettes, the recorder
        // and the file pickers behind them, and dropping them from the tree would take that state
        // with them — the overflow menu drives them through their request signals instead.
        Box(Modifier.size(0.dp).clipToBounds()) {
            if (ToolbarAction.Snippets in hidden) SnippetPaletteButton(active, state.snippetPaletteRequests)
            if (ToolbarAction.Runbook in hidden) RunbookPaletteButton(active, state.runbookPaletteRequests)
            if (ToolbarAction.Record in hidden) {
                RecordSessionButton(
                    active,
                    state.recordingToggleRequests,
                    onSaved = { hostId, seconds -> teams?.reportSessionRecorded(hostId, seconds) },
                ) { state.showRecordingNotice(it) }
            }
            if (ToolbarAction.Play in hidden) PlayRecordingButton(state.castOpenRequests, onCastOpened)
            if (ToolbarAction.Share in hidden) {
                ShareSessionButton(active, LocalSessionShare.current, shareableTeams(), state.sharePanelRequests)
            }
        }
    }
}

/**
 * Whether the info panel has anything to say about the pane in focus. Everything it shows — host
 * profile, cipher, uptime, live metrics — comes from a connection this app owns, so a pane merely
 * watching a colleague's shared session ([watched]) gets the button dimmed and the panel hidden
 * instead of a column of dashes. [mock] is the preview path with no session backend, where the
 * static layout is the point.
 */
internal fun infoPanelAvailable(hasSession: Boolean, watched: Boolean, mock: Boolean): Boolean =
    if (mock) true else hasSession && !watched

/**
 * Which actions have to leave the row for it to fit beside the header of the pane it floats over.
 * [available] is that pane's width (`null` = no pane under the row, so nothing overflows), and
 * [syncShown] counts the sync toggle, which is only there on a split tab.
 *
 * Pure so the thresholds can be tested without a window: the row must also keep room for the
 * overflow button itself once anything is hidden.
 */
internal fun overflowedActions(available: Dp?, syncShown: Boolean): Set<ToolbarAction> {
    if (available == null) return emptySet()
    val total = ToolbarAction.entries.size + 2 + if (syncShown) 1 else 0 // + add-pane and power
    val room = available - PANE_HEADER_ROOM - 32.dp // the row's own horizontal padding
    val fits = (room / ACTION_SLOT_WIDTH).toInt()
    if (fits >= total) return emptySet()
    // One slot goes to the overflow button; whatever still doesn't fit gives way in enum order.
    val keep = (fits - 1).coerceAtLeast(0)
    val drop = (total - keep).coerceIn(0, ToolbarAction.entries.size)
    return ToolbarAction.entries.take(drop).toSet()
}

/** The "⋯" menu holding the actions that did not fit the row. */
@Composable
private fun OverflowActionsButton(
    hidden: Set<ToolbarAction>,
    state: DesktopDesignState,
    infoAvailable: Boolean,
    onOpenSftp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn("more_horiz", onClick = { open = !open }, tooltip = stringResource(Res.string.shell_tip_more_actions))
        if (open) {
            Popup(alignment = Alignment.TopEnd, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier
                        .padding(top = PANE_HEADER_HEIGHT)
                        .width(220.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Skerry.colors.surface2)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                        .padding(4.dp),
                ) {
                    // Listed the way they sit in the row, so the menu reads as its continuation.
                    hidden.sortedByDescending { it.ordinal }.forEach { action ->
                        val run: () -> Unit = when (action) {
                            ToolbarAction.Files -> onOpenSftp
                            ToolbarAction.Tunnels -> ({ state.showView(DesktopView.Ports) })
                            ToolbarAction.Info -> ({ if (infoAvailable) state.toggleInfo() })
                            ToolbarAction.Snippets -> state::requestSnippetPalette
                            ToolbarAction.Runbook -> state::requestRunbookPalette
                            ToolbarAction.Record -> state::requestRecordingToggle
                            ToolbarAction.Play -> state::requestCastOpen
                            ToolbarAction.Share -> state::requestSharePanel
                        }
                        OverflowActionRow(icon = action.icon, label = stringResource(action.label)) {
                            open = false
                            run()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowActionRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.dim, size = 12.sp)
    }
}

/** The glyph the action carries in the row, reused by its overflow entry. */
private val ToolbarAction.icon: String
    get() = when (this) {
        ToolbarAction.Files -> "folder"
        ToolbarAction.Tunnels -> "lan"
        ToolbarAction.Snippets -> "bolt"
        ToolbarAction.Runbook -> "checklist"
        ToolbarAction.Record -> "radio_button_checked"
        ToolbarAction.Play -> "play_circle"
        ToolbarAction.Share -> "cast"
        ToolbarAction.Info -> "info"
    }

/** The action's own tooltip, reused as its label in the overflow menu. */
private val ToolbarAction.label: StringResource
    get() = when (this) {
        ToolbarAction.Files -> Res.string.shell_tip_files
        ToolbarAction.Tunnels -> Res.string.shell_tip_ports
        ToolbarAction.Snippets -> Res.string.shell_tip_snippets
        ToolbarAction.Runbook -> Res.string.runbook_toolbar_tip
        ToolbarAction.Record -> Res.string.shell_tip_record
        ToolbarAction.Share -> Res.string.share_session
        ToolbarAction.Play -> Res.string.shell_tip_play
        ToolbarAction.Info -> Res.string.shell_tip_info
    }

/** Shared pane header height (panes and the info panel's top strip) so rows align. */
internal val PANE_HEADER_HEIGHT = 40.dp

/** Terminal view: hosts sidebar + main (toolbar, panes, AI bar) + info panel. */
@Composable
fun TerminalView(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        // Slides in/out when toggled from the icon rail (SidebarToggle); expandFrom = End keeps the
        // right edge leading, so the panel visually emerges from under the rail instead of popping.
        AnimatedVisibility(
            visible = !state.sidebarHidden,
            enter = expandHorizontally(expandFrom = Alignment.End),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End),
        ) { HostsSidebar(state) }
        // Reopen handle: a slim strip at the terminal's left edge, shown only while the sidebar is
        // collapsed (its collapse chevron lives in the panel header, which is gone when hidden).
        AnimatedVisibility(
            visible = state.sidebarHidden,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) { SidebarReopenHandle(onClick = state::toggleSidebar) }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Shared live AI bar controller (or null): one instance for the overlay layer and
            // input row; key() recreates it when the active host/policy changes. Off/mock -> null
            // (falls back to the slot below).
            val liveAi = LocalAi.current
            // The AI bar acts on the pane in focus: on a split it explains and runs there.
            val aiSession = LocalSessions.current?.activeTerminal?.focusedPane
            val aiPolicy = aiSession?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
            val aiTerminal = (aiSession?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
            // liveAi.enabled is in the key: toggling the global OFF setting shows/hides the bar
            // without recreating the screen (settings is Compose state, so it recomposes).
            // The pane's id is in it too. terminalController() builds a fresh controller holding the
            // proposed command, and most hosts sit on the same policy, so without the id one
            // controller was shared across panes while `aiTerminal` below followed the focus: a
            // command proposed for one pane would run on whichever pane was focused when Run was
            // pressed. Switching focus now discards an unconfirmed proposal, which is the cheap side
            // of that trade.
            val aiController = key(liveAi, aiPolicy, liveAi?.enabled, aiSession?.id) {
                remember {
                    if (liveAi != null && liveAi.enabled && AiPolicyDecision.of(aiPolicy).aiEnabled) liveAi.terminalController(aiPolicy) else null
                }
            }
            // Width of the pinned action row, measured so the pane it sits over can reserve room
            // for it instead of drawing its own header controls underneath.
            var actionsWidth by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            val sessions = LocalSessions.current
            val tab = sessions?.activeTerminal
            // Width of the pane the pinned actions float over: the row collapses into an overflow
            // menu rather than drawing over that pane's own header. Held here, beside the row
            // itself, since both the grid that measures it and the row that reads it live below.
            var actionsPaneWidth by remember { mutableStateOf<Dp?>(null) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    // With the info panel closed the pinned actions sit over the top-right pane's
                    // header, so that pane reserves room for them; with the panel open they're over it.
                    val reserveEnd = if (state.infoPanel) 0.dp else actionsWidth
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when {
                            // Design preview (offscreen render without a session manager).
                            sessions == null -> MockPanes(state)
                            // Live, but nothing open: an empty header over the "pick a host" screen.
                            tab == null -> Column(Modifier.fillMaxSize()) {
                                PaneHeaderBar(reserveEnd) {}
                                HLine()
                                LivePaneBody(state, pane = null, solo = true, modifier = Modifier.weight(1f).fillMaxWidth())
                            }
                            else -> PaneGrid(sessions, tab, state, reserveEnd) { actionsPaneWidth = it }
                        }
                    }
                    // Same treatment as the hosts sidebar: the panel slides out of the right edge
                    // instead of popping into the layout. shrinkTowards = Start keeps its left edge
                    // leading, so the terminal reflows smoothly as the panel widens.
                    // The panel is entirely about the active session (host / cipher / metrics), so with
                    // no active session it would be a column of "—" placeholders next to the empty-state
                    // screen — hide it there, like the header and AI bar. Mock preview keeps it.
                    AnimatedVisibility(
                        visible = state.infoPanel && infoPanelAvailable(
                            hasSession = tab != null,
                            watched = tab?.focusedPane?.controller?.isWatched == true,
                            mock = sessions == null,
                        ),
                        enter = expandHorizontally(expandFrom = Alignment.Start),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
                    ) { InfoPanel() }
                }
                SessionActions(
                    state,
                    available = if (tab != null) actionsPaneWidth else null,
                    modifier = Modifier.align(Alignment.TopEnd).onGloballyPositioned {
                        actionsWidth = with(density) { it.size.width.toDp() }
                    },
                )
            }
            // Single bar row: command + inline explanation/risk reason + buttons; thinking/blocked/
            // error states share it. Never overlaps the terminal or changes its height. Off/mock -> slot.
            // AI bar only shows with an active session; not shown on the empty "no active session"
            // screen. Design preview (LocalSessions == null) keeps the mock bar.
            if (aiSession != null || LocalSessions.current == null) {
                if (aiController != null) AiBarInput(aiController, aiTerminal, state.aiBarFocusRequests) else TerminalAiBarSlot()
            }
        }
    }
}

/**
 * Slim reopen strip shown at the terminal's left edge while the hosts sidebar is collapsed. Painted
 * in the sidebar's own surface so it reads as the panel peeking out; clicking it restores the panel.
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

// Pane headers.

/**
 * The strip above a pane's terminal: fixed height so every pane's header lines up across the grid,
 * and [reserveEnd] keeps the pinned [SessionActions] from covering the controls of the pane it
 * floats over. [content] is laid out as a row inside it.
 */
@Composable
private fun PaneHeaderBar(
    reserveEnd: Dp,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).background(Skerry.colors.surface2)
            .padding(start = 16.dp, end = 16.dp + reserveEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Static header of the design preview (no session manager behind it). */
@Composable
private fun MockPaneHeader() {
    val mono = LocalFonts.current.mono
    PaneHeaderBar(reserveEnd = 0.dp) {
        Column {
            Txt("root@prod-web-01", color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("192.168.1.45:22", color = Skerry.colors.dim, size = 11.5.sp)
                Txt(" · ", color = Skerry.colors.faint, size = 11.5.sp)
                Txt("●", color = Skerry.colors.moss, size = 11.5.sp)
                Txt(" 04:12:45", color = Skerry.colors.faint, size = 11.5.sp)
            }
            Txt("SSHv2 · aes256-gcm · ed25519", color = Skerry.colors.faint, size = 11.5.sp)
        }
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
 * and terminal; the focused one carries the accent border, and headers drag panes to another slot.
 */
@Composable
private fun PaneGrid(
    sessions: SessionsController,
    tab: Tab,
    state: DesktopDesignState,
    reserveEnd: Dp,
    onActionsPaneWidth: (Dp) -> Unit,
) {
    val density = LocalDensity.current
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
    Column(Modifier.fillMaxSize().onPreviewKeyEvent(onGridKey).onGloballyPositioned { gridSize = it.size }) {
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
                        // Only the pane the pinned actions float over gives them room — and it is
                        // the one whose width decides how many of them stay on screen.
                        val underActions = rowIndex == 0 && columnIndex == row.cells.lastIndex
                        key(pane.id) {
                            PaneCell(
                                sessions, tab, pane, state, drag,
                                row = rowIndex,
                                column = columnIndex,
                                reserveEnd = if (underActions) reserveEnd else 0.dp,
                                modifier = Modifier.weight(cell.weight).fillMaxHeight().then(
                                    if (underActions) {
                                        Modifier.onGloballyPositioned { onActionsPaneWidth(with(density) { it.size.width.toDp() }) }
                                    } else {
                                        Modifier
                                    },
                                ),
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
    reserveEnd: Dp,
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
            // A single-pane tab has nothing to tell apart, so the focus border only shows on a split.
            .then(if (tab.isSplit && focused) Modifier.border(1.dp, Skerry.colors.cyan.copy(alpha = 0.35f)) else Modifier)
            .focusPaneOnPress(sessions, tab.id, pane.id)
            .focusRequester(paneFocus)
            .focusable(),
    ) {
        PaneHeader(sessions, tab, pane, state, drag, reserveEnd)
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LivePaneBody(state, pane, solo = !tab.isSplit, modifier = Modifier.fillMaxSize(), tabId = tab.id, focused = focused)
            drag.drop?.takeIf { it.overPaneId == pane.id }?.let { PaneDropIndicator(it.edge) }
        }
    }
}

/**
 * A pane's header: host label, connection dot, the picker that points the pane at a host, and — on a
 * split tab — the button that closes it. Dragging the header moves the pane to another slot on the
 * grid; the drag only claims the pointer past a dead zone, so the picker still opens on a click.
 *
 * Every pane carries the same header, the first one included: panes are equal, so re-pointing works
 * anywhere. The close button is left off an unsplit tab — there the pane IS the tab, and the tab is
 * closed from its chip or the power button.
 */
@Composable
private fun PaneHeader(
    sessions: SessionsController,
    tab: Tab,
    pane: Session,
    state: DesktopDesignState,
    drag: PaneDragState,
    reserveEnd: Dp,
) {
    val mono = LocalFonts.current.mono
    val connectPane = LocalConnectPane.current
    var pickerOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(Skerry.colors.surface2)) {
        PaneHeaderBar(
            reserveEnd,
            Modifier.draggablePaneHeader(drag, pane.id) { slot -> sessions.movePane(tab.id, pane.id, slot) },
        ) {
            Row(
                Modifier.weight(1f).clickable { pickerOpen = !pickerOpen },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // No chevron next to the label: the whole header is the picker, and on a narrow pane
                // that glyph competed for the room the host's own name needs.
                if (pane.isBlank) {
                    Txt(stringResource(Res.string.term_select_host_placeholder), color = Skerry.colors.faint, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
                } else {
                    // Both stay on one line: a narrow pane (four of them, or the one reserving room
                    // for the pinned actions) would otherwise wrap user@host onto a second row and
                    // push the header out of alignment with its neighbours.
                    Txt(pane.title, color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Txt(
                        pane.subtitle, color = Skerry.colors.dim, size = 11.5.sp, font = mono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Dot(sessionDotColor(pane.controller.uiState))
                    Spacer(Modifier.weight(1f))
                }
            }
            // Synchronized input is marked on every pane it reaches, not just in the toolbar: what
            // makes it dangerous is typing into a pane while forgetting the others are listening.
            if (tab.syncInput && tab.isSplit) SyncInputBadge()
            if (tab.isSplit) {
                // Closing a pane that holds a session is confirmed (its connection goes with it); an
                // empty one has nothing to lose and closes straight away.
                IconBtn(
                    "close",
                    onClick = { if (pane.isBlank) sessions.closePane(tab.id, pane.id) else state.requestClosePane(tab.id, pane.id) },
                    box = 22,
                )
            }
        }
        if (pickerOpen) {
            Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                // A pane that already holds a session is re-pointed only after a confirmation: the
                // old connection goes down with it, and the header is one stray click away from the
                // host list. An empty pane has nothing to lose, so it connects straight away.
                PaneHostPicker { host ->
                    if (pane.isBlank) connectPane(host, pane.id)
                    else state.requestPaneConnect(tab.id, pane.id, host)
                    pickerOpen = false
                }
            }
        }
    }
}

/** Marks a pane that shares its input with the tab's other panes (the toolbar toggle is on). */
@Composable
private fun SyncInputBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Skerry.colors.cyan10).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sym("sync_alt", size = 12.sp, color = Skerry.colors.cyanBright)
        Txt(stringResource(Res.string.term_pane_sync_badge), color = Skerry.colors.cyanBright, size = 10.sp, weight = FontWeight.Medium)
    }
}

/**
 * Host picker from the catalog ([LocalHosts]): clicking a host hands it to [onPick], which either
 * connects it into the pane or asks first (see the call site). Empty outside the vault gate (no
 * live catalog).
 */
@Composable
private fun PaneHostPicker(onPick: (Host) -> Unit) {
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
private fun MockPanes(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MockPaneHeader()
            HLine()
            MockTerminalPane(state, Modifier.weight(1f).fillMaxWidth())
        }
        if (state.split) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Skerry.colors.cyan14))
            SplitPane(Modifier.weight(1f))
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
