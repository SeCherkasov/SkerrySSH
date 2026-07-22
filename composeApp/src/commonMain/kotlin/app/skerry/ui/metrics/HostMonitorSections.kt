package app.skerry.ui.metrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sparkline
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.forward.humanRate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_live_metrics
import app.skerry.ui.generated.resources.term_metric_cpu
import app.skerry.ui.generated.resources.term_metric_memory
import app.skerry.ui.generated.resources.term_metric_swap
import app.skerry.ui.generated.resources.term_monitor_col_command
import app.skerry.ui.generated.resources.term_monitor_col_cpu
import app.skerry.ui.generated.resources.term_monitor_col_mem
import app.skerry.ui.generated.resources.term_monitor_filesystems
import app.skerry.ui.generated.resources.term_monitor_network
import app.skerry.ui.generated.resources.term_monitor_processes
import app.skerry.ui.generated.resources.term_monitor_unavailable
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt
import app.skerry.ui.theme.Skerry

// Host monitor blocks shared by the desktop info panel and the mobile monitor sheet: resource
// meters with sparklines, network rates, mounted filesystems, and the top processes.

/** Above this fill a resource reads as a problem and its value turns red. */
private const val ALERT_PERCENT = 85

/**
 * Sparkline scale floor for network rates (64 KiB/s): an idle link would otherwise auto-scale its
 * own noise to full height and look like saturated traffic.
 */
private const val NET_SCALE_FLOOR = 64L * 1024

/**
 * How a sparkline appears once its second sample lands. Without it the chart snaps into place and
 * shoves everything below it down in one frame — a jarring redraw a few seconds after connecting.
 * Fading and growing in turns that into a soft reveal.
 */
private val SparklineReveal = fadeIn(tween(350)) + expandVertically(tween(350))

/**
 * The whole live part of the monitor: CPU / memory / swap meters with their history, network
 * rates, filesystems, and top processes.
 *
 * [metrics] is null until the first successful poll — the meters then show "…" rather than zeros,
 * which would read as a genuinely idle host. On a host that can't serve metrics at all
 * ([MetricsAvailability.Unsupported]: no exec channel, or non-Linux output) a single explanatory
 * line replaces the blocks.
 */
@Composable
fun HostMonitorSections(
    metrics: HostMetrics?,
    history: List<MetricsSample>,
    netRxRate: Long,
    netTxRate: Long,
    availability: MetricsAvailability,
    modifier: Modifier = Modifier,
) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxWidth()) {
        MonitorSectionTitle(stringResource(Res.string.term_live_metrics))
        if (availability == MetricsAvailability.Unsupported) {
            MonitorUnavailable()
            return@Column
        }
        val cpu = metrics?.cpuPercent
        MonitorMeter(
            label = stringResource(Res.string.term_metric_cpu),
            value = cpu?.let { "$it%" } ?: PENDING,
            fraction = metrics?.cpuFraction ?: 0f,
            bar = Skerry.colors.cyan,
            alert = (cpu ?: 0) > ALERT_PERCENT,
            history = history.map { it.cpuPercent / 100f },
            mono = mono,
        )
        MonitorMeter(
            label = stringResource(Res.string.term_metric_memory),
            value = metrics?.let { gbPair(it.memUsedBytes, it.memTotalBytes) } ?: PENDING,
            fraction = metrics?.memFraction ?: 0f,
            bar = Skerry.colors.moss,
            alert = false,
            history = history.map { it.memPercent / 100f },
            mono = mono,
        )
        // Swap only exists on hosts that configured it — a permanent "0 / 0" row is noise.
        if (metrics != null && metrics.swapTotalBytes > 0) {
            MonitorMeter(
                label = stringResource(Res.string.term_metric_swap),
                value = gbPair(metrics.swapUsedBytes, metrics.swapTotalBytes),
                fraction = metrics.swapFraction,
                bar = Skerry.colors.amber,
                // Swap in use at all is worth noticing, but only heavy use is an alert.
                alert = metrics.swapFraction > 0.5f,
                history = emptyList(),
                mono = mono,
            )
        }

        // Network: counters exist only when /proc/net/dev was readable.
        if (metrics?.netRxBytes != null) {
            MonitorSectionTitle(stringResource(Res.string.term_monitor_network))
            MonitorNetwork(netRxRate, netTxRate, history, mono)
        }

        if (!metrics?.disks.isNullOrEmpty()) {
            MonitorSectionTitle(stringResource(Res.string.term_monitor_filesystems))
            metrics.disks.forEach { disk ->
                MonitorMeter(
                    label = disk.mount,
                    value = "${gbPair(disk.usedBytes, disk.totalBytes)} · ${disk.percent}%",
                    fraction = disk.fraction,
                    bar = if (disk.percent > ALERT_PERCENT) Skerry.colors.sunset else Skerry.colors.cyan,
                    alert = disk.percent > ALERT_PERCENT,
                    history = emptyList(),
                    mono = mono,
                )
            }
        }

        if (!metrics?.processes.isNullOrEmpty()) {
            MonitorSectionTitle(stringResource(Res.string.term_monitor_processes))
            MonitorProcessHeader(mono)
            metrics.processes.forEach { MonitorProcessRow(it, mono) }
        }
    }
}

