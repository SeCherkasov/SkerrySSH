package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.host.Host
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionErrorText
import app.skerry.ui.immersive.ImmersiveScreen
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.RecordingOutcome
import app.skerry.shared.share.ShareFrame
import app.skerry.ui.app.LocalSessionShare
import app.skerry.ui.generated.resources.share_session
import app.skerry.ui.generated.resources.share_session_stop
import app.skerry.ui.share.ShareSource
import app.skerry.ui.share.ShareUiState
import app.skerry.ui.share.shareableTeams
import app.skerry.ui.share.viewersMayOnlyWatch
import app.skerry.ui.terminal.recordingOutcomeMessage
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.term_mobile_title_fallback
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.term_broadcast_title
import app.skerry.ui.generated.resources.term_record_start
import app.skerry.ui.generated.resources.term_record_stop
import app.skerry.ui.generated.resources.term_palette_title
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_mobile_open_host_connect
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_disconnect
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.mobileTabBarUnderRoute
import app.skerry.ui.design.Txt
import app.skerry.ui.terminal.filePathFromSelection
import app.skerry.ui.session.broadcastTargets
import kotlinx.coroutines.launch
import app.skerry.shared.terminal.castFileName
import app.skerry.shared.terminal.recordingStamp
import app.skerry.ui.vault.ExportOutcome
import app.skerry.ui.vault.exportFileGuarded
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.isProdHostId
import app.skerry.ui.host.prodOutline
import app.skerry.ui.host.rememberProductionLookup

/** ESC (0x1B) — prefix of arrow CSI sequences and the esc key itself. */
internal const val ESC = "\u001b"


/**
 * Full-screen mobile terminal push-screen (a live SSH session over the PTY core). Header with host name
 * and status → body by the active session's connection state ([LocalSessions]) → special-key panel. A
 * connected session's body renders the real grid via the shared [TerminalScreen] in IME mode (like the
 * desktop `LiveTerminalPane`).
 *
 * The session is opened by Connect on [MobileHostDetailScreen] (via `LocalConnectHost`); the back arrow
 * just returns to the list (the session stays alive), while Disconnect in the `more_horiz` menu drops it
 * and closes the screen. AI bar/cards are behind [FeatureFlags.ai]. No split mode on phone — the
 * `splitscreen` icon is removed from the header.
 */
