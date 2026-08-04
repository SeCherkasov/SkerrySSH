package app.skerry.ui.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Card
import app.skerry.ui.design.Sparkline
import app.skerry.ui.design.Txt
import app.skerry.ui.forward.humanRate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_autostart
import app.skerry.ui.generated.resources.ports_autostart_none
import app.skerry.ui.generated.resources.ports_card_errors
import app.skerry.ui.generated.resources.ports_card_throughput
import app.skerry.ui.generated.resources.ports_errors_none
import app.skerry.ui.generated.resources.ports_errors_updated
import app.skerry.ui.generated.resources.ports_rate_in
import app.skerry.ui.generated.resources.ports_rate_out
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/**
 * Sparkline scale floor (64 KiB/s), as in the host monitor: without it an idle section would
 * auto-scale a trickle of keepalive traffic into a dramatic mountain range.
 */
private const val RATE_SCALE_FLOOR = 64L * 1024

/** Room above the window's peak, so a steady load draws a line rather than a filled rectangle. */
private const val SCALE_HEADROOM = 1.25f

/**
 * The three cards under the tunnel table: what is flowing right now, what comes up on its own, and
 * what recently broke. Read-only — every one of them is a view over [TunnelManager] state, and none
 * of them is a control.
 */
@Composable
internal fun TunnelDashboard(
    manager: TunnelManager,
    hostLabel: (String) -> String,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    // IntrinsicSize.Min plus fillMaxHeight on each card: the three read as one strip, instead of
    // three boxes whose bottom edge depends on how much each happens to have to say.
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThroughputCard(manager.telemetry, mono, Modifier.weight(1.3f).fillMaxHeight())
        AutostartCard(manager.tunnels, hostLabel, Modifier.weight(1f).fillMaxHeight())
        ErrorsCard(manager.telemetry, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ThroughputCard(telemetry: TunnelTelemetry, mono: FontFamily, modifier: Modifier) {
    val history = telemetry.history
    val latest = history.lastOrNull()
    // Both directions share one scale so the line means "total load", not "whichever is bigger".
    // The headroom keeps a steady load off the top edge, where a full-height fill reads as a solid
    // block rather than a chart.
    val peak = history.maxOfOrNull { it.up + it.down } ?: 0L
    val scale = max(peak, RATE_SCALE_FLOOR) * SCALE_HEADROOM

    Card(modifier, stringResource(Res.string.ports_card_throughput)) {
        Sparkline(
            values = history.map { (it.up + it.down) / scale },
            color = Skerry.colors.cyan,
            modifier = Modifier.padding(top = 8.dp),
            height = 34.dp,
            capacity = TunnelTelemetry.HISTORY_CAPACITY,
        )
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RateStat(humanRate(latest?.up ?: 0), stringResource(Res.string.ports_rate_out), mono)
            RateStat(humanRate(latest?.down ?: 0), stringResource(Res.string.ports_rate_in), mono)
        }
    }
}

@Composable
private fun RowScope.RateStat(value: String, caption: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Txt(value, color = Skerry.colors.textBright, size = 15.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(caption, color = Skerry.colors.faint, size = 10.5.sp, modifier = Modifier.padding(bottom = 1.dp))
    }
}

@Composable
private fun AutostartCard(entries: List<TunnelEntry>, hostLabel: (String) -> String, modifier: Modifier) {
    val grouped = autostartByHost(entries)
    Card(modifier, stringResource(Res.string.ports_autostart)) {
        if (grouped.isEmpty()) {
            CardNote(stringResource(Res.string.ports_autostart_none))
        } else {
            grouped.forEach { (hostId, count) ->
                CardRow(hostLabel(hostId), tunnelCountText(count), Skerry.colors.text)
            }
        }
    }
}

@Composable
private fun ErrorsCard(telemetry: TunnelTelemetry, modifier: Modifier) {
    val events = telemetry.events
    Card(modifier, stringResource(Res.string.ports_card_errors)) {
        if (events.isEmpty()) {
            CardNote(stringResource(Res.string.ports_errors_none))
        } else {
            events.forEach { event ->
                CardRow("${event.label} · ${event.port}", event.kind.label(), event.kind.color())
            }
            CardRow(
                stringResource(Res.string.ports_errors_updated),
                eventAgeText(telemetry.secondsSince(events.first())),
                Skerry.colors.dim,
            )
        }
    }
}

/** Label on the left, value on the right — the shape every dashboard card row shares. */
@Composable
private fun CardRow(label: String, value: String, valueColor: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(end = 8.dp))
        Txt(value, color = valueColor, size = 11.5.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun CardNote(text: String) {
    Box(Modifier.padding(top = 8.dp)) {
        Txt(text, color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp)
    }
}
