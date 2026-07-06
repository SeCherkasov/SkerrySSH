package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Computes terminal channel throughput from the delta of accumulated byte counters per poll
 * period, publishing it as [upRate]/[downRate] (bytes/sec) for the status bar. Sources:
 * [sampleUp]/[sampleDown] over [app.skerry.shared.ssh.ShellChannel.bytesUp]/`bytesDown` counters.
 *
 * Runs on the session's [scope] (like [app.skerry.ui.metrics.HostMetricsController]): survives
 * tab switches and stops with the session ([stop] from
 * [app.skerry.ui.connection.ConnectionController.disconnect]). Rate formula matches
 * [app.skerry.ui.forward.PortForwardController]: `delta * 1000 / period`.
 */
@Stable
class ThroughputController(
    private val sampleUp: () -> Long,
    private val sampleDown: () -> Long,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 1000,
) {
    var upRate: Long by mutableStateOf(0)
        private set

    var downRate: Long by mutableStateOf(0)
        private set

    private var prevUp = 0L
    private var prevDown = 0L
    private var job: Job? = null

    /**
     * Starts periodic polling (idempotent). Baseline is captured immediately so bytes already
     * accumulated before start (shell banner, etc.) aren't counted as first-period throughput.
     */
    fun start() {
        if (job != null) return
        prevUp = sampleUp()
        prevDown = sampleDown()
        job = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                poll()
            }
        }
    }

    /** One measurement: rate = byte delta over the period, normalized to per-second; counter never decreases. */
    internal fun poll() {
        val up = sampleUp()
        val down = sampleDown()
        upRate = ((up - prevUp) * 1000 / pollIntervalMillis).coerceAtLeast(0)
        downRate = ((down - prevDown) * 1000 / pollIntervalMillis).coerceAtLeast(0)
        prevUp = up
        prevDown = down
    }

    /** Stops polling. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
