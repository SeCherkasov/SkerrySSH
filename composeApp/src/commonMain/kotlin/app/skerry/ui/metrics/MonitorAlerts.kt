package app.skerry.ui.metrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

// The monitor's alert feed. Everything here is derived from the snapshots the poller already
// fetches — no extra round-trip, no agent on the host, and no state that outlives the session.

/** What went wrong. The subject of the alert ([AlertCondition.subject]) says where or how much. */
enum class AlertKind { DiskFull, MemoryHigh, SwapHeavy, LoadHigh }

/**
 * One threshold that is currently crossed: [subject] is the mount for [AlertKind.DiskFull] and the
 * offending value for the rest, so two conditions of the same kind about different filesystems stay
 * apart.
 */
data class AlertCondition(val kind: AlertKind, val subject: String) {
    /**
     * What makes this the *same* alert across polls. Only a filesystem alert is scoped by its
     * subject — the mount is stable while the condition lasts. Everywhere else the subject is the
     * reading itself (91 % → 93 % → 95 %), which moves on nearly every poll while nothing about the
     * situation changed: keying on it would log a recovery and a fresh raise each time and bury the
     * card in churn.
     */
    val identity: Pair<AlertKind, String> get() = kind to if (kind == AlertKind.DiskFull) subject else ""
}

/** A raise or a recovery, stamped with the wall-clock time it happened at. */
data class HostAlert(val kind: AlertKind, val subject: String, val atMillis: Long, val active: Boolean)

/** Entries the feed keeps. Older ones fall off — the card is a recent history, not a journal. */
const val HOST_ALERT_LOG_SIZE = 6

/**
 * Fill above which a resource reads as a problem: the line the alert rules raise at and the one the
 * monitor's meters and tiles turn red at. One constant, so the colour and the alert can't drift.
 */
const val ALERT_PERCENT = 85

/** Memory this full means the host is about to start reclaiming or swapping. */
private const val MEMORY_ALERT_PERCENT = 90

/** Swap this deep in use is worth saying out loud; a few pages parked there are not. */
private const val SWAP_ALERT_PERCENT = 50

/**
 * How far a value has to fall back below its threshold before the alert clears. Without the band a
 * value resting on the line would raise and clear on alternating polls and fill the card with noise.
 */
private const val HYSTERESIS_PERCENT = 3

/**
 * Which thresholds [metrics] crosses. [active] is what is already raised: those conditions are held
 * until the value drops a further [HYSTERESIS_PERCENT] below the line, so a value hovering at the
 * threshold neither clears nor re-raises.
 */
fun alertConditions(metrics: HostMetrics, active: Set<AlertCondition> = emptySet()): List<AlertCondition> {
    val result = mutableListOf<AlertCondition>()

    metrics.disks.forEach { disk ->
        val condition = AlertCondition(AlertKind.DiskFull, disk.mount)
        if (crossed(disk.percent, ALERT_PERCENT, condition in active)) result += condition
    }

    val memPercent = (metrics.memFraction * 100).roundToInt()
    val memCondition = AlertCondition(AlertKind.MemoryHigh, memPercent.toString())
    if (crossed(memPercent, MEMORY_ALERT_PERCENT, active.any { it.kind == AlertKind.MemoryHigh })) {
        result += memCondition
    }

    if (metrics.swapTotalBytes > 0) {
        val swapPercent = (metrics.swapFraction * 100).roundToInt()
        val swapCondition = AlertCondition(AlertKind.SwapHeavy, swapPercent.toString())
        if (crossed(swapPercent, SWAP_ALERT_PERCENT, active.any { it.kind == AlertKind.SwapHeavy })) {
            result += swapCondition
        }
    }

    // Load is compared against the core count: "1.8" is idle on a 16-core box and a queue on a
    // single-core one. Without a core count there is nothing to compare it to, so no alert.
    val load1 = metrics.loadAverage?.split(' ')?.firstOrNull()
    val loadValue = load1?.toFloatOrNull()
    val cores = metrics.cpuCount
    if (loadValue != null && cores != null && cores > 0) {
        val condition = AlertCondition(AlertKind.LoadHigh, load1)
        val percentOfCores = (loadValue / cores * 100).roundToInt()
        if (crossed(percentOfCores, 100, active.any { it.kind == AlertKind.LoadHigh })) result += condition
    }

    return result
}

/** Whether [value] counts as over [threshold], allowing for the hysteresis band while [raised]. */
private fun crossed(value: Int, threshold: Int, raised: Boolean): Boolean =
    if (raised) value > threshold - HYSTERESIS_PERCENT else value > threshold

/**
 * The feed behind the monitor's Alerts card: fed a snapshot per poll, it records the moment a
 * threshold is crossed and the moment it is back under. Newest entry first, capped at
 * [HOST_ALERT_LOG_SIZE].
 *
 * Session-scoped like the metrics history — a reconnect starts an empty feed rather than replaying
 * what the previous connection saw.
 */
@Stable
class HostAlertLog {
    var entries: List<HostAlert> by mutableStateOf(emptyList())
        private set

    private var active: Set<AlertCondition> = emptySet()

    fun update(metrics: HostMetrics, nowMillis: Long) {
        val next = alertConditions(metrics, active).toSet()
        // Matched by [AlertCondition.identity], not by equality: a condition whose reading moved
        // (memory 91 % → 94 %) is the same alert, not a recovery followed by a new one.
        val activeIds = active.mapTo(mutableSetOf()) { it.identity }
        val nextIds = next.mapTo(mutableSetOf()) { it.identity }
        val raised = next.filter { it.identity !in activeIds }
        val cleared = active.filter { it.identity !in nextIds }
        if (raised.isNotEmpty() || cleared.isNotEmpty()) {
            val fresh = cleared.map { HostAlert(it.kind, it.subject, nowMillis, active = false) } +
                raised.map { HostAlert(it.kind, it.subject, nowMillis, active = true) }
            entries = (fresh.asReversed() + entries).take(HOST_ALERT_LOG_SIZE)
        }
        active = next
    }
}

/** How long ago an entry happened, in the granularity the card shows it at. */
sealed interface AlertAge {
    data object Now : AlertAge
    data class Minutes(val value: Int) : AlertAge
    data class Hours(val value: Int) : AlertAge
    data object Yesterday : AlertAge
    data class Days(val value: Int) : AlertAge
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * [elapsedMillis] as the coarsest unit that still says something useful. A negative value (the host
 * clock ran ahead of ours between polls) reads as "now" rather than a negative age.
 */
fun alertAge(elapsedMillis: Long): AlertAge = when {
    elapsedMillis < MINUTE_MS -> AlertAge.Now
    elapsedMillis < HOUR_MS -> AlertAge.Minutes((elapsedMillis / MINUTE_MS).toInt())
    elapsedMillis < DAY_MS -> AlertAge.Hours((elapsedMillis / HOUR_MS).toInt())
    elapsedMillis < 2 * DAY_MS -> AlertAge.Yesterday
    else -> AlertAge.Days((elapsedMillis / DAY_MS).toInt())
}
