package app.skerry.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.Vault
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.jumpRouteLabel
import app.skerry.ui.forward.humanRate
import androidx.compose.runtime.collectAsState
import app.skerry.ui.sync.SyncIndicatorLevel
import app.skerry.ui.sync.syncIndicatorLocalized
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_settings
import app.skerry.ui.generated.resources.shell_status_connected
import app.skerry.ui.generated.resources.shell_status_disconnected
import app.skerry.ui.generated.resources.shell_status_encoding
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSync
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.theme.Skerry

@Composable
internal fun IconRail(state: DesktopDesignState) {
    // What the work area is showing decides whether a section press can also bring the hosts panel
    // back: the file panel and a runbook run fill the area and draw no catalog (see [showsCatalog]).
    val terminalView = LocalSessions.current?.activeTerminal?.view
    Column(
        Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(Skerry.colors.railBg)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // No sidebar toggle here: the panel is collapsed from the strip on its own edge
        // ([app.skerry.ui.terminal.SidebarToggleHandle]); pressing a section only ever opens it.
        RAIL.forEach { item ->
            RailButton(
                icon = item.icon,
                label = stringResource(item.label),
                active = railItemActive(item, state),
                tag = when (val target = item.target) {
                    is RailTarget.View -> UiTags.railView(target.view)
                    is RailTarget.Section -> UiTags.railSection(target.section)
                },
                onClick = {
                    when (val target = item.target) {
                        // App-level (Vault/Known/Teams/Tunnels/Snippets) → overlay over the tabs.
                        is RailTarget.View -> state.showView(target.view)
                        // Work-area section: swaps the sidebar catalog; a running session stays on
                        // screen (openRailSection).
                        is RailTarget.Section ->
                            openRailSection(state, target.section, terminalView)
                    }
                },
            )
        }
        Spacer(Modifier.weight(1f))
        RailButton(
            icon = "settings",
            label = stringResource(Res.string.shell_settings),
            active = false,
            tag = UiTags.RAIL_SETTINGS,
            onClick = state::openSettings,
        )
    }
}