/** Section heading of the monitor (same treatment as the info panel's own headings). */
@Composable
fun MonitorSectionTitle(text: String) {
    Txt(
        text,
        color = Skerry.colors.faint,
        size = 10.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
    )
}

/** Label + value, a fill bar, and (when there is history) the sparkline of that metric. */
@Composable
private fun MonitorMeter(
    label: String,
    value: String,
    fraction: Float,
    bar: Color,
    alert: Boolean,
    history: List<Float>,
    mono: FontFamily,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(label, color = Skerry.colors.dim, size = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Txt(value, color = if (alert) Skerry.colors.sunset else Skerry.colors.textBright, size = 11.sp, font = mono, maxLines = 1)
        }
        MeterBar(fraction, if (alert) Skerry.colors.sunset else bar)
        AnimatedVisibility(visible = history.size >= 2, enter = SparklineReveal) {
            Sparkline(history, bar, Modifier.padding(top = 4.dp), height = 26.dp, capacity = METRICS_HISTORY_SIZE)
        }
    }
}

/**
 * Receive/transmit rates with a shared sparkline pair. Both lines share one scale (the window's
 * peak, floored at [NET_SCALE_FLOOR]) so up- and downstream stay visually comparable.
 */
@Composable
private fun MonitorNetwork(rxRate: Long, txRate: Long, history: List<MetricsSample>, mono: FontFamily) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MonitorRateLabel("arrow_downward", humanRate(rxRate), Skerry.colors.cyan, mono)
            MonitorRateLabel("arrow_upward", humanRate(txRate), Skerry.colors.moss, mono)
        }
        val peak = history.maxOfOrNull { max(it.rxBytesPerSec, it.txBytesPerSec) } ?: 0L
        val scale = max(peak, NET_SCALE_FLOOR).toFloat()
        AnimatedVisibility(visible = history.size >= 2, enter = SparklineReveal) {
            Column {
                Sparkline(history.map { it.rxBytesPerSec / scale }, Skerry.colors.cyan, Modifier.padding(top = 6.dp), height = 26.dp, capacity = METRICS_HISTORY_SIZE)
                Sparkline(history.map { it.txBytesPerSec / scale }, Skerry.colors.moss, Modifier.padding(top = 2.dp), height = 26.dp, capacity = METRICS_HISTORY_SIZE)
            }
        }
    }
}

@Composable
private fun MonitorRateLabel(icon: String, text: String, color: Color, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Sym(icon, size = 12.sp, color = color)
        Txt(text, color = Skerry.colors.textBright, size = 11.sp, font = mono)
    }
}

@Composable
private fun MonitorProcessHeader(mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Txt(stringResource(Res.string.term_monitor_col_cpu), color = Skerry.colors.faint, size = 10.sp, font = mono, modifier = Modifier.width(42.dp))
        Txt(stringResource(Res.string.term_monitor_col_mem), color = Skerry.colors.faint, size = 10.sp, font = mono, modifier = Modifier.width(42.dp))
        Txt(stringResource(Res.string.term_monitor_col_command), color = Skerry.colors.faint, size = 10.sp, font = mono)
    }
}

@Composable
private fun MonitorProcessRow(process: ProcessSample, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Txt(
            percentText(process.cpuPercent),
            color = if (process.cpuPercent > ALERT_PERCENT) Skerry.colors.sunset else Skerry.colors.textBright,
            size = 10.5.sp, font = mono, modifier = Modifier.width(42.dp),
        )
        Txt(percentText(process.memPercent), color = Skerry.colors.textBright, size = 10.5.sp, font = mono, modifier = Modifier.width(42.dp))
        Txt(process.command, color = Skerry.colors.dim, size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A host that can't answer at all: say so once instead of leaving "…" under a dead poller. */
@Composable
private fun MonitorUnavailable() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym("info", size = 13.sp, color = Skerry.colors.faint)
        Txt(stringResource(Res.string.term_monitor_unavailable), color = Skerry.colors.faint, size = 11.sp)
    }
}

/** Placeholder shown until the first poll lands (never a zero, which would read as a real value). */
private const val PENDING = "…"

/** `ps` percentages with one decimal, without String.format (unavailable in commonMain). */
private fun percentText(value: Float): String {
    val tenths = (value.coerceIn(0f, 999f) * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** `used / total GB` on one line (decimal GB with one decimal, like `free -h`). */
private fun gbPair(used: Long, total: Long): String = "${gb(used)} / ${gb(total)} GB"

private fun gb(bytes: Long): String {
    val tenths = (bytes / 100_000_000.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
