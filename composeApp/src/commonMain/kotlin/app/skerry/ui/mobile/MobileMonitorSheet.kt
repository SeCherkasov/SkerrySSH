package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.mon_tip_refresh
import app.skerry.ui.generated.resources.term_monitor_title
import app.skerry.ui.generated.resources.term_monitor_unavailable
import app.skerry.ui.metrics.HostAlert
import app.skerry.ui.metrics.HostMetrics
import app.skerry.ui.metrics.MetricsAvailability
import app.skerry.ui.metrics.MetricsSample
import app.skerry.ui.metrics.MonitorCardsBody
import app.skerry.ui.metrics.MonitorIntervalMenu
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Phone counterpart of the desktop monitor: the same cards (resource tiles, top processes, units
 * and mounts, containers, alerts) in a bottom sheet raised from the terminal's `more_horiz` menu —
 * a phone has no work area to give a screen of its own to, and the sheet keeps the shell one tap
 * away.
 *
 * The poller is shared with the desktop screen and cached per session, so opening and closing the
 * sheet doesn't restart polling or lose the history. A pane watching a colleague's shared session
 * has no connection to poll ([ConnectionController.openMetrics] is null): nothing is shown.
 */
@Composable
fun MobileHostMonitorSheet(controller: ConnectionController, onDismiss: () -> Unit) {
    val monitor = remember(controller, controller.metricsEpoch) { controller.openMetrics() } ?: return
    MobileHostMonitorSheet(
        metrics = monitor.metrics,
        history = monitor.history,
        netRxRate = monitor.netRxRate,
        netTxRate = monitor.netTxRate,
        availability = monitor.availability,
        alerts = monitor.alerts.entries,
        intervalMs = monitor.intervalMs,
        onRefresh = monitor::refreshNow,
        onInterval = monitor::setInterval,
        onDismiss = onDismiss,
    )
}

/** Rendering half of the sheet, split off the poller so previews/screenshots can feed it a snapshot. */
@Composable
internal fun MobileHostMonitorSheet(
    metrics: HostMetrics?,
    history: List<MetricsSample>,
    netRxRate: Long,
    netTxRate: Long,
    availability: MetricsAvailability,
    alerts: List<HostAlert>,
    intervalMs: Long,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit = {},
    onInterval: (Long) -> Unit = {},
    // Fixed stamp for the offscreen render; a live sheet passes null and the alerts card ticks.
    nowMillis: Long? = null,
) {
    val mono = LocalFonts.current.mono
    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        maxHeightFraction = 0.8f,
    ) {
        // Desktop parity: the phone gets the same two controls the work bar carries — poll now, and
        // choose how often. Without them the Collection row would name an interval nothing can change.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.term_monitor_title), color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconBtn("refresh", onClick = onRefresh, box = 30, tooltip = stringResource(Res.string.mon_tip_refresh))
            MonitorIntervalMenu(intervalMs, onInterval)
        }
        Spacer(Modifier.height(10.dp))
        if (availability == MetricsAvailability.Unsupported) {
            Txt(stringResource(Res.string.term_monitor_unavailable), color = Skerry.colors.faint, size = 12.sp)
            Spacer(Modifier.height(8.dp))
            return@MobileBottomSheet
        }
        // Capped height rather than a weight: the sheet panel itself is a plain Column, and a
        // weighted child inside it fails to measure the scrollable content.
        MonitorCardsBody(
            metrics = metrics,
            history = history,
            netRxRate = netRxRate,
            netTxRate = netTxRate,
            alerts = alerts,
            intervalMs = intervalMs,
            nowMillis = nowMillis,
            // The sheet is raised from the session it belongs to, and the terminal chrome above it
            // already says which host that is — a connection card here would repeat it.
            facts = null,
            mono = mono,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
