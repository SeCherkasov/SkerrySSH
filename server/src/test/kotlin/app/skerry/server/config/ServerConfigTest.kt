package app.skerry.server.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigTest {

    private fun corsHosts(value: String): List<CorsHost> =
        ServerConfig.fromEnv(mapOf("SKERRY_CORS_HOSTS" to value)).corsHosts

    @Test
    fun `plain host allows both schemes`() {
        assertEquals(
            listOf(CorsHost("cdn.example.com", listOf("http", "https"))),
            corsHosts("cdn.example.com"),
        )
    }

    @Test
    fun `https prefix is stripped and narrows to https only`() {
        // Users naturally paste full origins; Ktor's allowHost throws on "://" in the host.
        assertEquals(
            listOf(CorsHost("cdn.example.com", listOf("https"))),
            corsHosts("https://cdn.example.com"),
        )
    }

    @Test
    fun `http prefix narrows to http only`() {
        assertEquals(
            listOf(CorsHost("localhost:5173", listOf("http"))),
            corsHosts("http://localhost:5173"),
        )
    }

    @Test
    fun `scheme prefix is case-insensitive`() {
        assertEquals(
            listOf(CorsHost("cdn.example.com", listOf("https"))),
            corsHosts("HTTPS://cdn.example.com"),
        )
    }

    @Test
    fun `trailing slash and path are dropped`() {
        assertEquals(
            listOf(CorsHost("cdn.example.com", listOf("https"))),
            corsHosts("https://cdn.example.com/some/path/"),
        )
    }

    @Test
    fun `list splits on commas and trims whitespace`() {
        assertEquals(
            listOf(
                CorsHost("a.example.com", listOf("https")),
                CorsHost("b.example.com", listOf("http", "https")),
            ),
            corsHosts(" https://a.example.com , b.example.com "),
        )
    }

    @Test
    fun `blank and scheme-only entries are dropped`() {
        assertEquals(emptyList(), corsHosts(" , https:// ,, http://"))
    }

    @Test
    fun `empty variable disables CORS`() {
        assertEquals(emptyList(), corsHosts(""))
    }

    // Ktor's allowHost special-cases "*" into anyHost(), so the literal must survive parsing.
    @Test
    fun `wildcard passes through`() {
        assertEquals(listOf(CorsHost("*", listOf("http", "https"))), corsHosts("*"))
    }

    // --- metrics exposure ---

    private fun metrics(env: Map<String, String>) = ServerConfig.fromEnv(env).metrics

    @Test
    fun `metrics are off unless enabled`() {
        assertEquals(MetricsExposure.OFF, metrics(emptyMap()))
        assertEquals(MetricsExposure.OFF, metrics(mapOf("SKERRY_METRICS" to "")))
        // An unrecognized value must not open the endpoint: unknown means off, as with registration.
        assertEquals(MetricsExposure.OFF, metrics(mapOf("SKERRY_METRICS" to "yes please")))
    }

    @Test
    fun `metrics modes are case-insensitive`() {
        assertEquals(MetricsExposure.TOKEN, metrics(mapOf("SKERRY_METRICS" to "Token")))
        assertEquals(MetricsExposure.OPEN, metrics(mapOf("SKERRY_METRICS" to "OPEN")))
        assertEquals(MetricsExposure.OFF, metrics(mapOf("SKERRY_METRICS" to "off")))
    }

    @Test
    fun `metrics token is read separately from the admin token`() {
        val config = ServerConfig.fromEnv(
            mapOf("SKERRY_METRICS" to "token", "SKERRY_METRICS_TOKEN" to "scrape-me", "SKERRY_ADMIN_TOKEN" to "admin"),
        )
        assertEquals("scrape-me", config.metricsToken)
        assertEquals("admin", config.adminToken)
    }

    @Test
    fun `inventory interval has a floor and can be disabled`() {
        assertEquals(60L, ServerConfig.fromEnv(emptyMap()).metricsInventoryIntervalSeconds)
        assertEquals(0L, ServerConfig.fromEnv(mapOf("SKERRY_METRICS_INVENTORY_SECONDS" to "0")).metricsInventoryIntervalSeconds)
        // Below the floor the collector would scan `records` more often than it is worth on SQLite.
        assertEquals(15L, ServerConfig.fromEnv(mapOf("SKERRY_METRICS_INVENTORY_SECONDS" to "3")).metricsInventoryIntervalSeconds)
        assertEquals(300L, ServerConfig.fromEnv(mapOf("SKERRY_METRICS_INVENTORY_SECONDS" to "300")).metricsInventoryIntervalSeconds)
    }
}
