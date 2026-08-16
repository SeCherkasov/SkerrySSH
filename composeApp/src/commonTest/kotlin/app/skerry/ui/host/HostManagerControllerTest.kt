package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.host.HostStore
import app.skerry.shared.rdp.RdpFileImport
import app.skerry.shared.rdp.RdpSpec
import app.skerry.shared.ssh.SshConfigHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun `a new remote-desktop profile starts following the window`() {
        // What every other client does (F-06): a fresh RDP/VNC profile resizes with the viewport.
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen-id" }

        controller.save(HostDraft(label = "win", address = "w", port = 3389, username = "u", connectionType = ConnectionType.RDP))

        assertTrue(store.all().single().vncResizeToWindow)
    }

    @Test
    fun `the live quality choice is remembered and survives a form re-save`() {
        // V-03: the graphics menu's quality pick used to die with the tab; now it lands on the
        // profile, and — like vncResizeToWindow — a form save must not reset it.
        val store = FakeHostStore(Host("1", "v", "v.local", 5900, "", connectionType = ConnectionType.VNC))
        val controller = HostManagerController(store) { error("must not be called") }

        controller.setVncQuality("1", app.skerry.shared.graphics.RemoteDesktopQuality.High)
        assertEquals(
            app.skerry.shared.graphics.RemoteDesktopQuality.High,
            store.all().single().vncQuality,
        )

        controller.save(HostDraft(id = "1", label = "v", address = "v.local", port = 5900, username = "", connectionType = ConnectionType.VNC))
        assertEquals(
            app.skerry.shared.graphics.RemoteDesktopQuality.High,
            store.all().single().vncQuality,
            "the user picked it live; a form save must not flip it back",
        )
    }

    @Test
    fun `an existing profile keeps its stored resize choice on re-save`() {
        val store = FakeHostStore(
            Host("1", "win", "w", 3389, "u", connectionType = ConnectionType.RDP, vncResizeToWindow = false),
        )
        val controller = HostManagerController(store) { error("must not be called") }

        controller.save(HostDraft(id = "1", label = "win", address = "w", port = 3389, username = "u", connectionType = ConnectionType.RDP))

        assertFalse(store.all().single().vncResizeToWindow, "the user turned it off; a form save must not flip it back")
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
    fun `save carries notes through to the stored host`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen-id" }

        controller.save(
            HostDraft(label = "prod", address = "10.0.0.5", port = 22, username = "deploy", notes = "ask ops before reboot"),
        )

        assertEquals("ask ops before reboot", controller.hosts.single().notes)
        assertEquals("ask ops before reboot", store.all().single().notes)
    }

    @Test
    fun `save without notes clears them on the stored host`() {
        // Emptying the field in the edit form must actually drop the note, not keep the old one.
        val store = FakeHostStore(Host("1", "a", "a.local", 22, "u", notes = "stale"))
        val controller = HostManagerController(store) { error("must not be called") }

        controller.save(HostDraft(id = "1", label = "a", address = "a.local", port = 22, username = "u"))

        assertNull(controller.find("1")?.notes)
    }

    @Test
    fun `duplicating a host carries its note onto the new record`() {
        // Duplicate goes form -> draft -> controller with a fresh id; the note must ride along.
        val original = Host("1", "prod-web", "10.0.0.5", 22, "deploy", notes = "ask ops before reboot")
        val store = FakeHostStore(original)
        val controller = HostManagerController(store) { "gen-id" }

        val copyId = controller.save(
            NewConnectionFormState.duplicateOf(original, "prod-web (copy)").toDraft(id = null),
        )

        assertEquals("gen-id", copyId)
        assertEquals("ask ops before reboot", controller.find("gen-id")?.notes)
        assertEquals("ask ops before reboot", controller.find("1")?.notes) // original untouched
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
    fun `an RDP profile stores the settings the draft carries, token included`() {
        // The form is what owns a profile's RDP settings: it was prefilled from the stored spec and
        // hands back the farm routing token nobody edits, alongside the audio choice somebody did.
        val store = FakeHostStore(
            Host(
                "1", "rds", "rds.example.com", 3389, "CORP\\alice",
                connectionType = ConnectionType.RDP,
                rdp = RdpSpec(loadBalanceInfo = "tsv://x"),
            ),
        )
        val controller = HostManagerController(store) { error("editing needs no new id") }

        controller.save(
            HostDraft(
                id = "1",
                label = "rds",
                address = "rds.example.com",
                port = 3389,
                username = "CORP\\bob",
                connectionType = ConnectionType.RDP,
                rdp = RdpSpec(loadBalanceInfo = "tsv://x", audioOutput = true),
            ),
        )

        assertEquals("tsv://x", controller.find("1")?.rdp?.loadBalanceInfo)
        assertTrue(controller.find("1")?.rdp?.audioOutput == true)
    }

    @Test
    fun `a profile that is not RDP keeps the spec it was imported with`() {
        // Only an RDP draft speaks for these fields; another type's save has no RDP form behind it,
        // so dropping the stored spec would lose settings the user never saw.
        val store = FakeHostStore(
            Host("1", "h", "h.example.com", 22, "root", rdp = RdpSpec(loadBalanceInfo = "tsv://x")),
        )
        val controller = HostManagerController(store) { error("editing needs no new id") }

        controller.save(HostDraft(id = "1", label = "h", address = "h.example.com", port = 22, username = "root"))

        assertEquals("tsv://x", controller.find("1")?.rdp?.loadBalanceInfo)
    }

    @Test
    fun `importRdpFile creates a profile from the file contents`() {
        val store = FakeHostStore()
        var n = 0
        val controller = HostManagerController(store) { "gen-${++n}" }

        val result = RdpFileImport.read(
            "full address:s:rds.example.com\nusername:s:alice\ndomain:s:CORP\nloadbalanceinfo:s:tsv://x",
            fileName = "prod.rdp",
        )
        val id = controller.importRdpFile(checkNotNull(result.host))

        val host = checkNotNull(controller.find(id))
        assertEquals("prod", host.label)
        assertEquals("rds.example.com", host.address)
        assertEquals(3389, host.port)
        assertEquals("CORP\\alice", host.username)
        assertEquals(ConnectionType.RDP, host.connectionType)
        assertEquals("tsv://x", host.rdp?.loadBalanceInfo)
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

    /**
     * The vault starts locked, so the list the controller is built with is empty and every real host
     * arrives later — through `reload()` after unlock, and again after each sync merge. Canonical on
     * read has to hold there too, or a tag written by an older client files the host under a chip
     * that draws like another one's while the canonical chip stops listing it.
     */
    @Test
    fun `tags are canonical however the list was read`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen" }
        store.put(Host("1", "web", "a.local", 22, "u", tags = listOf("pro\u200Bd", "#DB")))

        controller.reload()

        assertEquals(listOf(listOf("prod", "db")), controller.hosts.map { it.tags })
    }

    /** An imported profile is written the same way: an `ssh_config` tag is whatever the file said. */
    @Test
    fun `an imported host is stored with canonical tags`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen" }

        controller.importHosts(listOf(Host("1", "web", "a.local", 22, "u", tags = listOf("#Pro\u200Bd"))))

        assertEquals(listOf("prod"), controller.hosts.single().tags)
        assertEquals(listOf("prod"), store.all().single().tags)
    }

    /** And on the write side, the way a snippet's are: what is stored is what is compared. */
    @Test
    fun `save stores the tags in the form every comparison uses`() {
        val store = FakeHostStore()
        val controller = HostManagerController(store) { "gen" }

        controller.save(HostDraft(label = "web", address = "a.local", port = 22, username = "u", tags = listOf("#Pro\u200Bd")))

        assertEquals(listOf("prod"), controller.hosts.single().tags)
        assertEquals(listOf("prod"), store.all().single().tags)
    }
}

