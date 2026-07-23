package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_monitor_title
import app.skerry.ui.metrics.HostMetrics
import app.skerry.ui.metrics.HostMonitorSections
import app.skerry.ui.metrics.MetricsAvailability
import app.skerry.ui.metrics.MetricsSample
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Phone counterpart of the desktop info panel's monitor: the same blocks (CPU/memory/swap with
 * sparklines, network rates, filesystems, top processes) in a bottom sheet raised from the
 * terminal's `more_horiz` menu — a phone has no room for a permanent side panel.
 *
 * The caller only opens this for a connected session, so [ConnectionController.openMetrics] (which
 * requires a live connection) is safe here. The poller is shared with the desktop panel and cached
 * per session, so opening and closing the sheet doesn't restart polling or lose the history.
 */
@Composable
fun MobileHostMonitorSheet(controller: ConnectionController, onDismiss: () -> Unit) {
    val monitor = remember(controller) { controller.openMetrics() }
    MobileHostMonitorSheet(
        metrics = monitor.metrics,
        history = monitor.history,
        netRxRate = monitor.netRxRate,
        netTxRate = monitor.netTxRate,
        availability = monitor.availability,
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
    onDismiss: () -> Unit,
) {
    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        maxHeightFraction = 0.8f,
    ) {
        Txt(stringResource(Res.string.term_monitor_title), color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        // Capped height rather than a weight: the sheet panel itself is a plain Column, and a
        // weighted child inside it fails to measure the scrollable content.
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            HostMonitorSections(
                metrics = metrics,
                history = history,
                netRxRate = netRxRate,
                netTxRate = netTxRate,
                availability = availability,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
