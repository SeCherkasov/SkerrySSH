package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun host(
    id: String,
    label: String = id,
    group: String? = null,
    type: ConnectionType = ConnectionType.SSH,
) = Host(
    id = id, label = label, address = "$id.example.com", username = "root", group = group,
    connectionType = type,
)

class HostGroupingTest {

    @Test
    fun empty_input_yields_no_folders() {
        assertEquals(emptyList(), groupHostsByFolder(emptyList()))
    }

    @Test
    fun groups_by_folder_preserving_first_seen_order() {
        val hosts = listOf(
            host("a", group = "Prod"),
            host("b", group = "Lab"),
            host("c", group = "Prod"),
        )
        val folders = groupHostsByFolder(hosts)
        assertEquals(listOf("Prod", "Lab"), folders.map { it.name })
        assertEquals(listOf("a", "c"), folders[0].hosts.map { it.id })
        assertEquals(listOf("b"), folders[1].hosts.map { it.id })
    }

    @Test
    fun null_group_falls_into_ungrouped_bucket_in_order() {
        val hosts = listOf(
            host("loose", group = null),
            host("p", group = "Prod"),
        )
        val folders = groupHostsByFolder(hosts, ungroupedLabel = "Ungrouped")
        assertEquals(listOf("Ungrouped", "Prod"), folders.map { it.name })
        assertEquals(listOf("loose"), folders[0].hosts.map { it.id })
    }
}

class HostSectionTest {

    private val catalog = listOf(
        host("shell", type = ConnectionType.SSH),
        host("screen", type = ConnectionType.VNC),
        host("router", type = ConnectionType.TELNET),
        host("box", type = ConnectionType.CONTAINER),
    )

    @Test
    fun terminal_section_excludes_remote_desktops() {
        assertEquals(listOf("shell", "router", "box"), catalog.inSection(HostSection.Terminal).map { it.id })
    }

    @Test
    fun remote_desktop_section_holds_only_remote_desktops() {
        assertEquals(listOf("screen"), catalog.inSection(HostSection.RemoteDesktops).map { it.id })
    }

    @Test
    fun the_two_sections_partition_the_catalog() {
        // Nothing hidden, nothing listed twice: a profile the user saved must be reachable in
        // exactly one section.
        val terminal = catalog.inSection(HostSection.Terminal).map { it.id }
        val desktops = catalog.inSection(HostSection.RemoteDesktops).map { it.id }
        assertEquals(catalog.size, terminal.size + desktops.size)
        assertTrue((terminal.toSet() intersect desktops.toSet()).isEmpty())
    }

    @Test
    fun the_creation_form_offers_only_its_section_protocols() {
        assertEquals(listOf(ConnectionType.VNC), connectionTypesIn(HostSection.RemoteDesktops))
        assertEquals(
            listOf(
                ConnectionType.SSH,
                ConnectionType.MOSH,
                ConnectionType.TELNET,
                ConnectionType.SERIAL,
                ConnectionType.CONTAINER,
            ),
            connectionTypesIn(HostSection.Terminal),
        )
    }

    @Test
    fun the_local_shell_is_not_offered_in_either_form() {
        // Not a user-created profile: it is launched from the empty tab and configured in Settings.
        assertTrue(HostSection.entries.none { ConnectionType.LOCAL in connectionTypesIn(it) })
    }

    @Test
    fun section_of_a_host_follows_its_transport() {
        assertEquals(HostSection.Terminal, host("a", type = ConnectionType.SSH).section)
        assertEquals(HostSection.RemoteDesktops, host("b", type = ConnectionType.VNC).section)
    }

    @Test
    fun filtering_preserves_catalog_order() {
        val ordered = listOf(
            host("v1", type = ConnectionType.VNC),
            host("s1", type = ConnectionType.SSH),
            host("v2", type = ConnectionType.VNC),
        )
        assertEquals(listOf("v1", "v2"), ordered.inSection(HostSection.RemoteDesktops).map { it.id })
    }
}

class HostRowSubtitleTest {

    @Test
    fun a_profile_with_a_user_shows_user_at_host() {
        val h = host("a", type = ConnectionType.SSH).copy(username = "root", address = "10.0.0.1")
        assertEquals("root@10.0.0.1", h.rowSubtitle())
    }

    @Test
    fun a_remote_desktop_shows_host_and_port_instead_of_a_dangling_at() {
        // VNC authenticates with a password only — no username, so "@10.0.0.5" would be all it had.
        val h = host("b", type = ConnectionType.VNC).copy(username = "", address = "10.0.0.5", port = 5901)
        assertEquals("10.0.0.5:5901", h.rowSubtitle())
    }

    @Test
    fun a_local_shell_shows_its_shell_path_or_a_name() {
        val shell = host("c", type = ConnectionType.LOCAL).copy(username = "", address = "/bin/zsh", port = 0)
        assertEquals("/bin/zsh", shell.rowSubtitle())
        val default = host("d", type = ConnectionType.LOCAL).copy(username = "", address = "", port = 0)
        assertEquals("", default.rowSubtitle())
    }
}
