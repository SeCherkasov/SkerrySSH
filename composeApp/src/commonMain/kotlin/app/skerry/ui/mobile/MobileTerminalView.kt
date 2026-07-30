package app.skerry.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.ui.ai.AiNotice
import app.skerry.ui.ai.TerminalAiController
import app.skerry.ui.ai.aiBlockedMessage
import app.skerry.ui.ai.aiFailureMessage
import app.skerry.ui.ai.shortLabel
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionErrorText
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.immersive.ImmersiveScreen
import app.skerry.ui.immersive.hiddenSystemBarsPadding
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.RecordingOutcome
import app.skerry.shared.share.ShareFrame
import app.skerry.ui.app.LocalSessionShare
import app.skerry.ui.generated.resources.share_session
import app.skerry.ui.generated.resources.share_session_stop
import app.skerry.ui.share.ShareSource
import app.skerry.ui.share.ShareUiState
import app.skerry.ui.share.shareableTeams
import app.skerry.ui.share.viewersMayOnlyWatch
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.generated.resources.shell_use_saved_secret
import app.skerry.ui.terminal.recordingOutcomeMessage
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_mobile_title_fallback
import app.skerry.ui.generated.resources.term_open_path_in_files
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.term_broadcast_title
import app.skerry.ui.generated.resources.term_monitor_title
import app.skerry.ui.generated.resources.term_record_start
import app.skerry.ui.generated.resources.term_record_stop
import app.skerry.ui.generated.resources.term_palette_title
import app.skerry.ui.generated.resources.term_no_active_session
import app.skerry.ui.generated.resources.term_mobile_open_host_connect
import app.skerry.ui.generated.resources.term_connecting
import app.skerry.ui.generated.resources.term_connection_failed
import app.skerry.ui.generated.resources.term_ai_thinking
import app.skerry.ui.generated.resources.term_ai_ask_short
import app.skerry.ui.generated.resources.term_ai_run
import app.skerry.ui.generated.resources.term_ai_run_anyway
import app.skerry.ui.generated.resources.term_ai_confirm
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_ai_explain_reading
import app.skerry.ui.generated.resources.term_ai_not_a_command
import app.skerry.ui.generated.resources.term_password_label
import app.skerry.ui.generated.resources.term_connect
import app.skerry.ui.generated.resources.term_disconnect
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.terminal.ArrowKey
import app.skerry.ui.design.Dot
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.app.LocalAi
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.terminal.arrowSequence
import app.skerry.ui.terminal.filePathFromSelection
import app.skerry.ui.session.broadcastTargets
import app.skerry.ui.session.sessionDotColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.skerry.shared.terminal.castFileName
import app.skerry.shared.terminal.recordingStamp
import app.skerry.ui.vault.exportTextFile
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.isProdHostId
import app.skerry.ui.host.prodOutline
import app.skerry.ui.host.rememberProductionLookup
import app.skerry.ui.ai.commandRiskReasonText

/** ESC (0x1B) — prefix of arrow CSI sequences and the esc key itself. */
private const val ESC = "\u001b"

