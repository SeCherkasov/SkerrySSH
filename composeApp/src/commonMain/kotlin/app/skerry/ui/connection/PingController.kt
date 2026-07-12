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
 * it in [rttMs] (ms) for the status bar. Each cycle IS the session's keep-alive (the measurement
 * sends `keepalive@openssh.com`), so [pollIntervalMillis] comes from the profile's keep-alive
 * cadence ([app.skerry.shared.host.Host.keepAliveSeconds]). Runs on the session's [scope] (like
 * [app.skerry.ui.metrics.HostMetricsController]): survives tab switches and stops together with
 * the session ([stop] from [ConnectionController.disconnect]).
 *
 * A failed measurement (dropped connection, timeout — [measure] returned `null` or threw) does
 * NOT reset the indicator: [rttMs] holds the last successful value until the next successful
 * cycle. The first measurement runs immediately.
 *
 * Dead-link detection (OpenSSH `ServerAliveCountMax` analog): after [deadAfterFailures]
 * CONSECUTIVE failed measurements [onDead] fires exactly once and polling ends — the handler is
 * expected to tear the session down. A success resets the streak. `onDead = null` (default)
 * disables detection: pure RTT/keep-alive polling.
 */
@Stable
class PingController(
    private val measure: suspend () -> Long?,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 5000,
    private val deadAfterFailures: Int = 3,
    private val onDead: (() -> Unit)? = null,
) {
    var rttMs: Long? by mutableStateOf(null)
        private set

    private var job: Job? = null

    // The loop runs on a multi-threaded [scope] while stop() comes from elsewhere, and Job.cancel()
    // does not wait: a measurement could land after stop() and leak into a restarted cycle. Storing
    // a value is therefore gated by a generation stamp under a lock (same as UpdateNoticeController).
    private val lock = Any()
    private var generation = 0

    /** Start periodic measurement (idempotent: a repeat call doesn't spawn a second cycle). */
    fun start() {
        synchronized(lock) {
            if (job != null) return
            val gen = generation
            job = scope.launch {
                var failureStreak = 0
                while (isActive) {
                    val measured = runCatching { measure() }
                        .onFailure { if (it is CancellationException) throw it } // don't swallow cancellation
                        .getOrNull()
                    if (measured != null) {
                        failureStreak = 0
                        // Discard a value from a cycle that was stopped while measure() was in flight.
                        synchronized(lock) { if (gen == generation) rttMs = measured }
                    } else if (onDead != null && ++failureStreak >= deadAfterFailures) {
                        // Link is dead. Same generation gate as the rtt store: a death observed by
                        // a cycle stopped mid-measure is discarded. The loop ends either way — the
                        // handler tears the session (and this controller) down.
                        if (synchronized(lock) { gen == generation }) onDead.invoke()
                        return@launch
                    }
                    delay(pollIntervalMillis)
                }
            }
        }
    }

    /** Stop measuring. */
    fun stop() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
        }
    }
}
