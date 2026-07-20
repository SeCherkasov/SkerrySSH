package app.skerry.ui.metrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Periodically polls host resources via [exec] (one exec channel per cycle) and publishes a fresh
 * [HostMetrics] to [metrics], a rolling [history] for the sparklines, and network rates derived
 * from the counter deltas. Polling runs on the session's [scope] (like
 * [app.skerry.ui.sftp.SftpController]) — survives tab/panel switches and stops with the session
 * ([stop] from [app.skerry.ui.connection.ConnectionController.disconnect]).
 *
 * A single poll failure (dropped channel) doesn't kill the loop or clear the last snapshot:
 * [metrics] simply doesn't update until the next successful cycle. A host that *can't* serve
 * metrics is a different case: no exec channel (telnet/serial) or output that never parses
 * (non-Linux) ends as [MetricsAvailability.Unsupported] and stops the loop, so the panel says so
 * instead of showing "…" forever over a stream of pointless round-trips.
 */
@Stable
class HostMetricsController(
    private val exec: suspend (String) -> ExecResult,
    private val scope: CoroutineScope,
    // Delay between polls AFTER the round-trip (excludes exec time, which includes a ~0.4s sleep
    // for the CPU sample) — the real period is approximately intervalMs + exec duration.
    private val intervalMs: Long = 3_000,
    // Wall clock for the network rates: rates divide the counter delta by the *actual* elapsed
    // time, which is intervalMs plus a variable round-trip. Injectable for tests.
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var metrics: HostMetrics? by mutableStateOf(null)
        private set

    /** Rolling window for the sparklines, oldest sample first, capped at [METRICS_HISTORY_SIZE]. */
    var history: List<MetricsSample> by mutableStateOf(emptyList())
        private set

    /** Network throughput of the host (all interfaces but loopback), bytes per second. */
    var netRxRate: Long by mutableStateOf(0)
        private set
    var netTxRate: Long by mutableStateOf(0)
        private set

    var availability: MetricsAvailability by mutableStateOf(MetricsAvailability.Probing)
        private set

    private var job: Job? = null
    private var lastMark: TimeMark? = null
    private var lastRx: Long? = null
    private var lastTx: Long? = null
    private var unparsablePolls = 0

    /** Starts periodic polling (idempotent: a repeat call does not start a second loop). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val result = runCatching { exec(METRICS_COMMAND) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        // A transport with no exec channel at all (telnet/serial/Mosh) can never
                        // answer — a verdict, not a hiccup.
                        if (it is UnsupportedOperationException) {
                            availability = MetricsAvailability.Unsupported
                            return@launch
                        }
                    }
                    .getOrNull()
                if (result != null) {
                    val parsed = parseHostMetrics(result.stdout)
                    if (parsed == null) {
                        // The host answers but the output isn't Linux /proc — retrying won't change
                        // that, so give up after a few attempts (the first may be truncated output).
                        if (++unparsablePolls >= UNPARSABLE_POLLS_BEFORE_VERDICT) {
                            availability = MetricsAvailability.Unsupported
                            return@launch
                        }
                    } else {
                        unparsablePolls = 0
                        publish(parsed)
                    }
                }
                delay(intervalMs)
            }
        }
    }

    /** Stops polling. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun publish(parsed: HostMetrics) {
        updateNetRates(parsed)
        metrics = parsed
        availability = MetricsAvailability.Live
        history = history.appendCapped(
            MetricsSample(
                cpuPercent = parsed.cpuPercent,
                memPercent = (parsed.memFraction * 100).toInt(),
                rxBytesPerSec = netRxRate,
                txBytesPerSec = netTxRate,
            ),
        )
    }

    /**
     * Rates from the counter delta over the time actually elapsed since the previous poll. The
     * first poll has nothing to compare against; a counter that went backwards means a reboot or
     * an interface reset, and reports zero rather than a nonsense spike.
     */
    private fun updateNetRates(parsed: HostMetrics) {
        val mark = timeSource.markNow()
        val elapsedMs = lastMark?.elapsedNow()?.inWholeMilliseconds ?: 0
        val rx = parsed.netRxBytes
        val tx = parsed.netTxBytes
        if (rx != null && tx != null) {
            val prevRx = lastRx
            val prevTx = lastTx
            if (prevRx != null && prevTx != null && elapsedMs > 0) {
                netRxRate = rate(rx - prevRx, elapsedMs)
                netTxRate = rate(tx - prevTx, elapsedMs)
            }
            lastRx = rx
            lastTx = tx
        }
        lastMark = mark
    }

    private fun rate(deltaBytes: Long, elapsedMs: Long): Long =
        if (deltaBytes < 0) 0 else deltaBytes * 1000 / elapsedMs

    companion object {
        /** Consecutive unparsable answers before a host is declared unable to serve metrics. */
        private const val UNPARSABLE_POLLS_BEFORE_VERDICT = 3

        /**
         * One command, one round-trip: two /proc/stat samples for CPU delta, then memory, disks,
         * network counters, the top processes by CPU, and host facts (uptime, load average, OS,
         * kernel, CPU count). Markers `@MEM`/`@DISK`/`@NET`/`@PROC`/`@UPTIME`/`@LOAD`/`@OS`/
         * `@KERNEL`/`@CPU` separate sections for [parseHostMetrics]. Everything is cheap (/proc
         * plus df/ps/uname), so it all rides the same cycle; the parser just re-reads the static
         * facts (OS/kernel/CPU). Assumes a POSIX shell (`;`-chained commands) and Linux (/proc,
         * free -b, df -Pk); on other systems the missing sections simply yield `null`/empty fields
         * (see [parseHostMetrics]), and output that never parses ends as
         * [MetricsAvailability.Unsupported].
         */
        const val METRICS_COMMAND: String =
            "grep '^cpu ' /proc/stat; sleep 0.4; grep '^cpu ' /proc/stat; " +
                "echo '@MEM'; free -b; echo '@DISK'; df -Pk; " +
                "echo '@NET'; cat /proc/net/dev; " +
                "echo '@PROC'; ps -eo pid=,pcpu=,pmem=,comm= --sort=-pcpu 2>/dev/null | head -5; " +
                "echo '@UPTIME'; cat /proc/uptime; echo '@LOAD'; cat /proc/loadavg; " +
                "echo '@OS'; grep '^PRETTY_NAME=' /etc/os-release 2>/dev/null; " +
                "echo '@KERNEL'; uname -s -r -m; echo '@CPU'; nproc"
    }
}
