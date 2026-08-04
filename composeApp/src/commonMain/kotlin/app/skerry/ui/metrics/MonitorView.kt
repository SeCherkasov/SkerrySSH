package app.skerry.ui.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.shortCipher
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.forward.humanRate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.mon_foot_cpu
import app.skerry.ui.generated.resources.mon_foot_disk
import app.skerry.ui.generated.resources.mon_foot_memory
import app.skerry.ui.generated.resources.mon_foot_network
import app.skerry.ui.generated.resources.mon_no_session
import app.skerry.ui.generated.resources.mon_stat_cpu
import app.skerry.ui.generated.resources.mon_stat_disk
import app.skerry.ui.generated.resources.mon_stat_memory
import app.skerry.ui.generated.resources.mon_stat_network
import app.skerry.ui.generated.resources.mon_subtitle_minutes
import app.skerry.ui.generated.resources.mon_subtitle_pending
import app.skerry.ui.generated.resources.mon_subtitle_seconds
import app.skerry.ui.generated.resources.mon_tip_back
import app.skerry.ui.generated.resources.mon_tip_interval
import app.skerry.ui.generated.resources.mon_tip_refresh
import app.skerry.ui.generated.resources.mon_interval
import app.skerry.ui.generated.resources.mon_interval_seconds
import app.skerry.ui.generated.resources.mon_unit_percent
import app.skerry.ui.generated.resources.shell_tip_disconnect
import app.skerry.ui.generated.resources.term_monitor_unavailable
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.SessionView
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sync.nowMillis
import app.skerry.ui.terminal.HostsSidebar
import app.skerry.ui.terminal.WorkBar
import app.skerry.ui.terminal.WorkBarLabel
import app.skerry.ui.terminal.WorkBarLeading
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

// The host monitor as a work-area view: opened from the session action row like SFTP is, filling
// the same area under the same bar, and left again through the bar's chevron.

/** Poll periods the bar's interval menu offers, in milliseconds. */
private val POLL_INTERVALS = listOf(2_000L, 3_000L, 5_000L, 10_000L, 30_000L)

/** Sparkline floor for the network tile (64 KiB/s) — an idle link must not draw its own noise full height. */
private const val NET_SCALE_FLOOR = 64L * 1024

/** Below this the work area shows one column of cards; above it, two. */
private val TWO_COLUMN_WIDTH = 820.dp

/** Above this the four resource tiles fit in one row; between the two, in two rows of two. */
private val FOUR_TILE_WIDTH = 860.dp
private val TWO_TILE_WIDTH = 300.dp

/**
 * The monitor of the session in focus: resource tiles, top processes, systemd units and mounts,
 * containers, and the alert feed. With no session backend at all (offscreen design render) the
 * same screen is drawn from a fixed snapshot; with a session that can't be polled — a colleague's
 * shared terminal, or nothing connected — it says so and offers the way back.
 */
@Composable
fun MonitorView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    val pane = sessions?.activeTerminal?.focusedPane
    val back: () -> Unit = { sessions?.setActiveView(SessionView.Terminal) }

    when {
        sessions == null -> MockMonitorScreen(state)
        pane != null && pane.controller.uiState is ConnectionUiState.Connected -> LiveMonitorScreen(
            state = state,
            controller = pane.controller,
            title = pane.title,
            hostId = pane.hostId,
            status = pane.status,
            tabId = sessions.activeTerminal?.id,
            onBack = back,
        )
        else -> EmptyMonitorScreen(state, back)
    }
}

@Composable
private fun LiveMonitorScreen(
    state: DesktopDesignState,
    controller: ConnectionController,
    title: String,
    hostId: String?,
    status: SessionStatus,
    tabId: String?,
    onBack: () -> Unit,
) {
    // Null for a pane watching a colleague's shared session: their host is not ours to poll. Keyed
    // on the epoch as well as the controller: a reconnect tears the poller down and builds a new
    // one, and this screen stays on the tab across that.
    val monitor = remember(controller, controller.metricsEpoch) { controller.openMetrics() }
    if (monitor == null) {
        EmptyMonitorScreen(state, onBack)
        return
    }
    val facts = connectionFacts(hostId, title, shortCipher(controller.cipher))
    MonitorScreen(
        state = state,
        // A lambda, not a value: the "refreshed N s ago" clock ticks once a second, and reading it
        // here would recompose every card on the screen along with the bar. Read inside the bar,
        // the tick reaches the title and nothing else.
        label = { WorkBarLabel.Solo(title, subtitleText(monitor.lastUpdateMillis, secondTick()), status) },
        metrics = monitor.metrics,
        history = monitor.history,
        netRxRate = monitor.netRxRate,
        netTxRate = monitor.netTxRate,
        availability = monitor.availability,
        alerts = monitor.alerts.entries,
        intervalMs = monitor.intervalMs,
        facts = facts,
        onBack = onBack,
        actions = {
            MonitorBarActions(
                intervalMs = monitor.intervalMs,
                onRefresh = monitor::refreshNow,
                onInterval = monitor::setInterval,
                onClose = state::requestCloseSession,
                tabId = tabId,
            )
        },
    )
}

