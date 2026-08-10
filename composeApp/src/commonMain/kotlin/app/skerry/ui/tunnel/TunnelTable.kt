package app.skerry.ui.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.design.Badge
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_col_host
import app.skerry.ui.generated.resources.ports_col_listen
import app.skerry.ui.generated.resources.ports_col_name
import app.skerry.ui.generated.resources.ports_col_status
import app.skerry.ui.generated.resources.ports_col_target
import app.skerry.ui.generated.resources.ports_col_traffic
import app.skerry.ui.generated.resources.ports_col_type
import app.skerry.ui.generated.resources.ports_dynamic_proxy
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.ports_open_in_browser

/** Placeholder in a numeric cell with nothing to show yet (a tunnel that has carried no bytes). */
private const val NO_VALUE = "—"

private val TYPE_WIDTH = 76.dp
private val ARROW_WIDTH = 18.dp
private val HOST_WIDTH = 100.dp
private val TRAFFIC_WIDTH = 72.dp
private val STATUS_WIDTH = 86.dp
private val ACTIONS_WIDTH = 62.dp

/**
 * Column header of the tunnel table. Kept next to the row that fills it — the two share the width
 * constants above, and a column added to one without the other silently skews the grid.
 */
@Composable
internal fun TunnelHeaderRow() {
    TunnelGridRow(Modifier.background(Skerry.colors.overlayFaint).padding(vertical = 10.dp)) {
        HeaderCell(stringResource(Res.string.ports_col_name), Modifier.weight(1f))
        HeaderCell(stringResource(Res.string.ports_col_type), Modifier.width(TYPE_WIDTH))
        HeaderCell(stringResource(Res.string.ports_col_listen), Modifier.weight(1.1f))
        Box(Modifier.width(ARROW_WIDTH))
        HeaderCell(stringResource(Res.string.ports_col_target), Modifier.weight(1.1f))
        HeaderCell(stringResource(Res.string.ports_col_host), Modifier.width(HOST_WIDTH))
        HeaderCell(stringResource(Res.string.ports_col_traffic), Modifier.width(TRAFFIC_WIDTH), end = true)
        HeaderCell(stringResource(Res.string.ports_col_status), Modifier.width(STATUS_WIDTH))
        Box(Modifier.width(ACTIONS_WIDTH))
    }
}

/**
 * One saved tunnel: name, type, both endpoints with the arrow showing which way traffic goes,
 * host, bytes carried since it came up, status, and the on/off switch. A failed tunnel spells the
 * reason out under the row — the STATUS badge only says that something went wrong.
 */
@Composable
internal fun TunnelRow(
    entry: TunnelEntry,
    via: String,
    mono: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val t = entry.tunnel
    val (typeBg, typeFg) = t.direction.badgeColors()
    val badge = entry.status.badge()
    val (statusBg, statusFg) = badge.colors()
    val dim = entry.status !is TunnelStatus.Active
    val addressColor = if (dim) Skerry.colors.dim else Skerry.colors.textBright

    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(if (selected) Skerry.colors.cyan08 else Color.Transparent),
    ) {
        TunnelGridRow(Modifier.padding(vertical = 12.dp)) {
            Txt(
                t.label,
                color = if (dim) Skerry.colors.text else Skerry.colors.textBright,
                size = 12.5.sp,
                weight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(TYPE_WIDTH)) {
                Badge(t.direction.badgeLabel(), bg = typeBg, fg = typeFg, radius = 4, size = 10.sp)
            }
            Txt(listenText(entry), color = addressColor, size = 12.5.sp, font = mono, modifier = Modifier.weight(1.1f))
            Box(Modifier.width(ARROW_WIDTH)) {
                Sym(flowIcon(t.direction), size = 15.sp, color = Skerry.colors.faint)
            }
            Txt(
                targetText(t.destHost, t.destPort),
                color = if (t.destHost == null) Skerry.colors.dim else addressColor,
                size = 12.5.sp,
                font = mono,
                modifier = Modifier.weight(1.1f),
            )
            Txt(via, color = Skerry.colors.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(HOST_WIDTH))
            EndCell(Modifier.width(TRAFFIC_WIDTH)) {
                Txt(trafficText(entry), color = Skerry.colors.dim, size = 11.5.sp, font = mono)
            }
            Box(Modifier.width(STATUS_WIDTH)) {
                Badge(badge.label(), bg = statusBg, fg = statusFg, radius = 4, size = 10.sp)
            }
            Row(
                Modifier.width(ACTIONS_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenInBrowserAction(entry)
                TunnelSwitch(entry, t.label, onToggle)
            }
        }
        (entry.status as? TunnelStatus.Failed)?.let {
            Txt(
                tunnelFailureText(it),
                color = Skerry.colors.sunset,
                size = 11.sp,
                font = mono,
                modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
            )
        }
    }
}

/** Shared geometry of the header and the rows — one place decides padding and column spacing. */
@Composable
private fun TunnelGridRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(text, color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun EndCell(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.CenterEnd, content = content)
}

/** Listener side: bind address with the port actually bound once up, the requested one before that. */
@Composable
private fun listenText(entry: TunnelEntry): String {
    val port = (entry.status as? TunnelStatus.Active)?.boundPort ?: entry.tunnel.bindPort
    return "${entry.tunnel.bindHost}:$port"
}

/** Destination side; SOCKS has none — the client picks one per connection. */
@Composable
private fun targetText(destHost: String?, destPort: Int?): String =
    if (destHost == null || destPort == null) stringResource(Res.string.ports_dynamic_proxy) else "$destHost:$destPort"

/** Bytes carried since the tunnel came up; counters reset on every raise, so a stopped one shows nothing. */
@Composable
private fun trafficText(entry: TunnelEntry): String {
    val total = entry.bytesUp + entry.bytesDown
    return if (total == 0L) NO_VALUE else humanSize(total)
}

private fun flowIcon(direction: TunnelDirection): String =
    if (direction.flow() == TunnelFlow.Inbound) "arrow_back" else "arrow_forward"

/**
 * Opens a live local forward in the browser. Only shown when there is something to open: `-R`
 * listens on the server and `-D` is a SOCKS proxy, so neither gets a link.
 */
@Composable
private fun OpenInBrowserAction(entry: TunnelEntry) {
    val url = tunnelBrowserUrl(entry) ?: return
    val uriHandler = LocalUriHandler.current
    Sym(
        "open_in_new",
        contentDescription = stringResource(Res.string.ports_open_in_browser),
        size = 15.sp,
        color = Skerry.colors.cyanBright,
        // A failing system handler must not throw into the composition (see AboutSection).
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } },
    )
}

/**
 * On/off switch; an hourglass stands in while the tunnel is still dialling. Named after the tunnel
 * it raises — the row draws that name too, but the switch is a control of its own.
 */
@Composable
private fun TunnelSwitch(entry: TunnelEntry, label: String, onToggle: () -> Unit) {
    when (entry.status) {
        is TunnelStatus.Active -> Toggle(on = true, onToggle = onToggle, label = label)
        TunnelStatus.Connecting -> Sym("hourglass_top", size = 16.sp, color = Skerry.colors.amber)
        else -> Toggle(on = false, onToggle = onToggle, label = label)
    }
}
