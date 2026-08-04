package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.immersive.hiddenSystemBarsPadding
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.ArrowKey
import app.skerry.ui.design.Dot
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.terminal.arrowSequence
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.theme.Skerry

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
internal fun MobileTerminalHeader(
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
internal fun MobileTerminalNotice(icon: String, title: String, subtitle: String, color: Color = Skerry.colors.dim) {
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
internal fun MobileKeybar(
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
        if (terminal.reverseSearch.query != null) {
            KeyCap("esc") { terminal.reverseSearch.close(); onCtrlArmedChange(false) }
            KeyCapIcon("expand_more") { terminal.reverseSearch.next() } // next (older)
            KeyCapIcon("expand_less") { terminal.reverseSearch.prev() } // previous (newer)
            KeyCapIcon("delete") { terminal.reverseSearch.deleteSelected() } // remove from history
            KeyCap("insert", accent = true) { terminal.reverseSearch.accept(); onCtrlArmedChange(false) }
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
        KeyCapIcon("search") { terminal.reverseSearch.open() }
        // Find in output (desktop ⌘F parity): opens the search panel over the terminal, which takes
        // the soft keyboard for its query field.
        KeyCapIcon("find_in_page") { terminal.search.open() }
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