/** The offscreen/design path: the same screen over the fixed preview snapshot, with inert actions. */
@Composable
private fun MockMonitorScreen(state: DesktopDesignState) {
    MonitorScreen(
        state = state,
        label = { WorkBarLabel.Solo("prod-web-01", stringResource(Res.string.mon_subtitle_seconds, 2), SessionStatus.Live) },
        metrics = PREVIEW_HOST_METRICS,
        history = PREVIEW_METRICS_HISTORY,
        netRxRate = PREVIEW_RX_RATE,
        netTxRate = PREVIEW_TX_RATE,
        availability = MetricsAvailability.Live,
        alerts = emptyList(),
        intervalMs = 5_000,
        // A fixed stamp keeps the offscreen render identical between runs.
        nowMillis = 0,
        facts = PREVIEW_CONNECTION_FACTS,
        onBack = {},
        actions = {},
    )
}

/** Nothing to poll: no session in this tab, or a pane that only watches someone else's. */
@Composable
private fun EmptyMonitorScreen(state: DesktopDesignState, onBack: () -> Unit) {
    MonitorFrame(state, label = { null }, onBack = onBack, actions = {}) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Txt(stringResource(Res.string.mon_no_session), color = Skerry.colors.faint, size = 12.5.sp)
        }
    }
}

/**
 * The chrome this screen shares with the terminal: the hosts catalog on the left and the work bar
 * over the content. The catalog belongs to the rail, not to what is on screen, so it stays where it
 * is while the monitor is up — walking the list here is how another host is reached without going
 * back first. The bar's chevron leaves for the terminal, the way the SFTP screen's does: this view
 * fills the work area, and the sidebar has the rail's own toggle.
 */
@Composable
private fun MonitorFrame(
    state: DesktopDesignState,
    label: @Composable () -> WorkBarLabel?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (!state.sidebarHidden) HostsSidebar(state, state.section)
        Column(Modifier.weight(1f).fillMaxHeight().background(Skerry.colors.bg)) {
            WorkBar(
                label = label(),
                tabKey = null,
                leading = WorkBarLeading.back(Res.string.mon_tip_back, onBack),
                onPickHost = null,
                actions = actions,
            )
            content()
        }
    }
}

/**
 * The screen itself, separated from where its numbers come from so the preview, the offscreen
 * render and a live session all draw the same layout.
 */
@Composable
private fun MonitorScreen(
    state: DesktopDesignState,
    label: @Composable () -> WorkBarLabel?,
    metrics: HostMetrics?,
    history: List<MetricsSample>,
    netRxRate: Long,
    netTxRate: Long,
    availability: MetricsAvailability,
    alerts: List<HostAlert>,
    intervalMs: Long,
    facts: ConnectionFacts,
    onBack: () -> Unit,
    nowMillis: Long? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val mono = LocalFonts.current.mono
    MonitorFrame(state, label, onBack, actions) {
        if (availability == MetricsAvailability.Unsupported) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt(stringResource(Res.string.term_monitor_unavailable), color = Skerry.colors.faint, size = 12.5.sp)
            }
            return@MonitorFrame
        }
        MonitorCardsBody(
            metrics = metrics,
            history = history,
            netRxRate = netRxRate,
            netTxRate = netTxRate,
            alerts = alerts,
            intervalMs = intervalMs,
            nowMillis = nowMillis,
            facts = facts,
            mono = mono,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Every card of the monitor in one scrolling column, laid out for whatever width it is given: the
 * desktop work area puts two cards per row, a phone sheet one. Shared so the two platforms show the
 * same monitor rather than two takes on it.
 */
@Composable
internal fun MonitorCardsBody(
    metrics: HostMetrics?,
    history: List<MetricsSample>,
    netRxRate: Long,
    netTxRate: Long,
    alerts: List<HostAlert>,
    intervalMs: Long,
    facts: ConnectionFacts?,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    // Fixed stamp for the offscreen renders, which must come out identical between runs; a live
    // screen passes null and the alerts card reads the clock itself, once a minute.
    nowMillis: Long? = null,
) {
    BoxWithConstraints(modifier) {
        val width = maxWidth
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatTiles(metrics, history, netRxRate, netTxRate, mono, width)
            val containers = metrics?.containers.orEmpty()
            CardGrid(
                width,
                listOf(
                    { modifier2 -> MonitorProcessCard(metrics?.processes.orEmpty(), mono, modifier2) },
                    { modifier2 ->
                        MonitorServicesCard(metrics?.services.orEmpty(), metrics?.disks.orEmpty(), mono, modifier2)
                    },
                ),
            )
            CardGrid(
                width,
                // A host with no containers (or no docker at all) doesn't get an empty card.
                listOfNotNull<@Composable (Modifier) -> Unit>(
                    if (containers.isEmpty()) null else ({ modifier2 -> MonitorContainersCard(containers, mono, modifier2) }),
                    { modifier2 -> MonitorAlertsCard(alerts, intervalMs, mono, modifier2, nowMillis) },
                ),
            )
            CardGrid(
                width,
                listOfNotNull<@Composable (Modifier) -> Unit>(
                    // The phone sheet is opened from a session whose facts its own chrome already
                    // names, so it passes none and gets no connection card.
                    facts?.let { { modifier2 -> MonitorConnectionCard(it, metrics?.uptimeSeconds, mono, modifier2) } },
                    { modifier2 -> MonitorSystemCard(metrics, mono, modifier2) },
                ),
            )
        }
    }
}

