package app.skerry.ui.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure helpers for the tunnel table columns: type, listen port, source/destination. */
class ForwardDisplayTest {

    private fun entry(
        direction: ForwardDirection,
        bindHost: String = "127.0.0.1",
        requestedPort: Int = 0,
        destHost: String = "",
        destPort: Int = 0,
        status: ForwardStatus = ForwardStatus.Starting,
    ) = ForwardEntry(0, direction, bindHost, requestedPort, destHost, destPort).also { it.status = status }

    // Note: forwardTypeLabel is now @Composable (LOCAL/REMOTE/SOCKS labels are string resources,
    // the technical badges are identical in both languages), so the mapping unit test was dropped: it's
    // a plain resource lookup, only verifiable in composition. Below are pure formatting helpers.

    @Test
    fun `listen port is the bound port once active`() {
        val active = entry(ForwardDirection.Local, requestedPort = 0, status = ForwardStatus.Active(50001))
        assertEquals(50001, forwardListenPort(active))
    }

    @Test
    fun `listen port falls back to the requested port before active`() {
        val starting = entry(ForwardDirection.Local, requestedPort = 8080)
        assertEquals(8080, forwardListenPort(starting))
        val failed = entry(ForwardDirection.Local, requestedPort = 8080, status = ForwardStatus.Failed("busy"))
        assertEquals(8080, forwardListenPort(failed))
    }

    @Test
    fun `local source is on this machine, remote source is on the server`() {
        val local = entry(ForwardDirection.Local, bindHost = "127.0.0.1", status = ForwardStatus.Active(8080))
        assertEquals("127.0.0.1:8080", forwardSourceText(local))
        val remote = entry(ForwardDirection.Remote, bindHost = "0.0.0.0", status = ForwardStatus.Active(9000))
        assertEquals("server:9000", forwardSourceText(remote))
    }

    @Test
    fun `destination shows host and port for local and remote`() {
        val local = entry(ForwardDirection.Local, destHost = "10.0.0.5", destPort = 80)
        assertEquals("10.0.0.5:80", forwardDestText(local))
        val remote = entry(ForwardDirection.Remote, destHost = "localhost", destPort = 3000)
        assertEquals("localhost:3000", forwardDestText(remote))
    }

    @Test
    fun `dynamic forward has no fixed destination`() {
        assertNull(forwardDestText(entry(ForwardDirection.Dynamic)))
    }

    @Test
    fun `human rate scales bytes per second across units`() {
        assertEquals("512 B/s", humanRate(512))
        assertEquals("42 KB/s", humanRate(42L * 1024))
        assertEquals("1.1 MB/s", humanRate(1_200_000)) // ~1.14 MiB/s rounds down to 1.1
        assertEquals("0 B/s", humanRate(0))
    }

    @Test
    fun `rate fraction saturates at one mebibyte per second`() {
        assertEquals(0f, rateFraction(0))
        assertEquals(1f, rateFraction(1024L * 1024))
        assertEquals(1f, rateFraction(5L * 1024 * 1024)) // saturation
    }
}
