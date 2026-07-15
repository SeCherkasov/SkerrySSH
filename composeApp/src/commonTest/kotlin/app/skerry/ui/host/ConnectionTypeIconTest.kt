package app.skerry.ui.host

import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protocol icon mapping shared by the connection form's protocol picker, the desktop sidebar rows
 * and the mobile host list.
 */
class ConnectionTypeIconTest {

    @Test
    fun every_connection_type_maps_to_its_symbol() {
        assertEquals("lan", ConnectionType.SSH.icon)
        assertEquals("bolt", ConnectionType.MOSH.icon)
        assertEquals("terminal", ConnectionType.TELNET.icon)
        assertEquals("cable", ConnectionType.SERIAL.icon)
        assertEquals("desktop_windows", ConnectionType.VNC.icon)
    }

    /** The icon is the row's only protocol marker, so a shared symbol would make two types alike. */
    @Test
    fun icons_are_distinct_across_types() {
        val icons = ConnectionType.entries.map { it.icon }
        assertEquals(icons.size, icons.toSet().size, "duplicate protocol icons: $icons")
        assertTrue(icons.none { it.isBlank() })
    }
}