/**
 * Reordering from a section sidebar: the user drags among the rows THAT SECTION shows, while the
 * catalog holds both kinds. The other section's profiles must keep their relative order.
 */
class SectionReorderTest {

    private fun shell(id: String) = Host(id, id, "$id.local", 22, "root")
    private fun desktop(id: String) = Host(id, id, "$id.local", 5900, "", connectionType = ConnectionType.VNC)

    @Test
    fun `dragging a shell to the top of a mixed folder lands above the first shell`() {
        val store = FakeHostStore(shell("a"), desktop("x"), shell("b"))
        val controller = HostManagerController(store) { "gen" }

        // In the hosts sidebar the user sees [a, b] and drops b before a.
        controller.moveHostInSection("b", targetGroup = null, targetIndexInGroup = 0, section = HostSection.Terminal)

        assertEquals(listOf("b", "a", "x"), controller.hosts.map { it.id })
    }

    @Test
    fun `dragging past the last visible row does not jump over a hidden profile`() {
        // Catalog [a, x, b]: the desktops sidebar shows only x, the hosts one [a, b].
        val store = FakeHostStore(shell("a"), desktop("x"), shell("b"))
        val controller = HostManagerController(store) { "gen" }

        // Hosts sidebar: drop a after b (visible index 1 of [b]).
        controller.moveHostInSection("a", targetGroup = null, targetIndexInGroup = 1, section = HostSection.Terminal)

        // a sits after b, and the shells' order is what the user asked for.
        val ids = controller.hosts.map { it.id }
        assertTrue(ids.indexOf("b") < ids.indexOf("a"))
        assertTrue("x" in ids)
    }

    @Test
    fun `a section drag keeps the other section's relative order`() {
        val store = FakeHostStore(desktop("x"), shell("a"), desktop("y"), shell("b"))
        val controller = HostManagerController(store) { "gen" }

        controller.moveHostInSection("b", targetGroup = null, targetIndexInGroup = 0, section = HostSection.Terminal)

        val ids = controller.hosts.map { it.id }
        assertTrue(ids.indexOf("x") < ids.indexOf("y")) // desktops untouched relative to each other
        assertTrue(ids.indexOf("b") < ids.indexOf("a")) // the drag did what it said
    }

    @Test
    fun `moving into a folder that has nothing of this section appends to it`() {
        val store = FakeHostStore(shell("a"), Host("x", "x", "x.local", 5900, "", "Lab", connectionType = ConnectionType.VNC))
        val controller = HostManagerController(store) { "gen" }

        controller.moveHostInSection("a", targetGroup = "Lab", targetIndexInGroup = 0, section = HostSection.Terminal)

        assertEquals("Lab", controller.hosts.first { it.id == "a" }.group)
        assertEquals(listOf("x", "a"), controller.hosts.map { it.id })
    }

    @Test
    fun `folder reorder from a section counts only folders it shows`() {
        // Catalog folder order: Screens (desktops), Shells, Lab. The hosts sidebar shows [Shells, Lab].
        val store = FakeHostStore(
            desktop("x").copy(group = "Screens"),
            shell("a").copy(group = "Shells"),
            shell("b").copy(group = "Lab"),
        )
        val controller = HostManagerController(store) { "gen" }

        // Dropped after the last folder the sidebar shows (index 1 among [Shells]) — i.e. "stay last".
        controller.moveFolderInSection("Lab", targetGroupIndex = 1, section = HostSection.Terminal)

        // Counting the hidden Screens folder would have landed Lab between Screens and Shells.
        assertEquals(listOf("Screens", "Shells", "Lab"), controller.hosts.mapNotNull { it.group }.distinct())
    }
}