private const val HEADER_AUTO_HIDE_MS = 3000L
// Where the header's reveal strip starts and how tall it is: clear of the system's edge-swipe zone.
private val SYSTEM_EDGE_GESTURE = 40.dp
private val TOP_EDGE_STRIP = 72.dp

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
    // Session chrome auto-hides like the VNC screen's bar; a swipe down near the top brings it back.
    var headerVisible by remember(active?.id) { mutableStateOf(true) }
    // See MobileVncScreen: keyed into the auto-hide effect so a swipe restarts the timer even when
    // the header is already up.
    var revealNonce by remember(active?.id) { mutableStateOf(0) }
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

    ImmersiveScreen()
    // Held open while a sheet launched from it is up: the header is where they were opened from, and
    // hiding it under an open menu reads as the app losing its place.
    LaunchedEffect(headerVisible, menuOpen, paletteOpen, revealNonce) {
        if (headerVisible && !menuOpen && !paletteOpen) {
            delay(HEADER_AUTO_HIDE_MS)
            headerVisible = false
        }
    }

    // Red frame around a production session (desktop parity): the phone has no tab row to carry the
    // marker, so the screen edge is the only always-visible place for it.
    Box(Modifier.fillMaxSize().background(Skerry.colors.terminalBg).prodOutline(isProdHostId(active?.hostId))) {
        // imePadding here, not at the app root: this screen opts out of the root safeDrawing padding
        // to run edge to edge, so lifting the content above the soft keyboard is its own job now.
        Column(Modifier.fillMaxSize().imePadding()) {
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
        // Swipe down near the top brings the header back. Starts below the edge: a swipe from the very
        // edge is the system's own gesture for restoring the hidden bars and never reaches us.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(top = SYSTEM_EDGE_GESTURE).height(TOP_EDGE_STRIP)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dy ->
                        if (dy > 0f) { headerVisible = true; revealNonce++ }
                    }
                },
        )
        AnimatedVisibility(
            visible = headerVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            MobileTerminalHeader(
                title = active?.displayTitle ?: stringResource(Res.string.term_mobile_title_fallback),
                status = active?.controller?.uiState,
                controller = active?.controller,
                onBack = state::pop,
                onMenu = { menuOpen = true },
                onSnippets = if (canRunSnippet) ({ paletteOpen = true }) else null,
            )
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
                        // Desktop parity ([infoPanelAvailable]): a pane watching a colleague's shared
                        // session has no connection to poll, so the monitor isn't offered at all.
                        if (active?.controller?.isWatched != true) {
                            MobileSheetButton(
                                label = stringResource(Res.string.term_monitor_title),
                                onClick = { menuOpen = false; monitorOpen = true },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                icon = "monitoring",
                                filled = false,
                            )
                        }
                        MobileSheetButton(
                            label = stringResource(Res.string.term_palette_title),
                            onClick = { menuOpen = false; historyOpen = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            icon = "history",
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
                                            val saved = exportTextFile(name, cast)
                                            // Desktop parity: report a saved recording of a shared
                                            // host to its team. A cancelled Save-As kept nothing.
                                            if (saved) active.hostId?.let { teams?.reportSessionRecorded(it, seconds) }
                                            recordingNotice = when {
                                                !saved -> null
                                                truncated -> RecordingOutcome.SavedTruncated
                                                else -> RecordingOutcome.Saved
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

/**
 * The single form of the mobile AI bar (desktop parity) — constant height, the terminal isn't resized,
 * nothing is overlapped. One row: input, "Thinking…", blocked/error, and for a suggestion — the command
 * + inline note (None: what it does; Warn/Danger: risk reason in color) + buttons. Destructive is red
 * with a "block" icon. No auto-run: Run = confirmation; [CommandRisk.Danger] needs a second tap.
 */
@Composable
private fun MobileAiBarInput(controller: TerminalAiController, terminal: TerminalScreenState) {
    val mono = LocalFonts.current.mono
    var prompt by remember { mutableStateOf("") }
    val submit = {
        val text = prompt.trim()
        if (text.isNotEmpty()) { controller.ask(text); prompt = "" }
    }
    val pending = controller.pending
    val explanation = controller.explanation
    val risk = controller.pendingRisk?.risk ?: CommandRisk.None
    val danger = risk == CommandRisk.Danger
    // Red for any destructive command (delete/overwrite), even Warn.
    val severe = danger || controller.pendingRisk?.destructive == true
    val accent = if (severe) Skerry.colors.sunset else Skerry.colors.moss
    var armed by remember(pending) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (pending != null) accent.copy(alpha = 0.08f) else Skerry.colors.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(
                when {
                    pending != null -> if (severe) "block" else "terminal"
                    explanation != null -> "summarize"
                    else -> "auto_awesome"
                },
                size = 16.sp, color = if (pending != null) accent else Skerry.colors.amber,
            )
            Box(Modifier.weight(1f)) {
                when {
                    pending != null -> {
                        val infoColor = if (severe) Skerry.colors.sunset else if (risk == CommandRisk.Warn) Skerry.colors.amber else Skerry.colors.dim
                        val info = when (risk) {
                            CommandRisk.None -> controller.pendingInfo
                            else -> controller.pendingRisk?.reason?.let { commandRiskReasonText(it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // The command wraps (up to 6 lines), not truncated: the user sees in full what
                            // they confirm and run (see TerminalView — the same safety invariant).
                            Txt(pending, color = if (severe) Skerry.colors.sunset else Skerry.colors.text, size = 12.sp, font = mono, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                            if (info != null) Txt(info, color = infoColor, size = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                        }
                    }
                    explanation != null ->
                        if (explanation.isEmpty()) {
                            Txt(stringResource(Res.string.term_ai_explain_reading), color = Skerry.colors.dim, size = 13.sp)
                        } else {
                            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                Txt(explanation, color = Skerry.colors.text, size = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    controller.busy -> Txt(stringResource(Res.string.term_ai_thinking), color = Skerry.colors.dim, size = 13.sp)
                    controller.notice != null -> when (val notice = controller.notice!!) {
                        is AiNotice.Blocked -> Txt(aiBlockedMessage(notice.reason), color = Skerry.colors.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        is AiNotice.Ask -> Txt(notice.question, color = Skerry.colors.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AiNotice.Rejected -> Txt(stringResource(Res.string.term_ai_not_a_command), color = Skerry.colors.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        is AiNotice.Error -> Txt(aiFailureMessage(notice.failure), color = Skerry.colors.sunset, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> {
                        if (prompt.isEmpty()) Txt(stringResource(Res.string.term_ai_ask_short), color = Skerry.colors.dim, size = 13.sp)
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(Skerry.colors.cyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            when {
                pending != null -> {
                    MobileAiChip(when { !danger -> stringResource(Res.string.term_ai_run); !armed -> stringResource(Res.string.term_ai_run_anyway); else -> stringResource(Res.string.term_ai_confirm) }, accent) {
                        if (danger && !armed) armed = true
                        else controller.confirm()?.let { terminal.sendUserInputGuarded(it + "\r") }
                    }
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), Skerry.colors.faint) { controller.dismiss() }
                }
                explanation != null ->
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), Skerry.colors.faint) { controller.dismiss() }
                controller.notice != null ->
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), Skerry.colors.faint) { controller.dismiss() }
                else -> {
                    // Explain the current selection, or the visible screen when nothing is selected.
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
                            .background(Skerry.colors.overlaySoft)
                            .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp))
                            .clickable(enabled = !controller.busy) {
                                // Selection wins; else the last command's output; else the whole screen.
                                controller.explain(terminal.selectedText() ?: terminal.lastOutput() ?: terminal.output)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("summarize", size = 16.sp, color = Skerry.colors.amber)
                    }
                    Txt(labelUppercase(controller.policy.shortLabel()), color = Skerry.colors.faint, size = 10.sp, font = mono)
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.cyan)
                            .clickable(enabled = !controller.busy) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("arrow_upward", size = 16.sp, color = Skerry.colors.ink)
                    }
                }
            }
        }
    }
}

/**
 * "Open in Files" row over the key panel, shown while the selection is exactly one file path. Full
 * width and tappable as a whole (a phone-sized target), with the path itself in mono so it reads as
 * the thing being opened; a long path ellipsizes at the start, keeping the file name visible.
 */
@Composable
private fun MobileOpenPathBar(path: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.surface2).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("drive_file_move", size = 16.sp, color = Skerry.colors.cyanBright)
        Txt(stringResource(Res.string.term_open_path_in_files), color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium)
        Txt(
            path,
            color = Skerry.colors.dim,
            size = 11.5.sp,
            font = LocalFonts.current.mono,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MobileAiChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Txt(label, color = color, size = 11.5.sp, weight = FontWeight.Medium)
    }
}

/**
 * Terminal header (`#0B1A26` + bottom cyan line): back chevron, host name + status line with live
 * metrics (RTT/throughput), `more_horiz` icon (menu with Disconnect). [onDisconnect]==null — no active
 * session, the Disconnect item is hidden. The split icon is removed on phone (split not needed).
 *
 * Metrics come from [controller] via the same pollers as the desktop status bar: the keep-alive/RTT
 * poller ([openPing], null with the profile's keep-alive off) and channel throughput
 * ([openThroughput]). The remember is unconditional — keys (controller + connected flag) recreate it
 * on session/connection change; both methods are idempotent (cached in the controller). Before the
 * first sample / off-connection the metric is "—"; a narrow line scrolls horizontally.
 */
@Composable
private fun MobileTerminalHeader(
    title: String,
    status: ConnectionUiState?,
    controller: ConnectionController?,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onSnippets: (() -> Unit)?,
) {
    val mono = LocalFonts.current.mono
    val connected = status is ConnectionUiState.Connected
    val throughput = remember(controller, connected) {
        if (connected && controller != null) controller.openThroughput() else null
    }
    val ping = remember(controller, connected) {
        if (connected && controller != null) controller.openPing() else null
    }
    Column(Modifier.fillMaxWidth().background(Skerry.colors.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym(
                "chevron_left",
                size = 24.sp,
                color = Skerry.colors.cyanBright,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            )
            Column(Modifier.weight(1f)) {
                Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold, font = mono)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Dot(sessionDotColor(status))
                        Txt(mobileTerminalStatusText(mobileTerminalStatus(status)), color = sessionDotColor(status), size = 10.5.sp)
                    }
                    // Live metrics of the active session (desktop status-bar parity) — only when connected.
                    if (connected) {
                        MobileTerminalMetric("network_ping", mobileRttLabel(ping?.rttMs), mono)
                        MobileTerminalMetric("arrow_upward", mobileRateLabel(throughput?.upRate), mono)
                        MobileTerminalMetric("arrow_downward", mobileRateLabel(throughput?.downRate), mono)
                    }
                }
            }
            if (onSnippets != null) {
                Sym(
                    "bolt",
                    size = 21.sp,
                    color = Skerry.colors.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSnippets,
                    ),
                )
            }
            Sym(
                "more_horiz",
                size = 21.sp,
                color = Skerry.colors.dim,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onMenu,
                ),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan08))
    }
}

/** One header status-line metric: icon + monospaced value (RTT/throughput). */
@Composable
private fun MobileTerminalMetric(icon: String, text: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Sym(icon, size = 11.sp, color = Skerry.colors.faint)
        Txt(text, color = Skerry.colors.faint, size = 10.5.sp, font = mono)
    }
}

