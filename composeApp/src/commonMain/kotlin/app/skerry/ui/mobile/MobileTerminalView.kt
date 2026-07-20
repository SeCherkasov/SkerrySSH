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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.skerry.ui.ai.AiNotice
import app.skerry.ui.ai.TerminalAiController
import app.skerry.ui.ai.aiBlockedMessage
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionErrorText
import app.skerry.ui.immersive.ImmersiveScreen
import app.skerry.ui.immersive.hiddenSystemBarsPadding
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_mobile_title_fallback
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
import app.skerry.ui.generated.resources.term_ai_not_a_command
import app.skerry.ui.generated.resources.term_password_label
import app.skerry.ui.generated.resources.term_connect
import app.skerry.ui.generated.resources.term_disconnect
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.terminal.ArrowKey
import app.skerry.ui.design.D
import app.skerry.ui.design.Dot
import app.skerry.ui.app.LocalAi
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.terminal.arrowSequence
import app.skerry.ui.session.sessionDotColor
import kotlinx.coroutines.delay

/** Terminal key panel background (`#0E1A24`). Keys — white 6%, monospaced. */
private val KeybarBg = Color(0xFF0E1A24)
private val KeyCapBg = Color(0x0FFFFFFF)
private val KeyCapFg = Color(0xFFC9D6DE)

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
    val active = sessions?.active
    // Stable Disconnect lambda (recreated only on session change): drops the connection and returns to
    // the list — the back arrow leaves the session alive, Disconnect closes it.
    val onDisconnect = remember(active?.id, sessions) {
        active?.let { s -> { sessions.close(s.id); state.pop() } }
    }
    // Clean shell exit (`exit`) on phone: close the session and return to the host list — a full-screen
    // push terminal has no reason to hang frozen (unlike desktop, which keeps a "Session closed" card).
    // A transport drop doesn't reach here (cleanExit=false) — the screen lives on there.
    val cleanlyExited = (active?.controller?.uiState as? ConnectionUiState.Disconnected)?.cleanExit == true
    LaunchedEffect(active?.id, cleanlyExited) {
        if (cleanlyExited) {
            sessions.close(active.id)
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

    Box(Modifier.fillMaxSize().background(D.terminalBg)) {
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
                    MobileTerminalNotice("error", stringResource(Res.string.term_connection_failed), connectionErrorText(st), color = D.sunset)
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
                onRun = { entry -> snippets.run(entry.id) { text -> activeTerminal.sendUserInput(text) }; paletteOpen = false },
                onDismiss = { paletteOpen = false },
            )
        }
        if (menuOpen && onDisconnect != null) {
            MobileBottomSheet(
                onDismiss = { menuOpen = false },
                panelModifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Txt(active?.displayTitle ?: stringResource(Res.string.term_mobile_title_fallback), color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
    val risk = controller.pendingRisk?.risk ?: CommandRisk.None
    val danger = risk == CommandRisk.Danger
    // Red for any destructive command (delete/overwrite), even Warn.
    val severe = danger || controller.pendingRisk?.destructive == true
    val accent = if (severe) D.sunset else D.moss
    var armed by remember(pending) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (pending != null) accent.copy(alpha = 0.08f) else D.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(
                if (pending != null) (if (severe) "block" else "terminal") else "auto_awesome",
                size = 16.sp, color = if (pending != null) accent else D.amber,
            )
            Box(Modifier.weight(1f)) {
                when {
                    pending != null -> {
                        val infoColor = if (severe) D.sunset else if (risk == CommandRisk.Warn) D.amber else D.dim
                        val info = when (risk) {
                            CommandRisk.None -> controller.pendingInfo
                            else -> controller.pendingRisk?.reason
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // The command wraps (up to 6 lines), not truncated: the user sees in full what
                            // they confirm and run (see TerminalView — the same safety invariant).
                            Txt(pending, color = if (severe) D.sunset else D.text, size = 12.sp, font = mono, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                            if (info != null) Txt(info, color = infoColor, size = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                        }
                    }
                    controller.busy -> Txt(stringResource(Res.string.term_ai_thinking), color = D.dim, size = 13.sp)
                    controller.notice != null -> when (val notice = controller.notice!!) {
                        is AiNotice.Blocked -> Txt(aiBlockedMessage(notice.reason), color = D.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        is AiNotice.Ask -> Txt(notice.question, color = D.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AiNotice.Rejected -> Txt(stringResource(Res.string.term_ai_not_a_command), color = D.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        is AiNotice.Error -> Txt(notice.message, color = D.sunset, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> {
                        if (prompt.isEmpty()) Txt(stringResource(Res.string.term_ai_ask_short), color = D.dim, size = 13.sp)
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            singleLine = true,
                            textStyle = TextStyle(color = D.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(D.cyan),
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
                        else controller.confirm()?.let { terminal.sendUserInput(it + "\r") }
                    }
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), D.faint) { controller.dismiss() }
                }
                controller.notice != null ->
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), D.faint) { controller.dismiss() }
                else -> {
                    Txt(controller.policy.name.uppercase(), color = D.faint, size = 10.sp, font = mono)
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(D.cyan)
                            .clickable(enabled = !controller.busy) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("arrow_upward", size = 16.sp, color = D.ink)
                    }
                }
            }
        }
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
    Column(Modifier.fillMaxWidth().background(D.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym(
                "chevron_left",
                size = 24.sp,
                color = D.cyanBright,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            )
            Column(Modifier.weight(1f)) {
                Txt(title, color = D.text, size = 14.sp, weight = FontWeight.SemiBold, font = mono)
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
                        Txt(mobileTerminalStatusText(status), color = sessionDotColor(status), size = 10.5.sp)
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
                    color = D.dim,
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
                color = D.dim,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onMenu,
                ),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.cyan08))
    }
}

/** One header status-line metric: icon + monospaced value (RTT/throughput). */
@Composable
private fun MobileTerminalMetric(icon: String, text: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Sym(icon, size = 11.sp, color = D.faint)
        Txt(text, color = D.faint, size = 10.5.sp, font = mono)
    }
}

/** Centered message over the terminal background (no session / connecting / error). */
@Composable
private fun MobileTerminalNotice(icon: String, title: String, subtitle: String, color: Color = D.dim) {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        // Long texts (Mosh setup errors) must wrap into a readable centered block, not one
        // screen-wide line.
        Txt(
            subtitle, color = D.faint, size = 12.sp, font = mono, lineHeight = 18.sp,
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
            terminal.sendUserInput(controlByte(c[0]))
            onCtrlArmedChange(false)
        } else {
            terminal.sendUserInput(c)
        }
    }
    val arrow = { key: ArrowKey -> plain(arrowSequence(key, terminal.applicationCursorKeys)) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(KeybarBg)
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
            if (terminal.suggestionTail != null) { terminal.acceptSuggestion(); onCtrlArmedChange(false) } else plain("\t")
        }
        // With a suggestion shown — cycle alternatives (like Shift+Tab on desktop).
        if (terminal.suggestionTail != null) {
            KeyCapIcon("autorenew") { terminal.cycleSuggestion() }
        }
        // Reverse history search (Ctrl-R): open the search overlay (query typed on the soft keyboard).
        KeyCapIcon("search") { terminal.openReverseSearch() }
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
        active -> D.cyan
        accent -> D.cyan14
        else -> KeyCapBg
    }
    val fg = when {
        active -> D.ink
        accent -> D.cyanBright
        else -> KeyCapFg
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
            .background(if (accent) D.cyan14 else KeyCapBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 16.sp, color = if (accent) D.cyanBright else KeyCapFg)
    }
}

/**
 * Bottom password-prompt sheet on Connect to a host with no bound identity (styled like the
 * `New connection` sheet). The password goes to [onConnect] as a string and is used right away in
 * `SshAuth.Password`; the buffer lives only in this composable. A tap outside the sheet — [onDismiss].
 */
@Composable
fun MobilePasswordSheet(host: Host, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConnect(password) }
    // Protect SSH password entry on connect from screenshots/Recent Apps previews (Android; desktop no-op).
    SecureScreen()
    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Txt(host.label, color = D.text, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Txt("${host.username}@${host.address}:${host.port}", color = D.dim, size = 12.5.sp, font = LocalFonts.current.mono)
            Spacer(Modifier.height(18.dp))
            Txt(stringResource(Res.string.term_password_label), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(6.dp))
            MobileFormInput(
                value = password,
                onValueChange = { password = it },
                placeholder = "••••••••",
                masked = true,
                imeAction = ImeAction.Go,
                onSubmit = { submit() },
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (password.isNotEmpty()) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.term_connect), color = D.ink, size = 16.sp, weight = FontWeight.Bold)
            }
        }
}
