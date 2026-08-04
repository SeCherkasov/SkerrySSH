package app.skerry.ui.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Card
import app.skerry.ui.design.HLine
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sparkline
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.mon_age_days
import app.skerry.ui.generated.resources.mon_age_hours
import app.skerry.ui.generated.resources.mon_age_minutes
import app.skerry.ui.generated.resources.mon_age_now
import app.skerry.ui.generated.resources.mon_age_yesterday
import app.skerry.ui.generated.resources.mon_alert_disk
import app.skerry.ui.generated.resources.mon_alert_disk_ok
import app.skerry.ui.generated.resources.mon_alert_load
import app.skerry.ui.generated.resources.mon_alert_load_ok
import app.skerry.ui.generated.resources.mon_alert_memory
import app.skerry.ui.generated.resources.mon_alert_memory_ok
import app.skerry.ui.generated.resources.mon_alert_swap
import app.skerry.ui.generated.resources.mon_alert_swap_ok
import app.skerry.ui.generated.resources.mon_card_alerts
import app.skerry.ui.generated.resources.mon_card_containers
import app.skerry.ui.generated.resources.mon_card_processes
import app.skerry.ui.generated.resources.mon_card_services
import app.skerry.ui.generated.resources.mon_col_command
import app.skerry.ui.generated.resources.mon_col_cpu
import app.skerry.ui.generated.resources.mon_col_image
import app.skerry.ui.generated.resources.mon_col_name
import app.skerry.ui.generated.resources.mon_col_pid
import app.skerry.ui.generated.resources.mon_col_rss
import app.skerry.ui.generated.resources.mon_col_status
import app.skerry.ui.generated.resources.mon_collection
import app.skerry.ui.generated.resources.mon_interval
import app.skerry.ui.generated.resources.mon_interval_value
import app.skerry.ui.generated.resources.mon_mounts
import app.skerry.ui.generated.resources.mon_no_alerts
import app.skerry.ui.generated.resources.mon_no_services
import app.skerry.ui.generated.resources.mon_service_state
import app.skerry.ui.generated.resources.mon_source
import app.skerry.ui.generated.resources.mon_source_value
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sync.nowMillis
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

// The cards the monitor screen is built out of: the four resource tiles across the top, then the
// tables and lists under them. Each one draws a snapshot it is handed — no polling lives here.

/** Height of a tile's sparkline — tall enough to have a shape, short enough to keep the tile small. */
private val SPARK_HEIGHT = 40.dp

/**
 * One resource tile: uppercase caption, the big number with its unit, the history behind it and a
 * line of context underneath ("4 cores · load 0.42 0.51 0.47").
 */