/** Centered message over the terminal background (no session / connecting / error). */
@Composable
private fun MobileTerminalNotice(icon: String, title: String, subtitle: String, color: Color = Skerry.colors.dim) {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        // Long texts (Mosh setup errors) must wrap into a readable centered block, not one
        // screen-wide line.
        Txt(
            subtitle, color = Skerry.colors.faint, size = 12.sp, font = mono, lineHeight = 18.sp,
            align = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}

/**
 * Special-key panel (`#0E1A24`, horizontal scroll) — the core of the mobile SSH UX: esc, tab, ctrl
 * (sticky modifier), /, |, -, ~, arrows. Control sequences go to the PTY via
 * [TerminalScreenState.sendUserInput], so a viewport scrolled into history snaps back to the live screen.
 * `ctrl` is armed by a tap (cyan highlight): [ctrlArmed] is lifted to [MobileTerminalScreen], so it
 * applies to both the panel's character keys ([controlByte]) and soft-keyboard input ([applyStickyCtrl]
 * in the IME path). Arrows are encoded per the session's DECCKM mode ([arrowSequence]): CSI normally,
 * SS3 in application-cursor (vim/less).
 */
@Composable
private fun MobileKeybar(
    terminal: TerminalScreenState,
    ctrlArmed: Boolean,
    onCtrlArmedChange: (Boolean) -> Unit,
    aiOpen: Boolean = false,
    onToggleAi: (() -> Unit)? = null,
) {
    val plain = { seq: String -> terminal.sendUserInput(seq); onCtrlArmedChange(false) }
    val char = { c: String ->
        if (ctrlArmed && c.length == 1) {
            // typeInput for the same reason as below: the ctrl forms of these keys edit or run the
            // shell line (ctrl + "-" is CR, ctrl + "/" is accept-line-and-down-history), so they owe
            // the engine and the production guard the same visibility as anything else typed here.
            terminal.typeInput(controlByte(c[0]))
            onCtrlArmedChange(false)
        } else {
            // typeInput, not sendUserInput: a character from the panel is typing, and it has to reach
            // the tracked line like any other. Sending it raw left `/`, `|`, `~` out of the line the
            // production guard classifies (the panel has no Enter key, so nothing ran unguarded —
            // but the guard was reading a line that was missing characters).
            terminal.typeInput(c)
        }
    }
    val arrow = { key: ArrowKey -> plain(arrowSequence(key, terminal.applicationCursorKeys)) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Skerry.colors.surface2)
            // The screen runs edge to edge, so the panel keeps itself off the navigation bar.
            .hiddenSystemBarsPadding(top = false, bottom = true)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // While reverse-search (Ctrl-R) is open, the panel shows its controls; the query is typed on the
        // soft keyboard (routed to TerminalScreen). Otherwise — the normal layout.
        if (terminal.reverseSearchQuery != null) {
            KeyCap("esc") { terminal.closeReverseSearch(); onCtrlArmedChange(false) }
            KeyCapIcon("expand_more") { terminal.reverseSearchNext() } // next (older)
            KeyCapIcon("expand_less") { terminal.reverseSearchPrev() } // previous (newer)
            KeyCapIcon("delete") { terminal.reverseSearchDeleteSelected() } // remove from history
            KeyCap("insert", accent = true) { terminal.reverseSearchAccept(); onCtrlArmedChange(false) }
            return@Row
        }
        // The AI input lives behind this key instead of taking a permanent row (see MobileAiBarInput).
        if (onToggleAi != null) KeyCapIcon("auto_awesome", accent = aiOpen) { onToggleAi() }
        KeyCap("esc") { plain(ESC) }
        // Tab with an autocomplete suggestion — accept it; otherwise a normal tab to the PTY.
        KeyCap("tab") {
            if (terminal.hasSuggestion) { terminal.acceptSuggestion(); onCtrlArmedChange(false) } else plain("\t")
        }
        // With a suggestion available — cycle alternatives (like Shift+Tab on desktop). Keyed on the
        // engine, not on the drawn ghost: the ghost blinks off for the echo round trip, and the row
        // would reflow under the finger on every keystroke.
        if (terminal.hasSuggestion) {
            KeyCapIcon("autorenew") { terminal.cycleSuggestion() }
        }
        // Reverse history search (Ctrl-R): open the search overlay (query typed on the soft keyboard).
        KeyCapIcon("search") { terminal.openReverseSearch() }
        // Find in output (desktop ⌘F parity): opens the search panel over the terminal, which takes
        // the soft keyboard for its query field.
        KeyCapIcon("find_in_page") { terminal.openSearch() }
        // ctrl — special panel key (always cyan); arming fills it solid cyan.
        KeyCap("ctrl", accent = true, active = ctrlArmed) { onCtrlArmedChange(!ctrlArmed) }
        KeyCap("/") { char("/") }
        KeyCap("|") { char("|") }
        KeyCap("-") { char("-") }
        KeyCap("~") { char("~") }
        KeyCapIcon("keyboard_arrow_up") { arrow(ArrowKey.Up) }
        KeyCapIcon("keyboard_arrow_down") { arrow(ArrowKey.Down) }
        KeyCapIcon("keyboard_arrow_left") { arrow(ArrowKey.Left) }
        KeyCapIcon("keyboard_arrow_right") { arrow(ArrowKey.Right) }
    }
}

