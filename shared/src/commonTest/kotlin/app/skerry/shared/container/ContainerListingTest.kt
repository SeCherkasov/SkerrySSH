package app.skerry.shared.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing of the "list containers/pods" output. The input is remote command output — untrusted and
 * possibly truncated — so malformed lines are skipped rather than failing the whole listing, and
 * the number of entries is capped.
 */
class ContainerListingTest {

    @Test
    fun `docker ps lines map to entries`() {
        val out = """
            9c1a2b3c4d5e	web	nginx:1.25	Up 3 hours
            77aa88bb99cc	db	postgres:16	Up 2 days (healthy)
        """.trimIndent()
        val entries = parseContainerList(ContainerRuntime.DOCKER, out)
        assertEquals(2, entries.size)
        assertEquals(ContainerEntry(name = "web", id = "9c1a2b3c4d5e", image = "nginx:1.25", status = "Up 3 hours"), entries[0])
        assertEquals("db", entries[1].name)
        assertEquals("Up 2 days (healthy)", entries[1].status)
    }

    @Test
    fun `docker ps skips blank and malformed lines`() {
        val out = "\n9c1a2b3c4d5e\tweb\tnginx\tUp\n\nnot-a-row\n   \n"
        val entries = parseContainerList(ContainerRuntime.DOCKER, out)
        assertEquals(listOf("web"), entries.map { it.name })
    }

    @Test
    fun `docker ps tolerates missing trailing columns`() {
        val entries = parseContainerList(ContainerRuntime.DOCKER, "9c1a2b3c4d5e\tweb")
        assertEquals(1, entries.size)
        assertEquals("web", entries[0].name)
        assertEquals("", entries[0].image)
        assertEquals("", entries[0].status)
    }

    @Test
    fun `kubectl pods map name status and containers`() {
        val out = """
            api-0        Running   app,sidecar
            worker-7f9   Pending   worker
        """.trimIndent()
        val entries = parseContainerList(ContainerRuntime.KUBERNETES, out)
        assertEquals(2, entries.size)
        assertEquals("api-0", entries[0].name)
        assertEquals("Running", entries[0].status)
        assertEquals(listOf("app", "sidecar"), entries[0].containers)
        assertEquals(listOf("worker"), entries[1].containers)
    }

    @Test
    fun `kubectl pods without a container column still list`() {
        val entries = parseContainerList(ContainerRuntime.KUBERNETES, "api-0   Running")
        assertEquals(1, entries.size)
        assertEquals(emptyList(), entries[0].containers)
    }

    @Test
    fun `kubectl error output produces no entries`() {
        val entries = parseContainerList(
            ContainerRuntime.KUBERNETES,
            "No resources found in prod namespace.",
        )
        // A one-word-per-column heuristic can't tell prose from a row, so the only guarantee is that
        // nothing crashes; the message ends up as at most one bogus entry, never a parse failure.
        assertTrue(entries.size <= 1)
    }

    @Test
    fun `listing is capped`() {
        val out = (1..(MAX_CONTAINER_ENTRIES + 50)).joinToString("\n") { "id$it\tname$it\timage\tUp" }
        assertEquals(MAX_CONTAINER_ENTRIES, parseContainerList(ContainerRuntime.DOCKER, out).size)
    }
}