@Composable
internal fun MonitorStatCard(
    caption: String,
    value: String,
    unit: String,
    history: List<Float>,
    color: Color,
    foot: String,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    fill: Float? = null,
) {
    Card(modifier) {
        Txt(
            labelUppercase(caption),
            color = Skerry.colors.faint,
            size = 10.5.sp,
            weight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Txt(value, color = Skerry.colors.textBright, size = 26.sp, weight = FontWeight.SemiBold, font = mono)
            if (unit.isNotEmpty()) {
                Txt(unit, color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(start = 5.dp, bottom = 4.dp))
            }
        }
        Box(Modifier.padding(top = 6.dp, bottom = 6.dp).height(SPARK_HEIGHT), contentAlignment = Alignment.Center) {
            // A level, not a flow: disk fill sits near the top of the scale for hours on end, and a
            // line chart of it reads as a solid block. The other three tiles chart their history.
            if (fill != null) MeterBar(fill, color) else Sparkline(history, color, height = SPARK_HEIGHT, capacity = METRICS_HISTORY_SIZE)
        }
        Txt(foot, color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The `ps` top list: command, pid, CPU share, resident memory. */
@Composable
internal fun MonitorProcessCard(processes: List<ProcessSample>, mono: FontFamily, modifier: Modifier = Modifier) {
    Card(modifier, stringResource(Res.string.mon_card_processes)) {
        TableHeader {
            HeaderCell(stringResource(Res.string.mon_col_command), Modifier.weight(1f))
            HeaderCell(stringResource(Res.string.mon_col_pid), Modifier.width(PID_WIDTH), end = true)
            HeaderCell(stringResource(Res.string.mon_col_cpu), Modifier.width(NUMBER_WIDTH), end = true)
            HeaderCell(stringResource(Res.string.mon_col_rss), Modifier.width(SIZE_WIDTH), end = true)
        }
        processes.forEach { process ->
            TableRow {
                Txt(
                    process.command,
                    color = Skerry.colors.textBright, size = 12.sp, font = mono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Cell(process.pid.toString(), Modifier.width(PID_WIDTH), mono)
                Cell(
                    "${percentText(process.cpuPercent)}%",
                    Modifier.width(NUMBER_WIDTH),
                    mono,
                    color = if (process.cpuPercent > ALERT_PERCENT) Skerry.colors.sunset else Skerry.colors.text,
                )
                Cell(humanSize(process.rssBytes), Modifier.width(SIZE_WIDTH), mono)
            }
        }
    }
}

/** systemd units on top, the host's real filesystems as meters underneath. */
@Composable
internal fun MonitorServicesCard(
    services: List<ServiceUnit>,
    disks: List<DiskUsage>,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Card(modifier, stringResource(Res.string.mon_card_services)) {
        Spacer(Modifier.height(4.dp))
        if (services.isEmpty()) {
            Txt(stringResource(Res.string.mon_no_services), color = Skerry.colors.faint, size = 11.5.sp)
        }
        services.forEach { unit ->
            KeyValue(
                key = unit.name,
                value = stringResource(Res.string.mon_service_state, unit.state.label(), unit.sub),
                valueColor = unit.state.color(),
                mono = mono,
            )
        }
        if (disks.isNotEmpty()) {
            BlockTitle(stringResource(Res.string.mon_mounts))
            disks.forEach { disk -> MountMeter(disk, mono) }
        }
    }
}

/** Running containers: name, image, CPU share (when `stats` answered), status. */
@Composable
internal fun MonitorContainersCard(
    containers: List<ContainerSample>,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Card(modifier, stringResource(Res.string.mon_card_containers)) {
        TableHeader {
            HeaderCell(stringResource(Res.string.mon_col_name), Modifier.weight(1f))
            HeaderCell(stringResource(Res.string.mon_col_image), Modifier.weight(1f))
            HeaderCell(stringResource(Res.string.mon_col_cpu), Modifier.width(NUMBER_WIDTH), end = true)
            HeaderCell(stringResource(Res.string.mon_col_status), Modifier.width(STATUS_WIDTH), end = true)
        }
        containers.forEach { container ->
            TableRow {
                Txt(
                    container.name,
                    color = Skerry.colors.textBright, size = 12.sp, font = mono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Txt(
                    container.image,
                    color = Skerry.colors.dim, size = 12.sp, font = mono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Cell(container.cpuPercent?.let { "${percentText(it)}%" } ?: NO_VALUE, Modifier.width(NUMBER_WIDTH), mono)
                Cell(container.status, Modifier.width(STATUS_WIDTH), mono, color = Skerry.colors.moss)
            }
        }
    }
}

/**
 * Thresholds crossed since this session connected, newest first, and — under them — what the poller
 * is actually doing, so the numbers above can be taken at face value.
 */
@Composable
internal fun MonitorAlertsCard(
    alerts: List<HostAlert>,
    intervalMs: Long,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    // Fixed stamp for the offscreen renders, which must come out identical between runs. A live
    // screen passes null and the card reads the clock itself — the ages it prints are minute-grained,
    // so it re-reads once a minute rather than dragging the whole screen through a second-by-second
    // recomposition.
    nowMillis: Long? = null,
) {
    val now = nowMillis ?: minuteTick()
    Card(modifier, stringResource(Res.string.mon_card_alerts)) {
        Spacer(Modifier.height(4.dp))
        if (alerts.isEmpty()) {
            Txt(stringResource(Res.string.mon_no_alerts), color = Skerry.colors.faint, size = 11.5.sp)
        }
        alerts.forEach { alert -> AlertRow(alert, now, mono) }
        BlockTitle(stringResource(Res.string.mon_collection))
        KeyValue(
            key = stringResource(Res.string.mon_interval),
            value = stringResource(Res.string.mon_interval_value, (intervalMs / 1000).toInt()),
            valueColor = Skerry.colors.text,
            mono = mono,
        )
        KeyValue(
            key = stringResource(Res.string.mon_source),
            value = stringResource(Res.string.mon_source_value),
            valueColor = Skerry.colors.text,
            mono = mono,
        )
    }
}

/** Wall clock re-read once a minute, for the ages the alert feed prints. */
@Composable
private fun minuteTick(): Long {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = nowMillis()
        }
    }
    return now
}

@Composable
private fun AlertRow(alert: HostAlert, nowMillis: Long, mono: FontFamily) {
    val severity = if (alert.active) alert.kind.color() else Skerry.colors.moss
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sym(if (alert.active) "warning" else "check_circle", size = 13.sp, color = severity)
            Txt(
                alertText(alert), color = Skerry.colors.dim, size = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Txt(ageText(alertAge(nowMillis - alert.atMillis)), color = severity, size = 11.5.sp, font = mono, maxLines = 1)
    }
}

/** What an entry of the feed says — a raise and its recovery are two wordings of the same kind. */
@Composable
private fun alertText(alert: HostAlert): String = when (alert.kind) {
    AlertKind.DiskFull ->
        if (alert.active) {
            stringResource(Res.string.mon_alert_disk, alert.subject)
        } else {
            stringResource(Res.string.mon_alert_disk_ok, alert.subject)
        }
    AlertKind.MemoryHigh ->
        if (alert.active) stringResource(Res.string.mon_alert_memory, alert.subject) else stringResource(Res.string.mon_alert_memory_ok)
    AlertKind.SwapHeavy ->
        if (alert.active) stringResource(Res.string.mon_alert_swap, alert.subject) else stringResource(Res.string.mon_alert_swap_ok)
    AlertKind.LoadHigh ->
        if (alert.active) stringResource(Res.string.mon_alert_load, alert.subject) else stringResource(Res.string.mon_alert_load_ok)
}

@Composable
private fun ageText(age: AlertAge): String = when (age) {
    AlertAge.Now -> stringResource(Res.string.mon_age_now)
    is AlertAge.Minutes -> stringResource(Res.string.mon_age_minutes, age.value)
    is AlertAge.Hours -> stringResource(Res.string.mon_age_hours, age.value)
    AlertAge.Yesterday -> stringResource(Res.string.mon_age_yesterday)
    is AlertAge.Days -> stringResource(Res.string.mon_age_days, age.value)
}

/** How loudly a raised alert is drawn: a full disk is red, the rest amber until it is one. */
@Composable
private fun AlertKind.color(): Color = when (this) {
    AlertKind.DiskFull -> Skerry.colors.sunset
    AlertKind.MemoryHigh, AlertKind.SwapHeavy, AlertKind.LoadHigh -> Skerry.colors.amber
}

@Composable
private fun ServiceState.color(): Color = when (this) {
    ServiceState.Active -> Skerry.colors.moss
    ServiceState.Activating -> Skerry.colors.amber
    ServiceState.Failed -> Skerry.colors.sunset
    ServiceState.Other -> Skerry.colors.dim
}

/** The ACTIVE column verbatim — it is systemd's own vocabulary, and translating it would obscure it. */
private fun ServiceState.label(): String = when (this) {
    ServiceState.Active -> "active"
    ServiceState.Activating -> "activating"
    ServiceState.Failed -> "failed"
    ServiceState.Other -> "inactive"
}

@Composable
private fun MountMeter(disk: DiskUsage, mono: FontFamily) {
    val alert = disk.percent > ALERT_PERCENT
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(
                disk.mount, color = Skerry.colors.dim, size = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Txt(
                "${humanSize(disk.usedBytes)} / ${humanSize(disk.totalBytes)}",
                color = if (alert) Skerry.colors.sunset else Skerry.colors.textBright,
                size = 11.5.sp, font = mono, maxLines = 1,
            )
        }
        MeterBar(disk.fraction, if (alert) Skerry.colors.sunset else Skerry.colors.cyan)
    }
}

/** Label on the left, monospaced value on the right — the shape of every list row in these cards. */
@Composable
internal fun KeyValue(key: String, value: String, valueColor: Color, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            key, color = Skerry.colors.dim, size = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Txt(value, color = valueColor, size = 12.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Heading of a block inside a card ("Mounts", "Collection"). */
@Composable
internal fun BlockTitle(text: String) {
    Txt(
        labelUppercase(text),
        color = Skerry.colors.faint,
        size = 10.sp,
        weight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun TableHeader(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.cyan08).padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        HLine()
    }
}

@Composable
private fun TableRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        HLine()
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(
            labelUppercase(text),
            color = Skerry.colors.faint,
            size = 10.sp,
            weight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier, mono: FontFamily, color: Color = Skerry.colors.text) {
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Txt(text, color = color, size = 12.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val PID_WIDTH = 54.dp
private val NUMBER_WIDTH = 58.dp
private val SIZE_WIDTH = 72.dp
private val STATUS_WIDTH = 92.dp

/** Cell with nothing to show: a container the `stats` call didn't cover. */
private const val NO_VALUE = "—"

/** Percentages with one decimal, without String.format (unavailable in commonMain). */
private fun percentText(value: Float): String {
    val tenths = (value.coerceIn(0f, 999f) * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