/**
 * Text panel key. [accent] — special key (cyan at rest, like `ctrl`); [active] — sticky arming (solid
 * cyan + dark text).
 */
@Composable
private fun KeyCap(label: String, accent: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        active -> Skerry.colors.cyan
        accent -> Skerry.colors.cyan14
        else -> Skerry.colors.overlayMed
    }
    val fg = when {
        active -> Skerry.colors.ink
        accent -> Skerry.colors.cyanBright
        else -> Skerry.colors.textBright
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Txt(label, color = fg, size = 12.5.sp, font = LocalFonts.current.mono)
    }
}

/** Icon panel key (arrows). */
@Composable
private fun KeyCapIcon(icon: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (accent) Skerry.colors.cyan14 else Skerry.colors.overlayMed)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 16.sp, color = if (accent) Skerry.colors.cyanBright else Skerry.colors.textBright)
    }
}

/**
 * Bottom password-prompt sheet on Connect to a host with no bound identity (styled like the
 * `New connection` sheet). The password goes to [onConnect] as a string and is used right away in
 * `SshAuth.Password`; the buffer lives only in this composable. A tap outside the sheet — [onDismiss].
 *
 * [secrets] mirror the desktop dialog: a team-shared host arrives without its credential link, so
 * the keychain is offered here too — a key-only server has no other way in.
 */
@Composable
fun MobilePasswordSheet(
    host: Host,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    secrets: List<Credential> = emptyList(),
    onUseSecret: (Credential) -> Unit = {},
) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConnect(password) }
    // Protect SSH password entry on connect from screenshots/Recent Apps previews (Android; desktop no-op).
    SecureScreen()
    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Txt(host.label, color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Txt("${host.username}@${host.address}:${host.port}", color = Skerry.colors.dim, size = 12.5.sp, font = LocalFonts.current.mono)
            Spacer(Modifier.height(18.dp))
            Txt(stringResource(Res.string.term_password_label), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(6.dp))
            MobileFormInput(
                value = password,
                onValueChange = { password = it },
                placeholder = "••••••••",
                masked = true,
                imeAction = ImeAction.Go,
                onSubmit = { submit() },
            )
            if (secrets.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Txt(stringResource(Res.string.shell_use_saved_secret), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    secrets.forEach { secret ->
                        MobileSheetButton(
                            label = secret.label,
                            onClick = { onUseSecret(secret) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = VaultPresentation.secretIcon(secret.secret),
                            filled = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (password.isNotEmpty()) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.term_connect), color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
            }
        }
}