@Composable
fun MobileTerminalScreen(state: MobileDesignState) {
    val sessions = LocalSessions.current
    // The phone shows one session at a time: no pane grid, so the active tab's focused pane is it.
    val tab = sessions?.active
    val active = tab?.focusedPane
    // Teams: a saved recording of a shared host is reported to its team (see the record toggle below).
    val teams = LocalTeams.current
    // Stable Disconnect lambda (recreated only on session change): drops the connection and returns to
    // the list — the back arrow leaves the session alive, Disconnect closes it.
    val onDisconnect = remember(tab?.id, sessions) {
        tab?.let { t -> { sessions.close(t.id); state.pop() } }
    }
    // Clean shell exit (`exit`) on phone: close the session and return to the host list — a full-screen
    // push terminal has no reason to hang frozen (unlike desktop, which keeps a "Session closed" card).
    // A transport drop doesn't reach here (cleanExit=false) — the screen lives on there.
    val cleanlyExited = (active?.controller?.uiState as? ConnectionUiState.Disconnected)?.cleanExit == true
    LaunchedEffect(active?.id, cleanlyExited) {
        if (cleanlyExited) {
            sessions.close(tab.id)
            state.pop()
        }
    }
    // sticky-ctrl is lifted to screen level so the key panel's arming also affects soft-keyboard input
    // (the IME path bypasses the panel). Reset on session change.
    var ctrlArmed by remember(active?.id) { mutableStateOf(false) }
    // The AI input is off by default and raised by the sparkle key: on a phone it is a whole row of
    // screen that most sessions never use, and the terminal wants every line it can get.
    var aiOpen by remember(active?.id) { mutableStateOf(false) }
    // Callbacks are stabilized by remember (keyed on session), else a fresh lambda per PTY chunk would
    // repaint the key panel/terminal for nothing. `ctrlArmed` is compose-state, so the lambda body sees
    // its live value even through remember.
    val setCtrlArmed = remember(active?.id) { { v: Boolean -> ctrlArmed = v } }
    val imeTransform = remember(active?.id) {
        { raw: String ->
            // Armed ctrl applies to the first soft-keyboard char and is disarmed immediately (raw is
            // always non-empty here — TerminalScreen calls imeTransform only on real input).
            val out = applyStickyCtrl(ctrlArmed, raw)
            if (ctrlArmed) ctrlArmed = false
            out
        }
    }
    // The snippet palette (`bolt` icon in the header) lives at the top-level Box, not inside the header —
    // otherwise the inline sheet would take part in the Row layout and break it. Available only when
    // connected and a snippet library is attached.
    var paletteOpen by remember(active?.id) { mutableStateOf(false) }
    // The more_horiz menu (Disconnect) is an inline sheet at the screen's root Box, not a focusable
    // [MobileActionSheet] Popup: over an open soft keyboard a Popup measures against the shrunk window and
    // hangs at the old keyboard line with a gap below. Inline lives in the same window with live insets.
    var menuOpen by remember(active?.id) { mutableStateOf(false) }
    // Host monitor sheet (desktop info-panel parity) — raised from the same menu, connected only.
    var monitorOpen by remember(active?.id) { mutableStateOf(false) }
    // Session sharing (the "Share session" item below); null without sync — the item stays hidden.
    val share = LocalSessionShare.current
    val shareTeams = shareableTeams()
    // Outcome of the last finished recording, shown as a notice (desktop parity). null = nothing to say.
    var recordingNotice by remember(active?.id) { mutableStateOf<RecordingOutcome?>(null) }
    val scope = rememberCoroutineScope()
    // Broadcast sheet (desktop ⌘B parity): one command into several sessions. Not keyed on the
    // session — it addresses all of them, and the selection lives on the shell state.
    var broadcastOpen by remember { mutableStateOf(false) }
    // Command history palette (desktop ⌘K parity) — same menu, connected only.
    var historyOpen by remember(active?.id) { mutableStateOf(false) }
    val snippets = LocalSnippets.current
    val activeTerminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    val canRunSnippet = snippets != null && activeTerminal != null

    // Full-bleed only on request (More → Appearance → Interface); off, the phone keeps its bars
    // and the shell keeps this screen inside the safe area (see MobileChrome.fullBleed).
    ImmersiveScreen(state.hideSessionSystemBars)
    // Whether the bottom navigation is laid out under this screen right now — the same predicate the
    // shell uses, so the key panel and the bar never both claim (or both skip) the system inset.
    // Outside full-bleed the root padding already consumed the keyboard inset, so this reads false
    // and the panel reserves nothing; that is correct, the root reserved it for the whole screen.
    val tabBarBelow = mobileTabBarUnderRoute(
        state.route,
        state.modalOpen,
        keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
    )
    // Red frame around a production session (desktop parity): the phone has no tab row to carry the
    // marker, so the screen edge is the only always-visible place for it.
    Box(Modifier.fillMaxSize().background(Skerry.colors.terminalBg).prodOutline(isProdHostId(active?.hostId))) {
        // imePadding here, not at the app root: with "hide system bars" on this screen opts out of
        // the root safeDrawing padding to run edge to edge, so lifting the content above the soft
        // keyboard becomes its own job. With the setting off the root padding already consumed the
        // keyboard inset and this is a no-op.
        Column(Modifier.fillMaxSize().imePadding()) {
            MobileTerminalHeader(
                title = active?.displayTitle ?: stringResource(Res.string.term_mobile_title_fallback),
                subtitle = active?.subtitle.orEmpty(),
                status = active?.controller?.uiState,
                controller = active?.controller,
                onBack = state::pop,
                // Same rule as the path chip in output: a session with no SFTP channel (Telnet,
                // serial, container, a watched share) has no files to open.
                onFiles = if (active?.controller?.supportsSftp == true) ({ state.push(MobileRoute.Files) }) else null,
                // Desktop parity ([monitorAvailable]): a pane watching a colleague's session has no
                // connection of its own to poll.
                onMonitor = if (activeTerminal != null && active?.controller?.isWatched != true) ({ monitorOpen = true }) else null,
                onMenu = { menuOpen = true },
            )
            MobileSessionStrip(
                chips = mobileTerminalStrip(sessions?.tabs.orEmpty().map { it.toSessionInfo() }, sessions?.activeId),
                onSelect = { id -> sessions?.activate(id) },
                // A blank terminal is useless on a phone, so "+" leads to where a session starts.
                onNew = { state.select(MobileTab.Hosts) },
            )
            when (val st = active?.controller?.uiState) {
                null, ConnectionUiState.Form ->
                    MobileTerminalNotice("terminal", stringResource(Res.string.term_no_active_session), stringResource(Res.string.term_mobile_open_host_connect))
                ConnectionUiState.Connecting ->
                    MobileTerminalNotice("sync", stringResource(Res.string.term_connecting), active.subtitle)
                is ConnectionUiState.Connected -> {
                    // AI controller (or null): shared by the transient overlay and the input bar; key()
                    // recreates it on host/policy change. The transient is drawn over the terminal bottom
                    // so its appearance doesn't resize the terminal (else a reflow jump on paste/run).
                    val liveAi = LocalAi.current
                    val aiPolicy = active.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
                    // liveAi.enabled in the key: a global OFF in settings removes/restores the bar without
                    // recreating the screen (settings is Compose-state, a change recomposes).
                    val aiController = key(liveAi, aiPolicy, liveAi?.enabled) {
                        remember {
                            if (liveAi != null && liveAi.enabled && AiPolicyDecision.of(aiPolicy).aiEnabled) liveAi.terminalController(aiPolicy) else null
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TerminalScreen(
                            st.terminal,
                            Modifier.fillMaxSize(),
                            imeInput = true,
                            imeTransform = imeTransform,
                        )
                    }
                    // Desktop opens a path on Ctrl+click; touch has no such chord, so the affordance is
                    // a chip: long-press picks the path (word selection stops at whitespace, so a path
                    // comes out whole), the chip reveals it in Files. derivedStateOf keeps streaming
                    // output from recomposing the row while the selection hasn't changed.
                    // Gated on supportsSftp for the same reason as desktop: a Mosh/Telnet/serial/
                    // local/container session has no SFTP channel to reveal anything in.
                    val selectedPath by remember(st.terminal, active.controller) {
                        derivedStateOf {
                            if (!state.openFilePathsInSftp || !active.controller.supportsSftp) null
                            else filePathFromSelection(st.terminal.selectedText())
                        }
                    }
                    selectedPath?.let { path ->
                        MobileOpenPathBar(path) {
                            active.controller.requestReveal(path)
                            st.terminal.clearSelection()
                            state.push(MobileRoute.Files)
                        }
                    }
                    // Raised by the sparkle key; a pending suggestion forces it open so a command the
                    // model proposed can never be waiting behind a collapsed bar.
                    if (aiController != null && (aiOpen || aiController.pending != null)) {
                        MobileAiBarInput(aiController, st.terminal)
                    }
                    MobileKeybar(
                        st.terminal,
                        ctrlArmed,
                        onCtrlArmedChange = setCtrlArmed,
                        aiOpen = aiOpen,
                        onToggleAi = if (aiController != null) ({ aiOpen = !aiOpen }) else null,
                        // The tab bar below owns the navigation-bar inset whenever it is there.
                        reserveSystemBars = !tabBarBelow,
                    )
                }
                is ConnectionUiState.Error ->
                    MobileTerminalNotice("error", stringResource(Res.string.term_connection_failed), connectionErrorText(st), color = Skerry.colors.sunset)
                // Drop: frozen screen at the moment of loss, no keybar (channel is dead). Header status —
                // "disconnected" in red. Detailed mobile parity (auto-reconnect) is a separate task.
                is ConnectionUiState.Disconnected ->
                    TerminalScreen(st.terminal, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (paletteOpen && snippets != null && activeTerminal != null) {
            MobileSnippetRunSheet(
                manager = snippets,
                onRun = { entry -> snippets.run(entry.id, recording = activeTerminal.recording) { text -> activeTerminal.sendUserInputGuarded(text) }; paletteOpen = false },
                onDismiss = { paletteOpen = false },
            )
        }
        if (monitorOpen && active?.controller != null && activeTerminal != null) {
            MobileHostMonitorSheet(active.controller, onDismiss = { monitorOpen = false })
        }
        recordingNotice?.let { outcome ->
            NoticeDialog(
                title = stringResource(Res.string.term_record_start),
                message = recordingOutcomeMessage(outcome),
                buttonLabel = stringResource(Res.string.term_ai_dismiss),
                onDismiss = { recordingNotice = null },
            )
        }
        if (broadcastOpen) {
            MobileBroadcastSheet(
                controller = state.broadcast,
                targets = broadcastTargets(sessions, rememberProductionLookup()),
                onDismiss = { broadcastOpen = false },
            )
        }
        if (historyOpen && activeTerminal != null) {
            MobileCommandPaletteSheet(
                history = LocalTerminalHistory.current,
                currentKey = active?.controller?.historyKey,
                onPick = { command -> activeTerminal.applyHistoryCommand(command); historyOpen = false },
                onDismiss = { historyOpen = false },
            )
        }
        if (menuOpen && onDisconnect != null) {
            MobileBottomSheet(
                onDismiss = { menuOpen = false },
                panelModifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Txt(active?.displayTitle ?: stringResource(Res.string.term_mobile_title_fallback), color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (activeTerminal != null) {
                        MobileSheetButton(
                            label = stringResource(Res.string.term_palette_title),
                            onClick = { menuOpen = false; historyOpen = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            icon = "history",
                            filled = false,
                        )
                    }
                    if (canRunSnippet) {
                        MobileSheetButton(
                            label = stringResource(Res.string.lib_snippets_screen_title),
                            onClick = { menuOpen = false; paletteOpen = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            icon = "bolt",
                            filled = false,
                        )
                    }
                    if (activeTerminal != null && LocalRunbooks.current != null) {
                        // No popup picker here (the phone has no room for one over the terminal):
                        // the row opens the library, whose Run comes straight back with the start
                        // confirmation and the progress panel over this screen.
                        MobileSheetButton(
                            label = stringResource(Res.string.runbook_section),
                            onClick = { menuOpen = false; state.push(MobileRoute.Runbooks) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            icon = "checklist",
                            filled = false,
                        )
                    }
                    if (activeTerminal != null) {
                        MobileSheetButton(
                            label = stringResource(Res.string.term_broadcast_title),
                            onClick = { menuOpen = false; broadcastOpen = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            icon = "campaign",
                            filled = false,
                        )
                    }
                    if (activeTerminal != null) {
                        // Share this session with a team (desktop parity: the toolbar's cast toggle).
                        // Starting picks the first team with a key; stopping needs no choice at all.
                        val shareState = share?.state
                        val sharing = shareState is ShareUiState.Live
                        val target = shareTeams.firstOrNull()
                        if (sharing || target != null) {
                            MobileSheetButton(
                                label = stringResource(if (sharing) Res.string.share_session_stop else Res.string.share_session),
                                onClick = {
                                    menuOpen = false
                                    if (sharing) {
                                        share?.stop()
                                    } else if (target != null && active != null) {
                                        share?.share(
                                            teamId = target.first,
                                            teamName = target.second,
                                            paneId = active.id,
                                            label = active.displayTitle.ifBlank { active.subtitle },
                                            source = ShareSource(
                                                output = activeTerminal.ptyOutput,
                                                toShell = { bytes -> activeTerminal.sendSharedInput(bytes) },
                                                geometry = { ShareFrame.Resize(activeTerminal.cols, activeTerminal.rows) },
                                                sessionState = activeTerminal.state,
                                            ),
                                            readOnlyOnly = viewersMayOnlyWatch(activeTerminal.guardPolicy),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                icon = if (sharing) "cast_connected" else "cast",
                                filled = false,
                            )
                        }
                        // Recording toggle: stopping opens a Save-As for the .cast; nothing is
                        // written until the user picks a file.
                        val recording = activeTerminal.recording
                        MobileSheetButton(
                            label = stringResource(if (recording) Res.string.term_record_stop else Res.string.term_record_start),
                            onClick = {
                                menuOpen = false
                                // Start/stop go through the terminal's command loop, so both run in
                                // a coroutine rather than inline in the click.
                                scope.launch {
                                    if (!recording) {
                                        activeTerminal.startRecording(active?.displayTitle ?: active?.subtitle)
                                    } else {
                                        val truncated = activeTerminal.recordingTruncated
                                        val cast = activeTerminal.stopRecording()
                                        if (cast == null || !cast.contains('\n')) {
                                            recordingNotice = RecordingOutcome.Empty
                                        } else {
                                            val name = castFileName(active?.displayTitle.orEmpty().ifBlank { active?.subtitle.orEmpty() }, recordingStamp())
                                            val seconds = activeTerminal.recordingSeconds
                                            val outcome = exportFileGuarded(name, cast)
                                            // Desktop parity: report a saved recording of a shared
                                            // host to its team. A cancelled Save-As kept nothing.
                                            if (outcome == ExportOutcome.Saved) active.hostId?.let { teams?.reportSessionRecorded(it, seconds) }
                                            recordingNotice = when (outcome) {
                                                ExportOutcome.Cancelled -> null
                                                ExportOutcome.Failed -> RecordingOutcome.Failed
                                                ExportOutcome.Saved ->
                                                    if (truncated) RecordingOutcome.SavedTruncated else RecordingOutcome.Saved
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            icon = if (recording) "stop_circle" else "radio_button_checked",
                            filled = false,
                        )
                    }
                    MobileSheetButton(
                        label = stringResource(Res.string.term_disconnect),
                        onClick = { menuOpen = false; onDisconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = "power_settings_new",
                        filled = false,
                        danger = true,
                    )
                }
            }
        }
    }
}
