package app.skerry.shared.rdp

import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RdpFileImportTest {

    private fun read(text: String, fileName: String = "prod.rdp") = RdpFileImport.read(text, fileName)

    @Test
    fun `a port outside the range is reported, not silently replaced`() {
        val result = RdpFileImport.read("full address:s:desk\r\nserver port:i:70000\r\n", "desk.rdp")

        assertEquals(RdpTarget.DEFAULT_PORT, result.host?.port)
        assertTrue(RdpImportWarning.PortOutOfRange in result.warnings)
    }

    @Test
    fun `maps the settings a profile needs`() {
        val result = read(
            """
            full address:s:rds.example.com
            server port:i:3390
            username:s:alice
            domain:s:CORP
            loadbalanceinfo:s:tsv://MS Terminal Services Plugin.1.Employees
            """.trimIndent(),
        )
        val entry = checkNotNull(result.host)
        assertEquals("prod", entry.label)
        assertEquals("rds.example.com", entry.address)
        assertEquals(3390, entry.port)
        assertEquals("CORP\\alice", entry.username)
        assertEquals("tsv://MS Terminal Services Plugin.1.Employees", entry.loadBalanceInfo)
    }

    @Test
    fun `a port in the address wins over the port setting`() {
        // mstsc writes both; the one glued to the address is the one it dials.
        val entry = checkNotNull(read("full address:s:host.example.com:3391\nserver port:i:3389").host)
        assertEquals("host.example.com", entry.address)
        assertEquals(3391, entry.port)
    }

    @Test
    fun `an IPv6 address keeps its brackets and its port`() {
        val entry = checkNotNull(read("full address:s:[2001:db8::1]:3391").host)
        assertEquals("2001:db8::1", entry.address)
        assertEquals(3391, entry.port)
    }

    @Test
    fun `a bare IPv6 address is not mistaken for a host and port`() {
        val entry = checkNotNull(read("full address:s:2001:db8::1").host)
        assertEquals("2001:db8::1", entry.address)
        assertEquals(RdpTarget.DEFAULT_PORT, entry.port)
    }

    @Test
    fun `an out-of-range port falls back to the default with a warning`() {
        val result = read("full address:s:host\nserver port:i:70000")
        assertEquals(RdpTarget.DEFAULT_PORT, checkNotNull(result.host).port)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `the alternate address is used when the main one is missing`() {
        val entry = checkNotNull(read("alternate full address:s:alt.example.com").host)
        assertEquals("alt.example.com", entry.address)
    }

    @Test
    fun `a file without any address imports nothing`() {
        val result = read("username:s:alice\nserver port:i:3389")
        assertNull(result.host)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `a username that already carries a domain is kept as it is`() {
        val entry = checkNotNull(read("full address:s:host\nusername:s:OTHER\\bob\ndomain:s:CORP").host)
        assertEquals("OTHER\\bob", entry.username)
    }

    @Test
    fun `an RD Gateway is reported rather than silently dropped`() {
        val result = read(
            """
            full address:s:host
            gatewayhostname:s:gw.example.com
            gatewayusagemethod:i:1
            """.trimIndent(),
        )
        assertTrue(RdpImportWarning.GatewayIgnored in result.warnings)
    }

    @Test
    fun `the label falls back to the address when the file name is empty`() {
        val entry = checkNotNull(read("full address:s:host.example.com", fileName = "").host)
        assertEquals("host.example.com", entry.label)
    }

    @Test
    fun `builds a saveable RDP profile`() {
        val entry = checkNotNull(read("full address:s:host\nusername:s:alice\nloadbalanceinfo:s:tsv://x").host)
        val host = RdpFileImport.toHost(entry, id = "id-1")
        assertEquals("id-1", host.id)
        assertEquals(ConnectionType.RDP, host.connectionType)
        assertEquals("host", host.address)
        assertEquals(RdpTarget.DEFAULT_PORT, host.port)
        assertEquals("alice", host.username)
        assertNull(host.credentialId)
        assertEquals("tsv://x", checkNotNull(host.rdp).loadBalanceInfo)
        // Parity with a form-created profile (F-06): a new remote desktop follows the window.
        assertTrue(host.vncResizeToWindow)
    }

    @Test
    fun `a profile without a load balancer carries no RDP settings`() {
        val entry = checkNotNull(read("full address:s:host\nusername:s:alice").host)
        assertNull(RdpFileImport.toHost(entry, id = "id-1").rdp)
    }

    @Test
    fun `a file that plays sound on this computer imports audio redirection`() {
        val entry = checkNotNull(read("full address:s:host\naudiomode:i:0").host)
        assertTrue(entry.audioOutput)
        assertTrue(checkNotNull(RdpFileImport.toHost(entry, id = "id-1").rdp).audioOutput)
    }

    @Test
    fun `sound left on the server, muted, or unstated stays off`() {
        // A file with no audiomode is the ambiguous case: mstsc plays such a session locally, but a
        // profile importing itself into an audio channel nobody asked for is the wrong surprise.
        assertFalse(checkNotNull(read("full address:s:host\naudiomode:i:1").host).audioOutput)
        assertFalse(checkNotNull(read("full address:s:host\naudiomode:i:2").host).audioOutput)
        assertFalse(checkNotNull(read("full address:s:host").host).audioOutput)
    }
}
