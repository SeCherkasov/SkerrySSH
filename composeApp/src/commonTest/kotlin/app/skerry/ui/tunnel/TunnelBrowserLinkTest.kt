package app.skerry.ui.tunnel

import app.skerry.shared.tunnel.TunnelDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TunnelBrowserLinkTest {

    private fun url(
        direction: TunnelDirection = TunnelDirection.Local,
        bindHost: String = "127.0.0.1",
        port: Int = 8080,
    ) = localForwardUrl(direction, bindHost, port)

    @Test
    fun `local forward opens on its bound port over http`() {
        assertEquals("http://127.0.0.1:8080", url())
    }

    @Test
    fun `a wildcard bind address is browsed through loopback`() {
        // 0.0.0.0 is a listen address, not something a browser can connect to.
        assertEquals("http://127.0.0.1:9000", url(bindHost = "0.0.0.0", port = 9000))
        assertEquals("http://127.0.0.1:9000", url(bindHost = "", port = 9000))
        assertEquals("http://[::1]:9000", url(bindHost = "::", port = 9000))
    }

    @Test
    fun `ipv6 literals are bracketed`() {
        assertEquals("http://[::1]:3000", url(bindHost = "::1", port = 3000))
    }

    @Test
    fun `tls ports get the https scheme`() {
        assertEquals("https://127.0.0.1:443", url(port = 443))
        assertEquals("https://127.0.0.1:8443", url(port = 8443))
    }

    @Test
    fun `remote and dynamic forwards have nothing to open`() {
        // -R listens on the server; -D is a SOCKS proxy, not an http endpoint.
        assertNull(url(direction = TunnelDirection.Remote))
        assertNull(url(direction = TunnelDirection.Dynamic))
    }

    @Test
    fun `a port that was never bound has no link`() {
        assertNull(url(port = 0))
        assertNull(url(port = 70000))
    }

    @Test
    fun `a bind address that would break the url is refused`() {
        // bindHost is free-form user input; anything that doesn't survive the link gate is dropped
        // rather than handed to the system browser.
        assertNull(url(bindHost = "127.0.0.1 evil"))
        assertNull(url(bindHost = "host\nname"))
        assertNull(url(bindHost = "a/b"))
    }

    @Test
    fun `an entry only offers a link while it is active`() {
        val entry = TunnelEntry(
            app.skerry.shared.tunnel.Tunnel(
                id = "t1", label = "web", hostId = "h1", direction = TunnelDirection.Local,
                bindHost = "127.0.0.1", bindPort = 0, destHost = "10.0.0.5", destPort = 80,
            ),
        )

        assertNull(tunnelBrowserUrl(entry))

        // Bound port, not the requested one: 0 means "assigned by the OS".
        entry.status = TunnelStatus.Active(50123)
        assertEquals("http://127.0.0.1:50123", tunnelBrowserUrl(entry))
    }
}
