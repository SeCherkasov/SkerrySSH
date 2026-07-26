package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionTypeTest {

    @Test
    fun `vnc is a remote desktop`() {
        assertTrue(ConnectionType.VNC.isRemoteDesktop)
    }

    @Test
    fun `terminal transports are not remote desktops`() {
        val terminal = listOf(
            ConnectionType.SSH,
            ConnectionType.MOSH,
            ConnectionType.TELNET,
            ConnectionType.SERIAL,
            ConnectionType.LOCAL,
            ConnectionType.CONTAINER,
        )
        terminal.forEach { assertFalse(it.isRemoteDesktop, "$it must not be a remote desktop") }
    }

    @Test
    fun `every transport belongs to exactly one section`() {
        // The two catalogs partition ConnectionType: a transport missing from both would be
        // invisible in the UI, one in both would show up twice.
        val sections = ConnectionType.entries.groupBy { it.isRemoteDesktop }
        assertEquals(ConnectionType.entries.size, sections.values.sumOf { it.size })
    }

    @Test
    fun `a remote desktop never authenticates over ssh`() {
        // Remote-desktop profiles have no username/key/jump host; the forms gate on this.
        ConnectionType.entries.filter { it.isRemoteDesktop }.forEach {
            assertFalse(it.usesSshAuth, "$it must not use SSH auth")
            assertFalse(it.carriedBySsh, "$it must not run over an SSH session")
        }
    }
}
