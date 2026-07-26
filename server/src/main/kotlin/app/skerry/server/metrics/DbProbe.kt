package app.skerry.server.metrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * Background database probe behind `GET /readyz` and `skerry_db_up`.
 *
 * Two design constraints, both learned from what readiness probes usually get wrong:
 *
 * 1. **The probe never runs inside the request.** An orchestrator polling `/readyz` every couple of
 *    seconds would otherwise spend the single SQLite connection on probing and then report its own
 *    contention as a database failure. `/readyz` reads [ready], which this loop maintains.
 * 2. **Hysteresis.** One slow transaction must not take the instance out of rotation, so it takes
 *    [failureThreshold] consecutive failures to become not-ready and a single success to come back.
 */
class DbProbe(
    private val metrics: ServerMetrics,
    private val timeoutMillis: Long = 2_000,
    private val failureThreshold: Int = 3,
    private val check: suspend () -> Unit,
) {
    /** Starts ready: schema creation at startup already proved the database answers. */
    @Volatile
    var ready: Boolean = true
        private set

    /** Touched only from the probe loop (or a test calling [probeOnce] sequentially). */
    private var consecutiveFailures = 0

    suspend fun probeOnce(nanoTime: () -> Long = System::nanoTime) {
        val startedAt = nanoTime()
        val failure = try {
            withTimeout(timeoutMillis) { check() }
            null
        } catch (timeout: TimeoutCancellationException) {
            timeout
        } catch (cancelled: CancellationException) {
            throw cancelled // shutdown, not a database fault
        } catch (error: Exception) {
            error
        }
        metrics.dbProbe(up = failure == null, durationSeconds = (nanoTime() - startedAt) / 1_000_000_000.0)
        if (failure == null) {
            if (!ready) log.info("database probe recovered")
            consecutiveFailures = 0
            ready = true
        } else {
            consecutiveFailures++
            // Logged every time: unlike the periodic cleanup, a probe failure is exactly the kind of
            // thing an operator is looking for when the instance misbehaves.
            log.warn("database probe failed ($consecutiveFailures in a row): {}", failure.message ?: failure::class.simpleName)
            if (consecutiveFailures >= failureThreshold) ready = false
        }
    }

    fun start(scope: CoroutineScope, intervalMillis: Long = 10_000): Job = scope.launch {
        while (true) {
            probeOnce()
            delay(intervalMillis)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DbProbe::class.java)!!
    }
}
