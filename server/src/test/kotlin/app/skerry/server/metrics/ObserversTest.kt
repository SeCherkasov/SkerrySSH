package app.skerry.server.metrics

import app.skerry.server.config.ServerConfig
import app.skerry.server.db.Db
import app.skerry.server.db.StatsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The readiness probe and the inventory collector: hysteresis, and honesty about stale data. */
class ObserversTest {

    private val opened = mutableListOf<ServerMetrics>()

    /** Same reason as in the route tests: the JVM binders hold a GC listener until closed. */
    @AfterTest
    fun closeRegistries() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun metrics(exposure: String = "open"): ServerMetrics =
        ServerMetrics(ServerConfig.fromEnv(mapOf("SKERRY_METRICS" to exposure))).also { opened += it }

    @Test
    fun `a single failure does not take the instance out of rotation`() = runTest {
        val probe = DbProbe(metrics(), failureThreshold = 3) { error("locked") }
        probe.probeOnce()
        assertTrue(probe.ready, "one failure must not flip readiness — a slow transaction is not an outage")
        probe.probeOnce()
        assertTrue(probe.ready)
        probe.probeOnce()
        assertFalse(probe.ready, "three consecutive failures should")
    }

    @Test
    fun `one success restores readiness and the counter starts over`() = runTest {
        var failing = true
        val probe = DbProbe(metrics(), failureThreshold = 3) { if (failing) error("locked") }
        repeat(3) { probe.probeOnce() }
        assertFalse(probe.ready)

        failing = false
        probe.probeOnce()
        assertTrue(probe.ready)

        // The failure count reset, so two fresh failures are still not enough.
        failing = true
        probe.probeOnce()
        probe.probeOnce()
        assertTrue(probe.ready)
    }

    @Test
    fun `a probe that hangs counts as a failure rather than blocking`() = runTest {
        val probe = DbProbe(metrics(), timeoutMillis = 50, failureThreshold = 1) { delay(10_000) }
        probe.probeOnce()
        assertFalse(probe.ready)
    }

    @Test
    fun `probe results reach the exposition`() = runTest {
        val metrics = metrics()
        DbProbe(metrics, failureThreshold = 1) { }.probeOnce()
        assertTrue(metrics.scrape().lines().any { it == "skerry_db_up 1.0" }, metrics.scrape())

        DbProbe(metrics, failureThreshold = 1) { error("down") }.probeOnce()
        assertTrue(metrics.scrape().lines().any { it == "skerry_db_up 0.0" })
    }

    /**
     * A failed collection must not update the freshness timestamp: alerting on
     * `time() - skerry_inventory_last_success_time_seconds` is the only thing that keeps a stale
     * gauge from reading as current.
     */
    @Test
    fun `a failed collection is visible and does not refresh the timestamp`() = runTest {
        val metrics = metrics()
        val file = Files.createTempFile("skerry-observers-", ".db")
        file.toFile().deleteOnExit()
        val config = ServerConfig.fromEnv(mapOf("SKERRY_DB_URL" to "jdbc:sqlite:${file.toAbsolutePath()}"))
        val database = Db.connect(config)
        val collector = InventoryCollector(StatsRepository(database), metrics, config.databaseUrl)
        collector.collectOnce { 1_700_000_000_000 }
        val afterSuccess = metrics.gaugeValue("skerry_inventory_last_success_time_seconds")
        assertEquals(1_700_000_000.0, afterSuccess)
        assertEquals(0.0, metrics.gaugeValue("skerry_accounts"))

        // Break a table the inventory query needs, so the failure happens where it would in
        // production — inside the collection, not while opening the pool.
        transaction(database) { exec("DROP TABLE records") }
        collector.collectOnce { 1_900_000_000_000 }

        assertEquals(afterSuccess, metrics.gaugeValue("skerry_inventory_last_success_time_seconds"))
        assertTrue(
            metrics.scrape().lines().any { it.startsWith("skerry_inventory_errors_total") && !it.endsWith(" 0.0") },
            metrics.scrape().lines().filter { "inventory" in it }.toString(),
        )
    }

    private fun ServerMetrics.gaugeValue(name: String): Double =
        scrape().lines().first { it.startsWith("$name ") }.substringAfterLast(' ').toDouble()
}
