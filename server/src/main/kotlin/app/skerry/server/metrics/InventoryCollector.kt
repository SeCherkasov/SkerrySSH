package app.skerry.server.metrics

import app.skerry.server.db.StatsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Refreshes the inventory gauges (accounts, records, ciphertext size, database file size) in the
 * background, so a scrape never triggers a table scan — see [StatsRepository.inventory] for why that
 * matters on a single-connection SQLite pool.
 *
 * On failure the gauges keep their previous values and the freshness timestamp is deliberately left
 * behind: the honest signal is `time() - skerry_inventory_last_success_time_seconds`, which an alert
 * can act on. Zeroing the gauges would look like data loss; refusing to say anything would hide it.
 */
class InventoryCollector(
    private val stats: StatsRepository,
    private val metrics: ServerMetrics,
    private val databaseUrl: String,
) {
    suspend fun collectOnce(now: () -> Long = System::currentTimeMillis) {
        try {
            val at = now()
            metrics.inventoryCollected(stats.inventory(databaseUrl, at), at)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            metrics.inventoryFailed()
            log.warn("metrics inventory collection failed", error)
        }
    }

    fun start(scope: CoroutineScope, intervalSeconds: Long): Job = scope.launch {
        while (true) {
            collectOnce()
            delay(intervalSeconds * 1_000)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(InventoryCollector::class.java)!!
    }
}
