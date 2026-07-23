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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalConnectSplit
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
import app.skerry.ui.generated.resources.shell_tip_disconnect
import app.skerry.ui.generated.resources.shell_tip_files
import app.skerry.ui.generated.resources.shell_tip_info
import app.skerry.ui.generated.resources.shell_tip_ports
import app.skerry.ui.generated.resources.shell_tip_split
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_connection_lost
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.generated.resources.term_no_host_selected
import app.skerry.ui.generated.resources.term_no_hosts_in_catalog
import app.skerry.ui.generated.resources.term_notice_not_connected
import app.skerry.ui.generated.resources.term_notice_pick_host_to_connect
import app.skerry.ui.generated.resources.term_notice_pick_or_new
import app.skerry.ui.generated.resources.term_notice_pick_side_by_side
import app.skerry.ui.generated.resources.term_reconnecting
import app.skerry.ui.generated.resources.term_select_host_placeholder
import app.skerry.ui.generated.resources.term_session_closed
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.sessionDotColor
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Session action icons (split / SFTP / tunnels / snippets / info panel / disconnect). Pinned to the
 * top-right corner of the terminal area rather than living in a pane header: opening the info panel
 * or a split narrows the panes, and icons that shift under the pointer are hard to hit twice.
 */
@Composable
private fun SessionActions(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val sessions = LocalSessions.current
    val active = sessions?.active
    Row(
        modifier.height(PANE_HEADER_HEIGHT).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Split: live mode toggles the active tab's split (its own secondary session);
        // mock/preview uses a global flag.
        IconBtn("splitscreen_right", onClick = { if (sessions != null) sessions.toggleSplit() else state.toggleSplit() }, tooltip = stringResource(Res.string.shell_tip_split))
        // Switches the active tab's subview (live mode, plus overlay reset) / mock fallback state.view.
        IconBtn("folder", onClick = { if (sessions != null) { state.clearOverlay(); sessions.setActiveView(SessionView.Sftp) } else state.showView(DesktopView.Sftp) }, tooltip = stringResource(Res.string.shell_tip_files))
        // Tunnels is a global section, always opens as an overlay.
        IconBtn("lan", onClick = { state.showView(DesktopView.Ports) }, tooltip = stringResource(Res.string.shell_tip_ports))
        // Quick snippet launch into the active session without leaving for the Snippets section.
        SnippetPaletteButton(active, state.snippetPaletteRequests)
        // Asciinema recording of this session; the stop click offers a Save-As for the .cast.
        RecordSessionButton(active, state.recordingToggleRequests) { state.showRecordingNotice(it) }
        // Plays a .cast back. Not tied to a session (a recording is watched, not run), which is why
        // it sits here rather than behind a connected-only guard. Live mode opens the recording in
        // its own tab, so the shells stay reachable while it plays; the mock/preview path (no
        // session manager) has no tabs and falls back to the overlay.
        val playerTabTitle = stringResource(Res.string.term_player_title)
        PlayRecordingButton(state.castOpenRequests) { result ->
            if (result is CastOpenResult.Loaded && sessions != null) {
                state.clearOverlay()
                // The file name labels the tab: it says "recording", and two recordings of the same
                // host stay apart (their in-file titles are both just the host name).
                sessions.openPlayer(result.fileName.ifBlank { playerTabTitle }, result.cast)
            } else {
                state.showCast(result)
            }
        }
        // Lit while the info panel is open — the only action here with a visible on/off state. The
        // panel is session-scoped, so with no active session there is nothing to show: the button
        // dims and no-ops rather than toggling a panel that can't appear. Mock preview keeps it live.
        val infoAvailable = active != null || sessions == null
        IconBtn("info", onClick = { if (infoAvailable) state.toggleInfo() }, tint = if (state.infoPanel && infoAvailable) Skerry.colors.cyanBright else Skerry.colors.dim, tooltip = stringResource(Res.string.shell_tip_info))
        // Power: closes the active session (live path) with a confirmation prompt
        // (destructive, no auto-reconnect); no-op stub in mock mode.
        IconBtn("power_settings_new", onClick = { if (active != null) state.requestCloseSession(active.id) }, tint = Skerry.colors.sunset, tooltip = stringResource(Res.string.shell_tip_disconnect))
    }
}