@Composable
private fun RailButton(icon: String, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val fg = if (active) Skerry.colors.cyanBright else Skerry.colors.faint
    // Icons without labels: the item name goes to a hover tooltip (desktop) so the narrow column doesn't
    // wrap long words.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(interaction)) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(Skerry.colors.cyan, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) Skerry.colors.cyan10 else if (hovered) Skerry.colors.cyan.copy(alpha = 0.06f) else Color.Transparent)
                // On the clickable box itself, not the row: the tag has to land on the node that
                // carries the click, or a test would find it and press nothing.
                .testTag(tag)
                // Which section is open is otherwise only a colour and a 2dp accent bar, so a screen
                // reader would announce every rail button alike whichever one is current.
                .semantics { selected = active }
                // Same for the button of the section already showing.
                .handsKeyboardBack()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // The label is otherwise only in the hover tooltip, which a screen reader never opens
            // and a keyboard never triggers — without this the rail is a column of unnamed buttons.
            Sym(icon, size = 21.sp, color = fg, contentDescription = label)
        }
        // Tooltip to the right of the rail — only while the cursor is over the button.
        if (hovered) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            val position = remember(gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        x = anchorBounds.right + gap,
                        y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2,
                    )
                }
            }
            Popup(
                popupPositionProvider = position,
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Skerry.colors.railBg)
                        .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Txt(label, color = Skerry.colors.textBright, size = 11.sp, weight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun StatusBar() {
    val mono = LocalFonts.current.mono
    // In live mode the left status and throughput reflect the active session.
    val sessions = LocalSessions.current
    // The pane being worked in: on a split tab the bar describes that session, not a fixed one.
    val active = sessions?.activeSession
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    val live = sessions != null
    val statusText = if (!live || connected) stringResource(Res.string.shell_status_connected) else stringResource(Res.string.shell_status_disconnected)
    val statusColor = if (!live || connected) Skerry.colors.moss else Skerry.colors.faint
    // Channel throughput poller for the active session (when connected). The remember is unconditional —
    // keys (session + connected flag) recreate it on session/connection change; openThroughput is
    // idempotent (cached in ConnectionController).
    val throughput = remember(active, connected) {
        if (connected) active.controller.openThroughput() else null
    }
    val upRate = throughput?.upRate
    val downRate = throughput?.downRate
    // RTT of the active session's keep-alive poller (same approach as throughput); null with the
    // profile's keep-alive off (no pings, no RTT), before the first sample, or on failure.
    val ping = remember(active, connected) {
        if (connected) active.controller.openPing() else null
    }
    val rttMs = ping?.rttMs
    // Grid size — live cols×rows of the active terminal; off-connection the mock label remains.
    val gridLabel = (active?.controller?.uiState as? ConnectionUiState.Connected)
        ?.terminal?.let { "${it.cols} × ${it.rows}" } ?: "80 × 24"
    // ProxyJump route of the active session's profile ("outer → inner", entry hop first) — the
    // at-a-glance "this session rides through a bastion" marker. Hidden for direct connections
    // and in mock mode, so the static bar is unchanged.
    val statusHosts = LocalHosts.current
    val jumpRoute = if (live) {
        active?.hostId?.let { id -> statusHosts?.find(id) }?.let { h -> jumpRouteLabel(h) { statusHosts?.find(it) } }
    } else null
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Skerry.colors.railBg)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (live && !connected) {
                // Idle home / dropped session: with no session there is nothing to ping or meter, so a
                // bare dim dot stands in for "not connected" instead of the word plus a row of "—".
                Sym("circle", size = 11.sp, color = statusColor)
            } else {
                StatusItem("circle", statusText, color = statusColor, iconSize = 11.sp, mono = mono)
                // Jump route right next to the connection status, cyan so it reads at a glance.
                if (jumpRoute != null) StatusItem("alt_route", jumpRoute, color = Skerry.colors.cyan, mono = mono)
                // Live RTT ping of the active session (before the first sample — "—"); mock mode — template label.
                StatusItem("network_ping", if (live) (rttMs?.let { "$it ms" } ?: "—") else "42 ms", mono = mono)
                // Live channel throughput (before connect — "—"); mock mode (offscreen) — template labels.
                StatusItem("arrow_upward", if (live) (upRate?.let { humanRate(it) } ?: "—") else "1.2 KB/s", mono = mono)
                StatusItem("arrow_downward", if (live) (downRate?.let { humanRate(it) } ?: "—") else "8.4 KB/s", mono = mono)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Update notice (undismissed newer release): click opens the GitHub release page.
            // App-level — shown regardless of any session.
            app.skerry.ui.update.UpdateStatusItem()
            // Server ident, encoding, and grid size describe the active terminal — with no session they
            // are just template values, so off-connection they drop out (mock mode keeps them).
            if (!live || connected) {
                // Server version — live ident of the active session (before connect / if the transport is silent — "—").
                StatusItem("memory", if (live) (active?.controller?.serverVersion ?: "—") else "SSH-2.0-OpenSSH_8.9p1", mono = mono)
                Txt(stringResource(Res.string.shell_status_encoding), color = Skerry.colors.faint, size = 10.5.sp, font = mono)
                Txt(gridLabel, color = Skerry.colors.faint, size = 10.5.sp, font = mono)
            }
            // The sync indicator follows session status (see syncIndicator): green only with an active
            // session + reachable server; linked-but-not-connected → amber, etc. Hidden when sync isn't
            // configured / not yet pinged. Rendered as a bare glyph (no label) to match the mobile header,
            // pinned to the far right after all status texts.
            val syncC = LocalSync.current
            val ind = syncC?.let { syncIndicatorLocalized(it.status.collectAsState().value, it.serverReachable.collectAsState().value) }
            if (ind != null) {
                Sym(
                    ind.icon,
                    // The only thing that says sync has stopped: a bare glyph with no text beside it,
                    // so without its own name it is nothing at all to a screen reader.
                    contentDescription = ind.label,
                    size = 13.sp,
                    color = when (ind.level) {
                        SyncIndicatorLevel.OK -> Skerry.colors.moss
                        SyncIndicatorLevel.WARN -> Skerry.colors.amber
                        SyncIndicatorLevel.ERROR -> Skerry.colors.sunset
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    icon: String,
    text: String,
    color: Color = Skerry.colors.faint,
    iconSize: TextUnit = 13.sp,
    mono: FontFamily,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Sym(icon, size = iconSize, color = color)
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}

/**
 * The desktop size an RDP session falls back to when the window has not been measured yet. The
 * normal path asks for the viewport itself (see `rdpDesktopSize`, F-06), and once connected the
 * Display Control channel lets the session follow the window.
 */
internal const val RDP_DEFAULT_WIDTH = 1920
internal const val RDP_DEFAULT_HEIGHT = 1080

/** Name this client reports to the server; it shows up in the session list on the remote machine. */
internal const val RDP_CLIENT_NAME = "Skerry"
