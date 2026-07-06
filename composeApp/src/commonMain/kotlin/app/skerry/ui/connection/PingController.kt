package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Periodically measures RTT to the server via [measure] (one round-trip per cycle) and publishes
 * it in [rttMs] (ms) for the status bar. Runs on the session's [scope] (like
 * [app.skerry.ui.metrics.HostMetricsController]): survives tab switches and stops together with
 * the session ([stop] from [ConnectionController.disconnect]).
 *
 * A failed measurement (dropped connection, timeout — [measure] returned `null` or threw) does
 * NOT reset the indicator: [rttMs] holds the last successful value until the next successful
 * cycle. The first measurement runs immediately.
 */
@Stable
class PingController(
    private val measure: suspend () -> Long?,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 5000,
) {
    var rttMs: Long? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Start periodic measurement (idempotent: a repeat call doesn't spawn a second cycle). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { measure() }
                    .onFailure { if (it is CancellationException) throw it } // don't swallow cancellation
                    .getOrNull()
                    ?.let { rttMs = it }
                delay(pollIntervalMillis)
            }
        }
    }

    /** Stop measuring. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