/** The four resource tiles, wrapping into two rows (or one column) as the work area narrows. */
@Composable
private fun StatTiles(
    metrics: HostMetrics?,
    history: List<MetricsSample>,
    netRxRate: Long,
    netTxRate: Long,
    mono: FontFamily,
    width: Dp,
) {
    val perRow = when {
        width >= FOUR_TILE_WIDTH -> 4
        width >= TWO_TILE_WIDTH -> 2
        else -> 1
    }
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { modifier -> CpuTile(metrics, history, mono, modifier) },
        { modifier -> MemoryTile(metrics, history, mono, modifier) },
        { modifier -> DiskTile(metrics, mono, modifier) },
        { modifier -> NetworkTile(metrics, history, netRxRate, netTxRate, mono, modifier) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tiles.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                // Keeps the last row's tiles the width of the ones above when it is short of a full row.
                repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * [cards] side by side while the work area is wide enough for two, stacked otherwise. Cards in a
 * row share their height ([IntrinsicSize.Min] plus `fillMaxHeight`), so the row reads as one strip
 * rather than boxes with mismatched bottoms.
 */
@Composable
private fun CardGrid(width: Dp, cards: List<@Composable (Modifier) -> Unit>) {
    if (width >= TWO_COLUMN_WIDTH) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            cards.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            cards.forEach { card -> card(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun CpuTile(metrics: HostMetrics?, history: List<MetricsSample>, mono: FontFamily, modifier: Modifier) {
    val cores = metrics?.cpuCount
    val load = metrics?.loadAverage
    MonitorStatCard(
        caption = stringResource(Res.string.mon_stat_cpu),
        value = metrics?.cpuPercent?.toString() ?: PENDING,
        unit = stringResource(Res.string.mon_unit_percent),
        history = history.map { it.cpuPercent / 100f },
        color = Skerry.colors.cyan,
        foot = if (cores != null && load != null) stringResource(Res.string.mon_foot_cpu, cores, load) else "",
        mono = mono,
        modifier = modifier,
    )
}

@Composable
private fun MemoryTile(metrics: HostMetrics?, history: List<MetricsSample>, mono: FontFamily, modifier: Modifier) {
    MonitorStatCard(
        caption = stringResource(Res.string.mon_stat_memory),
        value = metrics?.let { (it.memFraction * 100).toInt().toString() } ?: PENDING,
        unit = stringResource(Res.string.mon_unit_percent),
        history = history.map { it.memPercent / 100f },
        color = Skerry.colors.moss,
        foot = metrics?.let {
            stringResource(
                Res.string.mon_foot_memory,
                humanSize(it.memUsedBytes),
                humanSize(it.memTotalBytes),
                humanSize(it.swapUsedBytes),
            )
        } ?: "",
        mono = mono,
        modifier = modifier,
    )
}

@Composable
private fun DiskTile(metrics: HostMetrics?, mono: FontFamily, modifier: Modifier) {
    val root = metrics?.disks?.firstOrNull { it.mount == "/" }
    MonitorStatCard(
        caption = stringResource(Res.string.mon_stat_disk),
        // An unreadable df section leaves diskPercent at 0, and a big "0 %" headline reads as good
        // news rather than as missing data: only a filesystem we actually parsed gets a number.
        value = root?.percent?.toString() ?: PENDING,
        unit = stringResource(Res.string.mon_unit_percent),
        history = emptyList(),
        fill = root?.fraction ?: 0f,
        color = if ((root?.percent ?: 0) > ALERT_PERCENT) Skerry.colors.sunset else Skerry.colors.amber,
        foot = root?.let {
            stringResource(
                Res.string.mon_foot_disk,
                humanSize(it.usedBytes),
                humanSize(it.totalBytes),
                humanSize((it.totalBytes - it.usedBytes).coerceAtLeast(0)),
            )
        } ?: "",
        mono = mono,
        modifier = modifier,
    )
}

@Composable
private fun NetworkTile(
    metrics: HostMetrics?,
    history: List<MetricsSample>,
    netRxRate: Long,
    netTxRate: Long,
    mono: FontFamily,
    modifier: Modifier,
) {
    val peak = history.maxOfOrNull { max(it.rxBytesPerSec, it.txBytesPerSec) } ?: 0L
    val scale = max(peak, NET_SCALE_FLOOR).toFloat()
    // Rates are plain counters that start at zero, so before the first poll "0 B/s" would read as
    // a measured idle link — the tile waits for a snapshot like the other three.
    val total = if (metrics == null) emptyList() else humanRate(netRxRate + netTxRate).split(' ', limit = 2)
    MonitorStatCard(
        caption = stringResource(Res.string.mon_stat_network),
        value = total.firstOrNull() ?: PENDING,
        unit = total.getOrElse(1) { "" },
        history = history.map { (it.rxBytesPerSec + it.txBytesPerSec) / scale },
        color = Skerry.colors.teal,
        foot = stringResource(
            Res.string.mon_foot_network,
            metrics?.netInterface ?: PENDING,
            humanRate(netTxRate),
            humanRate(netRxRate),
        ),
        mono = mono,
        modifier = modifier,
    )
}

/** Refresh now, choose the poll period, close the session — the bar's right end on this screen. */
@Composable
private fun RowScope.MonitorBarActions(
    intervalMs: Long,
    onRefresh: () -> Unit,
    onInterval: (Long) -> Unit,
    onClose: (String) -> Unit,
    tabId: String?,
) {
    IconBtn("refresh", onClick = onRefresh, box = 26, tooltip = stringResource(Res.string.mon_tip_refresh))
    MonitorIntervalMenu(intervalMs, onInterval)
    if (tabId != null) {
        IconBtn(
            "power_settings_new",
            onClick = { onClose(tabId) },
            box = 26,
            tint = Skerry.colors.sunset,
            tooltip = stringResource(Res.string.shell_tip_disconnect),
        )
    }
}

/** Poll-period picker behind the bar's clock icon; the phone sheet raises the same menu. */
@Composable
internal fun MonitorIntervalMenu(intervalMs: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            IconBtn(
                "schedule",
                onClick = { open = !open },
                box = 26,
                tooltip = stringResource(Res.string.mon_tip_interval),
            )
        },
    ) { _ ->
        Column(
            Modifier
                .width(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Skerry.colors.surface2)
                .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(8.dp))
                .padding(vertical = 6.dp),
        ) {
            Txt(
                labelUppercase(stringResource(Res.string.mon_interval)),
                color = Skerry.colors.faint,
                size = 10.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            POLL_INTERVALS.forEach { ms ->
                val picked = ms == intervalMs
                Txt(
                    stringResource(Res.string.mon_interval_seconds, (ms / 1000).toInt()),
                    color = if (picked) Skerry.colors.cyanBright else Skerry.colors.dim,
                    size = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(ms)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** Wall clock re-read once a second, for the one label that counts in seconds. */
@Composable
private fun secondTick(): Long {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = nowMillis()
        }
    }
    return now
}

/** "monitoring · refreshed 5 s ago" — the age of the snapshot on screen, ticking once a second. */
@Composable
private fun subtitleText(lastUpdateMillis: Long?, nowMillis: Long): String {
    val elapsed = lastUpdateMillis?.let { (nowMillis - it).coerceAtLeast(0) }
        ?: return stringResource(Res.string.mon_subtitle_pending)
    val seconds = (elapsed / 1_000).toInt()
    return if (seconds < 60) {
        stringResource(Res.string.mon_subtitle_seconds, seconds)
    } else {
        stringResource(Res.string.mon_subtitle_minutes, seconds / 60)
    }
}

private const val PENDING = "…"
