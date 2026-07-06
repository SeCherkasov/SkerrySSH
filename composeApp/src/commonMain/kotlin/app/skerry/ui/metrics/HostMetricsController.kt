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

/**
 * Periodically polls host resources via [exec] (one exec channel per cycle) and publishes a fresh
 * [HostMetrics] to [metrics] for the info panel. Polling runs on the session's [scope] (like
 * [app.skerry.ui.sftp.SftpController]) — survives tab/panel switches and stops with the session
 * ([stop] from [app.skerry.ui.connection.ConnectionController.disconnect]).
 *
 * A single poll failure (dropped channel, non-Linux output) doesn't kill the loop or clear the
 * last snapshot: [metrics] simply doesn't update until the next successful cycle.
 */
@Stable
class HostMetricsController(
    private val exec: suspend (String) -> ExecResult,
    private val scope: CoroutineScope,
    // Delay between polls AFTER the round-trip (excludes exec time, which includes a ~0.4s sleep
    // for the CPU sample) — the real period is approximately intervalMs + exec duration.
    private val intervalMs: Long = 3_000,
) {
    var metrics: HostMetrics? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Starts periodic polling (idempotent: a repeat call does not start a second loop). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { exec(METRICS_COMMAND) }
                    .onFailure { if (it is CancellationException) throw it } // don't swallow cancellation
                    .getOrNull()
                    ?.let { parseHostMetrics(it.stdout) }
                    ?.let { metrics = it }
                delay(intervalMs)
            }
        }
    }

    /** Stops polling. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * One command, one round-trip: two /proc/stat samples for CPU delta, then memory, disk,
         * and host facts (uptime, load average, OS, kernel, CPU count). Markers
         * `@MEM`/`@DISK`/`@UPTIME`/`@LOAD`/`@OS`/`@KERNEL`/`@CPU` separate sections for
         * [parseHostMetrics]. Facts are cheap (all from /proc plus one file and uname/nproc), so
         * they're polled on the same cycle; the parser just re-reads the static ones (OS/kernel/CPU).
         * Assumes a POSIX shell (`;`-chained commands) and Linux (/proc, free -b, df -Pk); on other
         * systems, missing sections simply yield `null` fields (see [parseHostMetrics]).
         */
        const val METRICS_COMMAND: String =
            "grep '^cpu ' /proc/stat; sleep 0.4; grep '^cpu ' /proc/stat; " +
                "echo '@MEM'; free -b; echo '@DISK'; df -Pk /; " +
                "echo '@UPTIME'; cat /proc/uptime; echo '@LOAD'; cat /proc/loadavg; " +
                "echo '@OS'; grep '^PRETTY_NAME=' /etc/os-release 2>/dev/null; " +
                "echo '@KERNEL'; uname -s -r -m; echo '@CPU'; nproc"
    }
}
