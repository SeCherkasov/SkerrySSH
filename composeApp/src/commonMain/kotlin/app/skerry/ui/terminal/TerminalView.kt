package app.skerry.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import app.skerry.ui.design.EmptyState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.design.GhostButton
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
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_connection_lost
import app.skerry.ui.generated.resources.term_connection_lost_detail
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_launch_local_shell
import app.skerry.ui.generated.resources.local_shell_name
import app.skerry.ui.generated.resources.term_no_host_selected
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
import app.skerry.ui.session.Tab
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry
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
                leading = WorkBarLeading.sidebarToggle(state.sidebarHidden, state::toggleSidebar),
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
        // The assistant sits beside the terminal: it is about this session, and a question is asked
        // while its output is in view. Nothing to talk about without a session, so it stays closed
        // there. It slides out of the right edge instead of popping into the layout —
        // shrinkTowards = Start keeps its left edge leading, so the terminal reflows smoothly as
        // the panel widens. The panel is a sibling of the work area, not of the terminal inside it:
        // it runs the full height beside the bar, not under it.
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
internal fun LivePaneBody(
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
    val openPath: ((String) -> Unit)? = remember(sessions, pane?.id, tabId, state.settings.openFilePathsInSftp, pane?.controller?.supportsSftp) {
        val controller = pane?.controller
        when {
            sessions == null || controller == null -> null
            // No SFTP channel on Mosh/Telnet/serial/local/container sessions — offering the path
            // there would only open a panel that can't list anything.
            !state.settings.openFilePathsInSftp || !controller.supportsSftp -> null
            else -> ({ path: String ->
                if (tabId != null) sessions.focusPane(tabId, pane.id)
                controller.requestReveal(path)
                state.clearOverlay()
                sessions.setActiveView(SessionView.Sftp)
            })
        }
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
            onClick = { connect(localTerminalHost(state.settings.localShellPath, localName)) },
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
        // Transport text in English, like the connect-error detail: it names what actually refused.
        // Already sanitised and length-capped where it was captured (see reconnectFailureDetail).
        state.lastError != null -> stringResource(Res.string.term_connection_lost_detail, state.lastError)
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