/** Shared pane header height (primary, split, and the info panel's top strip) so rows align. */
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
            val aiSession = LocalSessions.current?.active
            val aiPolicy = aiSession?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
            val aiTerminal = (aiSession?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
            // liveAi.enabled is in the key: toggling the global OFF setting shows/hides the bar
            // without recreating the screen (settings is Compose state, so it recomposes).
            val aiController = key(liveAi, aiPolicy, liveAi?.enabled) {
                remember {
                    if (liveAi != null && liveAi.enabled && AiPolicyDecision.of(aiPolicy).aiEnabled) liveAi.terminalController(aiPolicy) else null
                }
            }
            // Width of the pinned action row, measured so a split pane's own header can reserve
            // room for it instead of drawing its close button underneath.
            var actionsWidth by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                // Live mode: split is tied to the active tab (its own secondary session).
                // Mock/preview uses a global flag.
                val sessions = LocalSessions.current
                val activeId = sessions?.active?.id
                val showSplit = if (sessions != null) sessions.active?.splitOpen == true else state.split
                // In split mode, an accent border (primary cyan) outlines the focused pane.
                // focusedSplit=false means the primary pane.
                val focusedSplit = sessions?.active?.focusedSplit == true
                val focusBorderColor = Skerry.colors.cyan
                fun paneFocusBorder(focused: Boolean): Modifier =
                    if (showSplit && focused) Modifier.border(1.dp, focusBorderColor.copy(alpha = 0.35f)) else Modifier
                // While split is open, clicking the primary pane returns focus to it (tab chip title).
                val primaryMod = Modifier.weight(1f).fillMaxHeight()
                    .then(if (sessions != null && activeId != null && showSplit) Modifier.focusPaneOnPress(sessions, activeId, split = false) else Modifier)
                    .then(paneFocusBorder(!focusedSplit))
                // Primary pane: its own header (toolbar) + terminal, symmetric with the split
                // pane so both headers align.
                Column(primaryMod) {
                    SessionToolbar(state)
                    TerminalPane(state, Modifier.weight(1f).fillMaxWidth())
                }
                if (showSplit) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Skerry.colors.cyan14))
                    val splitMod = Modifier.weight(1f).then(paneFocusBorder(focusedSplit))
                    // With the info panel closed the actions sit over this pane's header, so it
                    // gives them room; with the panel open they're over the panel instead.
                    val reserveEnd = if (state.infoPanel) 0.dp else actionsWidth
                    if (sessions != null) LiveSplitPane(sessions, state, splitMod, reserveEnd) else SplitPane(splitMod)
                }
                // Same treatment as the hosts sidebar: the panel slides out of the right edge
                // instead of popping into the layout. shrinkTowards = Start keeps its left edge
                // leading, so the terminal reflows smoothly as the panel widens.
                // The panel is entirely about the active session (host / cipher / metrics), so with
                // no active session it would be a column of "—" placeholders next to the empty-state
                // screen — hide it there, like the header and AI bar. Mock preview keeps it.
                AnimatedVisibility(
                    visible = state.infoPanel && (sessions == null || activeId != null),
                    enter = expandHorizontally(expandFrom = Alignment.Start),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
                ) { InfoPanel() }
            }
            SessionActions(
                state,
                Modifier.align(Alignment.TopEnd).onGloballyPositioned {
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
private fun SidebarReopenHandle(onClick: () -> Unit) {
    Box(
        Modifier.width(16.dp).fillMaxHeight().background(Skerry.colors.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym("chevron_right", size = 16.sp, color = Skerry.colors.faint)
    }
}

// Session toolbar.

@Composable
private fun SessionToolbar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    Column {
        Row(
            // Fixed header height shared with the split pane (PANE_HEADER_HEIGHT) so both
            // headers align regardless of content.
            Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).background(Skerry.colors.surface2).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active != null) {
                    // Live title: host label + user@addr:port + connection status dot.
                    // Spacing/padding matches the split header (LiveSplitPane) for visual parity.
                    Txt(active.title, color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                    Txt(active.subtitle, color = Skerry.colors.dim, size = 11.5.sp, font = mono)
                    Dot(sessionDotColor(active.controller.uiState))
                } else if (sessions == null) {
                    // Mock/preview (offscreen render without LocalSessions): static header.
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
        HLine()
    }
}

// Terminal pane.

/**
 * Terminal area: live ([LocalSessions] present, behind the vault gate) or mock demo.
 * Live path renders the active session's grid via [TerminalScreen] (VT emulator + PTY input),
 * or a placeholder for other connection states.
 */
@Composable
private fun TerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val sessions = LocalSessions.current
    if (sessions != null) LiveTerminalPane(sessions, modifier) else MockTerminalPane(state, modifier)
}

/** Live terminal of the active tab: renders based on its [ConnectionUiState]. */
@Composable
private fun LiveTerminalPane(sessions: SessionsController, modifier: Modifier = Modifier) {
    val active = sessions.active
    val st = active?.controller?.uiState
    // A live or frozen screen sits on the terminal's own background; every notice (no session /
    // connecting / error) sits on the app background, so the empty terminal matches other sections.
    val onScreen = st is ConnectionUiState.Connected || st is ConnectionUiState.Disconnected
    Box(modifier.fillMaxHeight().fillMaxWidth().background(if (onScreen) Skerry.colors.terminalBg else Skerry.colors.bg)) {
        when (st) {
            null -> TerminalNotice("terminal", stringResource(Res.string.term_no_active_session), stringResource(Res.string.term_notice_pick_host_to_connect))
            // Form state on the active tab means an empty ("+") tab: connection not yet started.
            ConnectionUiState.Form -> TerminalNotice("terminal", stringResource(Res.string.term_notice_not_connected), stringResource(Res.string.term_notice_pick_or_new))
            ConnectionUiState.Connecting -> TerminalNotice("sync", stringResource(Res.string.term_connecting), active.subtitle)
            is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
            is ConnectionUiState.Error -> TerminalNotice("error", stringResource(Res.string.term_connection_failed), connectionErrorText(st), color = Skerry.colors.sunset)
            // Disconnected: screen is frozen at the moment of loss ([ConnectionUiState.Disconnected.terminal]),
            // shown under the disconnect banner so output isn't lost and status (reconnecting/gave up) stays visible.
            is ConnectionUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                TerminalScreen(st.terminal, Modifier.fillMaxSize())
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
private fun TerminalNotice(icon: String, title: String, subtitle: String, color: Color = Skerry.colors.dim) {
    EmptyState(icon = icon, title = title, subtitle = subtitle, tint = color)
}

// Split pane.

/**
 * Live split pane: a second, independent session of the active tab
 * ([Session.splitSession], its own connection/terminal/selection). Header shows its host and a
 * close button ([SessionsController.closeSplit]); until a host is picked, shows a catalog picker
 * ([SplitHostPicker]) that connects a new session via [LocalConnectSplit]. Clicking the body
 * focuses the split pane (tab chip title follows focus).
 */
@Composable
private fun LiveSplitPane(
    sessions: SessionsController,
    state: DesktopDesignState,
    modifier: Modifier = Modifier,
    reserveEnd: Dp = 0.dp,
) {
    val mono = LocalFonts.current.mono
    val parent = sessions.active ?: return
    var pickerOpen by remember { mutableStateOf(false) }
    val split = parent.splitSession
    Column(
        modifier.fillMaxHeight().background(Skerry.colors.terminalBg)
            .focusPaneOnPress(sessions, parent.id, split = true),
    ) {
        Box(Modifier.fillMaxWidth().background(Skerry.colors.surface2)) {
            Row(
                Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT)
                    .padding(start = 16.dp, end = 16.dp + reserveEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Title selector like the primary pane: click opens the catalog picker to choose
                // a host (if empty) or replace the current one (connectSplit closes the previous
                // secondary session). Spacing/padding matches SessionToolbar for visual parity.
                Row(
                    Modifier.weight(1f).clickable { pickerOpen = !pickerOpen },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (split != null) {
                        Txt(split.title, color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                        Txt(split.subtitle, color = Skerry.colors.dim, size = 11.5.sp, font = mono)
                        Dot(sessionDotColor(split.controller.uiState))
                        Spacer(Modifier.weight(1f))
                    } else {
                        Txt(stringResource(Res.string.term_select_host_placeholder), color = Skerry.colors.faint, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
                    }
                    Sym(if (pickerOpen) "expand_less" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
                }
                // Header close button closes this tab's split (drops the secondary connection);
                // confirms only when there's something to drop (host already selected), closes
                // an empty pane immediately.
                IconBtn(
                    "close",
                    onClick = { if (split != null) state.requestCloseSplit(parent.id) else sessions.closeSplit(parent.id) },
                    box = 22,
                )
            }
            if (pickerOpen) {
                Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                    SplitHostPicker { pickerOpen = false }
                }
            }
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = split?.controller?.uiState) {
                null -> TerminalNotice("splitscreen_right", stringResource(Res.string.term_no_host_selected), stringResource(Res.string.term_notice_pick_side_by_side))
                ConnectionUiState.Form -> TerminalNotice("terminal", stringResource(Res.string.term_session_closed), split.subtitle)
                ConnectionUiState.Connecting -> TerminalNotice("sync", stringResource(Res.string.term_connecting), split.subtitle)
                is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
                is ConnectionUiState.Error -> TerminalNotice("error", stringResource(Res.string.term_connection_failed), connectionErrorText(st), color = Skerry.colors.sunset)
                is ConnectionUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                    TerminalScreen(st.terminal, Modifier.fillMaxSize())
                    DisconnectedBanner(st, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * Host picker from the catalog ([LocalHosts]) for the split pane: clicking a host opens a new
 * independent session via [LocalConnectSplit] (same secret-resolution path as the primary
 * connection). Empty outside the vault gate (no live catalog).
 */
@Composable
private fun SplitHostPicker(onPicked: () -> Unit) {
    val mono = LocalFonts.current.mono
    val hosts = LocalHosts.current?.hosts ?: emptyList()
    val connectSplit = LocalConnectSplit.current
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
                    .clickable { connectSplit(host); onPicked() }
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
 * Intercepts press in [PointerEventPass.Initial] (without consuming it): focuses the [split]
 * pane of tab [parentId] so the tab chip title follows the active pane. Keyboard routing stays
 * with [TerminalScreen] (its own focusRequester on pointer-down).
 */
private fun Modifier.focusPaneOnPress(sessions: SessionsController, parentId: String, split: Boolean): Modifier =
    this.pointerInput(sessions, parentId, split) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) sessions.focusPane(parentId, split)
            }
        }
    }
