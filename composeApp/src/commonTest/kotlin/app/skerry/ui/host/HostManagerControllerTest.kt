package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostManagerControllerTest {

    @Test
    fun `exposes hosts already in the store`() {
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u"))
        val controller = HostManagerController(store) { "generated" }

        assertEquals(listOf("a"), controller.hosts.map { it.label })
    }

    @Test
    fun `save without id creates a host with a generated id`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen-id" }

        val id = controller.save(HostDraft(label = "prod", address = "10.0.0.5", port = 22, username = "deploy"))

        assertEquals("gen-id", id)
        assertEquals(
            listOf(Host("gen-id", "prod", "10.0.0.5", 22, "deploy")),
            controller.hosts,
        )
        assertEquals(controller.hosts, store.all())
    }

    @Test
    fun `save returns the existing id when updating`() {
        val store = FakeHostStore(Host("1", "old", "a.local", 22, "u"))
        val controller = HostManagerController(store) { error("must not be called") }

        val id = controller.save(HostDraft(id = "1", label = "new", address = "a.local", port = 22, username = "u"))

        assertEquals("1", id)
    }

    @Test
    fun `save with an existing id updates in place without generating an id`() {
        val store = FakeHostStore(Host("1", "old", "a.local", 22, "u"))
        val controller = HostManagerController(store) { error("must not be called") }

        controller.save(
            HostDraft(id = "1", label = "new", address = "b.local", port = 2022, username = "admin", group = "Prod"),
        )

        assertEquals(
            listOf(Host("1", "new", "b.local", 2022, "admin", "Prod")),
            controller.hosts,
        )
    }

    @Test
    fun `save carries the credential reference through to the stored host`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen-id" }

        controller.save(
            HostDraft(label = "prod", address = "10.0.0.5", port = 22, username = "deploy", credentialId = "key-1"),
        )

        assertEquals("key-1", controller.hosts.single().credentialId)
    }

    @Test
    fun `save carries tags through to the stored host`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen-id" }

        controller.save(
            HostDraft(label = "prod", address = "10.0.0.5", port = 22, username = "deploy", tags = listOf("prod", "db")),
        )

        assertEquals(listOf("prod", "db"), controller.hosts.single().tags)
    }

    @Test
    fun `delete removes the host`() {
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u"), Host("2", "b", "b.local", 22, "u"))
        val controller = HostManagerController(store) { "x" }

        controller.delete("1")

        assertEquals(listOf("2"), controller.hosts.map { it.id })
    }

    @Test
    fun `reload pulls hosts written to the store behind the controller`() {
        // Миграция при unlock пишет в HostStore напрямую (мимо контроллера); reload синхронизирует
        // Compose-state со стором, чтобы UI увидел перенаправленные credentialId.
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u"))
        val controller = HostManagerController(store) { "x" }
        store.put(Host("2", "b", "b.local", 22, "u")) // запись в обход контроллера

        assertEquals(listOf("1"), controller.hosts.map { it.id }) // ещё не видит
        controller.reload()

        assertEquals(listOf("1", "2"), controller.hosts.map { it.id })
    }

    @Test
    fun `moveHost reorders within a folder and persists to the store`() {
        val store = FakeHostStore(
            Host("1", "a", "a.local", 22, "u", "Prod"),
            Host("2", "b", "b.local", 22, "u", "Prod"),
        )
        val controller = HostManagerController(store) { "x" }

        controller.moveHost("2", targetGroup = "Prod", targetIndexInGroup = 0)

        assertEquals(listOf("2", "1"), controller.hosts.map { it.id })
        assertEquals(controller.hosts, store.all())
    }

    @Test
    fun `moveHost into another folder rewrites the group`() {
        val store = FakeHostStore(
            Host("1", "a", "a.local", 22, "u", "Prod"),
            Host("2", "x", "x.local", 22, "u", "Lab"),
        )
        val controller = HostManagerController(store) { "x" }

        controller.moveHost("1", targetGroup = "Lab", targetIndexInGroup = 1)

        assertEquals(listOf("2", "1"), controller.hosts.map { it.id })
        assertEquals("Lab", controller.find("1")?.group)
    }

    @Test
    fun `moveFolder reorders whole folder blocks`() {
        val store = FakeHostStore(
            Host("1", "a", "a.local", 22, "u", "Prod"),
            Host("2", "x", "x.local", 22, "u", "Lab"),
        )
        val controller = HostManagerController(store) { "x" }

        controller.moveFolder("Lab", targetGroupIndex = 0)

        assertEquals(listOf("2", "1"), controller.hosts.map { it.id })
    }

    @Test
    fun `find returns a host by id or null`() {
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u"))
        val controller = HostManagerController(store) { "x" }

        assertEquals("a", controller.find("1")?.label)
        assertNull(controller.find("missing"))
    }
}

/** In-memory [HostStore] с семантикой upsert/remove по id, как у файловой реализации. */
private class FakeHostStore(vararg initial: Host) : HostStore {
    private val entries = initial.toMutableList()

    override fun all(): List<Host> = entries.toList()

    override fun put(host: Host) {
        val index = entries.indexOfFirst { it.id == host.id }
        if (index >= 0) entries[index] = host else entries += host
    }

    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }

    override fun reorder(transform: (List<Host>) -> List<Host>) {
        val updated = transform(entries.toList())
        entries.clear()
        entries += updated
    }
}
