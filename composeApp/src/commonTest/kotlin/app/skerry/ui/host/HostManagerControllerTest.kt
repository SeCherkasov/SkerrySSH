package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import app.skerry.shared.ssh.SshConfigHost
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
    fun `set vnc resize flag persists on the stored host`() {
        val store = FakeHostStore(Host("1", "a", "a.local", 5901, "u"))
        val controller = HostManagerController(store) { error("must not be called") }

        controller.setVncResizeToWindow("1", true)

        assertEquals(true, controller.find("1")?.vncResizeToWindow)
        assertEquals(true, store.all().single().vncResizeToWindow)
    }

    @Test
    fun `editing a host in the form preserves the vnc resize flag`() {
        // The flag is toggled from session chrome, not the edit form — a form save (draft has no
        // such field) must not silently reset it.
        val store = FakeHostStore(Host("1", "a", "a.local", 5901, "u", vncResizeToWindow = true))
        val controller = HostManagerController(store) { error("must not be called") }

        controller.save(HostDraft(id = "1", label = "renamed", address = "a.local", port = 5901, username = "u"))

        assertEquals(true, controller.find("1")?.vncResizeToWindow)
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
    fun `save carries the keep-alive interval through to the stored host`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen-id" }

        controller.save(
            HostDraft(label = "prod", address = "10.0.0.5", port = 22, username = "deploy", keepAliveSeconds = 0),
        )

        assertEquals(0, controller.hosts.single().keepAliveSeconds)
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
    fun `importHosts persists a batch and keeps existing hosts`() {
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u"))
        val controller = HostManagerController(store) { error("ids are pre-assigned") }

        controller.importHosts(
            listOf(
                Host("i1", "web", "10.0.0.1", 22, "deploy"),
                Host("i2", "db", "10.0.0.2", 22, "deploy", jumpHostId = "i1"),
            ),
        )

        assertEquals(listOf("1", "i1", "i2"), controller.hosts.map { it.id })
        assertEquals("i1", controller.find("i2")?.jumpHostId)
        assertEquals(controller.hosts, store.all())
    }

    @Test
    fun `importSshConfig plans and persists selected hosts with resolved jump`() {
        val store = FakeHostStore()
        var n = 0
        val controller = HostManagerController(store) { "gen-${++n}" }
        val parsed = listOf(
            SshConfigHost("web", "10.0.0.1", 22, user = null, proxyJump = "bastion", identityFile = null),
            SshConfigHost("bastion", "10.0.0.2", 22, user = "root", proxyJump = null, identityFile = null),
            SshConfigHost("skip", "10.0.0.3", 22, user = null, proxyJump = null, identityFile = null),
        )

        val count = controller.importSshConfig(parsed, selected = setOf("web", "bastion"), defaultUser = "me")

        assertEquals(2, count)
        assertEquals(setOf("web", "bastion"), controller.hosts.map { it.label }.toSet())
        val web = controller.hosts.single { it.label == "web" }
        val bastion = controller.hosts.single { it.label == "bastion" }
        assertEquals("me", web.username) // default user filled where config omitted User
        assertEquals("root", bastion.username)
        assertEquals(bastion.id, web.jumpHostId)
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
        // Unlock migration writes to HostStore directly (bypassing the controller); reload
        // syncs Compose state with the store so the UI sees redirected credentialIds.
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u"))
        val controller = HostManagerController(store) { "x" }
        store.put(Host("2", "b", "b.local", 22, "u")) // written bypassing the controller

        assertEquals(listOf("1"), controller.hosts.map { it.id }) // not visible yet
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

    @Test
    fun `renameGroup rewrites group on all member hosts`() {
        val store = FakeHostStore(
            Host("1", "a", "a.local", 22, "u", "Prod"),
            Host("2", "b", "b.local", 22, "u", "Dev"),
            Host("3", "c", "c.local", 22, "u", "Prod"),
        )
        val controller = HostManagerController(store) { "x" }

        controller.renameGroup("Prod", "Production")

        assertEquals(listOf("Production", "Production", "Dev"), controller.hosts.map { it.group })
    }

    @Test
    fun `deleteGroup ungroups its hosts but keeps the profiles`() {
        val store = FakeHostStore(
            Host("1", "a", "a.local", 22, "u", "Prod"),
            Host("2", "b", "b.local", 22, "u", "Dev"),
        )
        val controller = HostManagerController(store) { "x" }

        controller.deleteGroup("Prod")

        assertEquals(setOf("1", "2"), controller.hosts.map { it.id }.toSet())
        assertEquals(null, controller.find("1")?.group)
        assertEquals("Dev", controller.find("2")?.group)
    }
}

/** In-memory [HostStore] with upsert/remove-by-id semantics matching the file-backed implementation. */
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
