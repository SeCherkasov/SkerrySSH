package app.skerry.ui.tunnel

import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunnelPresentationTest {

    private fun entry(
        id: String,
        status: TunnelStatus,
        hostId: String = "h1",
        autostart: Boolean = false,
    ): TunnelEntry {
        val tunnel = Tunnel(id, id, hostId, TunnelDirection.Local, "127.0.0.1", 8080, "10.0.0.5", 80, autostart)
        return TunnelEntry(tunnel).also { it.status = status }
    }

    @Test
    fun `the header counts what is up and what is not`() {
        val counts = tunnelCounts(
            listOf(
                entry("a", TunnelStatus.Active(1)),
                entry("b", TunnelStatus.Active(2)),
                entry("c", TunnelStatus.Inactive),
                entry("d", TunnelStatus.Failed("boom")),
                entry("e", TunnelStatus.Connecting),
            ),
        )

        // Anything not carrying traffic is "stopped" — a failed or still-dialling tunnel is not
        // active, and the header must not claim otherwise.
        assertEquals(TunnelCounts(active = 2, stopped = 3), counts)
    }

    @Test
    fun `each status maps to its own row badge`() {
        assertEquals(TunnelStatusBadge.Active, TunnelStatus.Active(1).badge())
        assertEquals(TunnelStatusBadge.Connecting, TunnelStatus.Connecting.badge())
        assertEquals(TunnelStatusBadge.Failed, TunnelStatus.Failed("boom").badge())
        assertEquals(TunnelStatusBadge.Stopped, TunnelStatus.Inactive.badge())
    }

    @Test
    fun `the listen and target columns swap arrow direction for a remote forward`() {
        // -L/-D listen locally and reach out; -R listens on the server and traffic comes back at us.
        // The mock-up draws that as the arrow between the two address columns.
        assertEquals(TunnelFlow.Outbound, TunnelDirection.Local.flow())
        assertEquals(TunnelFlow.Outbound, TunnelDirection.Dynamic.flow())
        assertEquals(TunnelFlow.Inbound, TunnelDirection.Remote.flow())
    }

    @Test
    fun `autostart tunnels are grouped by host, hosts without one are absent`() {
        val grouped = autostartByHost(
            listOf(
                entry("a", TunnelStatus.Active(1), hostId = "db", autostart = true),
                entry("b", TunnelStatus.Inactive, hostId = "db", autostart = true),
                entry("c", TunnelStatus.Inactive, hostId = "edge", autostart = true),
                entry("d", TunnelStatus.Inactive, hostId = "quiet", autostart = false),
            ),
        )

        assertEquals(listOf("db" to 2, "edge" to 1), grouped)
    }

    @Test
    fun `a bind address is only quiet when it is loopback`() {
        // The warning drives whether the editor tells the user the listener is reachable from the
        // network. Empty means the form will substitute 127.0.0.1, so it is quiet too.
        assertFalse(bindsBeyondLoopback(""))
        assertFalse(bindsBeyondLoopback("127.0.0.1"))
        assertFalse(bindsBeyondLoopback(" 127.1.2.3 "))
        assertFalse(bindsBeyondLoopback("localhost"))
        assertFalse(bindsBeyondLoopback("::1"))
        assertFalse(bindsBeyondLoopback("[::1]"))

        assertTrue(bindsBeyondLoopback("0.0.0.0"))
        assertTrue(bindsBeyondLoopback("::"))
        assertTrue(bindsBeyondLoopback("192.168.1.10"))
        // A name we cannot resolve is assumed to be reachable — the warning must not be the thing
        // that goes quiet when we are unsure.
        assertTrue(bindsBeyondLoopback("nas.lan"))
    }

    @Test
    fun `a hostname that merely starts like loopback is not loopback`() {
        // "127." was once a text prefix check, so any domain whose first label starts with those
        // four characters silenced the warning. A DNS label is free to begin that way and resolve
        // wherever its owner likes, which is exactly the case the warning exists for.
        assertTrue(bindsBeyondLoopback("127.0.0.1.attacker.example"))
        assertTrue(bindsBeyondLoopback("127.tunnel.example"))

        // Anything that isn't a clean dotted quad in 127/8 is treated as reachable, because we
        // cannot tell what it resolves to.
        assertTrue(bindsBeyondLoopback("127.0.0"))
        assertTrue(bindsBeyondLoopback("127.0.0.256"))
        assertTrue(bindsBeyondLoopback("127.0.0.1.1"))
        assertTrue(bindsBeyondLoopback("127.00.0.1")) // a leading zero reads as octal to some resolvers
        assertTrue(bindsBeyondLoopback("127.+0.0.1"))
        assertTrue(bindsBeyondLoopback("2130706433")) // decimal-encoded 127.0.0.1

        assertFalse(bindsBeyondLoopback("127.0.0.1"))
        assertFalse(bindsBeyondLoopback("127.255.255.254"))
    }

    @Test
    fun `a transport failure is classified without reading its message`() {
        // The card shows a one-word cause, and it must not come from sniffing an exception string
        // that is localized and rewritten for the user.
        assertEquals(TunnelFailureKind.HostKey, tunnelFailureKind(SshHostKeyRejectedException("h", null)))
        assertEquals(TunnelFailureKind.Auth, tunnelFailureKind(SshAuthenticationException("nope")))
        assertEquals(TunnelFailureKind.Forward, tunnelFailureKind(PortForwardException("busy")))
        assertEquals(TunnelFailureKind.Connection, tunnelFailureKind(SshConnectionException("down")))
        assertEquals(TunnelFailureKind.Connection, tunnelFailureKind(IllegalStateException("who knows")))
    }
}
