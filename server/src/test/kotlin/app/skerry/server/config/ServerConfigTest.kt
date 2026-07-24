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
}
