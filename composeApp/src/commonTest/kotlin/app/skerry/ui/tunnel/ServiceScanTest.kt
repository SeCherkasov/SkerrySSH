package app.skerry.ui.tunnel

import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceScanTest {

    private val ssOutput = """
        State  Recv-Q Send-Q Local Address:Port Peer Address:Port Process
        LISTEN 0      4096         127.0.0.1:5432      0.0.0.0:*    users:(("postgres",pid=812,fd=5))
        LISTEN 0      511            0.0.0.0:80        0.0.0.0:*    users:(("nginx",pid=901,fd=6),("nginx",pid=902,fd=6))
        LISTEN 0      128            0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=640,fd=3))
        LISTEN 0      511               [::]:80           [::]:*    users:(("nginx",pid=901,fd=7))
    """.trimIndent()

    private val netstatOutput = """
        Active Internet connections (only servers)
        Proto Recv-Q Send-Q Local Address           Foreign Address         State       PID/Program name
        tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN      640/sshd
        tcp        0      0 127.0.0.1:6379          0.0.0.0:*               LISTEN      733/redis-server
        tcp6       0      0 :::8080                 :::*                    LISTEN      1204/java
    """.trimIndent()

    @Test
    fun `parses ss output into ports with process names`() {
        val services = parseListeningServices(ssOutput)

        assertEquals(listOf(22, 80, 5432), services.map { it.port })
        assertEquals(listOf("sshd", "nginx", "postgres"), services.map { it.process })
    }

    @Test
    fun `parses netstat output including its ipv6 form`() {
        val services = parseListeningServices(netstatOutput)

        assertEquals(listOf(22, 6379, 8080), services.map { it.port })
        assertEquals(listOf("sshd", "redis-server", "java"), services.map { it.process })
        // ":::8080" is netstat's IPv6 wildcard — the address must not swallow the port.
        assertEquals("::", services.single { it.port == 8080 }.bindAddress)
    }

    @Test
    fun `collapses the same port bound on several addresses and prefers the wildcard`() {
        // ss lists nginx twice (0.0.0.0:80 and [::]:80) — one row, reachable address kept.
        val services = parseListeningServices(ssOutput)

        assertEquals(1, services.count { it.port == 80 })
        assertEquals("0.0.0.0", services.single { it.port == 80 }.bindAddress)
    }

    @Test
    fun `keeps a loopback-only service marked as loopback`() {
        val services = parseListeningServices(ssOutput)

        assertEquals("127.0.0.1", services.single { it.port == 5432 }.bindAddress)
    }

    @Test
    fun `keeps a link-local address scoped by an interface name`() {
        // Zone ids are interface names, so they carry letters outside the hex range an address is
        // otherwise limited to; rejecting them dropped the whole row silently.
        val scoped = "LISTEN 0 128 [fe80::1%eth0]:8080 [::]:* users:((\"testsvc\",pid=1,fd=3))"

        val service = parseListeningServices(scoped).single()
        assertEquals(8080, service.port)
        assertEquals("fe80::1%eth0", service.bindAddress)
    }

    @Test
    fun `the process name follows the address that won, not the row listed first`() {
        // Two programs can hold the same port on different interfaces: the row describes the
        // wildcard listener, so it must not be labelled with the other one's name.
        val contested = """
            LISTEN 0 128 10.0.0.5:8080 0.0.0.0:* users:(("mgmt-agent",pid=1,fd=3))
            LISTEN 0 128  0.0.0.0:8080 0.0.0.0:* users:(("real-app",pid=2,fd=3))
        """.trimIndent()

        val service = parseListeningServices(contested).single()
        assertEquals("0.0.0.0", service.bindAddress)
        assertEquals("real-app", service.process)
    }

    @Test
    fun `a winning address with no process name borrows the other row's`() {
        val partial = """
            LISTEN 0 128 10.0.0.5:8080 0.0.0.0:* users:(("mgmt-agent",pid=1,fd=3))
            LISTEN 0 128  0.0.0.0:8080 0.0.0.0:*
        """.trimIndent()

        assertEquals("mgmt-agent", parseListeningServices(partial).single().process)
    }

    @Test
    fun `ignores headers, blank lines and rows without a port`() {
        val noise = """

            State  Recv-Q Send-Q Local Address:Port
            command not found
            LISTEN 0      128            0.0.0.0:*         0.0.0.0:*
        """.trimIndent()

        assertTrue(parseListeningServices(noise).isEmpty())
    }

    @Test
    fun `rejects out-of-range ports`() {
        val bogus = "LISTEN 0 128 0.0.0.0:70000 0.0.0.0:*\nLISTEN 0 128 0.0.0.0:0 0.0.0.0:*"

        assertTrue(parseListeningServices(bogus).isEmpty())
    }

    @Test
    fun `caps process names and strips control characters from remote output`() {
        // A process name is remote text: an escape sequence would repaint the panel, a long one
        // would push the row off screen.
        val withEscape = "LISTEN 0 128 0.0.0.0:9000 0.0.0.0:* users:((\"\u001B[31mngin${"x".repeat(200)}\",pid=1,fd=3))"

        val process = parseListeningServices(withEscape).single().process!!
        assertTrue(process.length <= SERVICE_NAME_MAX_LEN, "process name not capped: ${process.length}")
        assertTrue(process.none { it.code < 0x20 }, "control characters survived")
    }

    @Test
    fun `strips bidi overrides from a process name`() {
        // The name becomes the saved tunnel's label and syncs onward: a bidi override or a
        // zero-width space would let a host control how that label reads, not just what it says.
        val spoofed = "LISTEN 0 128 0.0.0.0:9001 0.0.0.0:* users:((\"nginx\u202Elive\u200B\",pid=1,fd=3))"

        assertEquals("nginxlive", parseListeningServices(spoofed).single().process)
    }

    @Test
    fun `caps the number of reported services`() {
        val many = (1..500).joinToString("\n") { "LISTEN 0 128 0.0.0.0:$it 0.0.0.0:*" }

        assertEquals(SERVICE_SCAN_MAX_ROWS, parseListeningServices(many).size)
    }

    // Draft built from a discovered service.

    @Test
    fun `draft forwards a wildcard service through loopback on the same port`() {
        val service = ListeningService(port = 5432, bindAddress = "0.0.0.0", process = "postgres")

        val draft = serviceTunnelDraft(service, hostId = "h1", fallbackLabel = "port 5432")

        assertEquals(TunnelDirection.Local, draft.direction)
        assertEquals("h1", draft.hostId)
        assertEquals("postgres", draft.label)
        assertEquals("127.0.0.1", draft.bindHost)
        assertEquals(5432, draft.bindPort)
        assertEquals("127.0.0.1", draft.destHost)
        assertEquals(5432, draft.destPort)
        assertNull(draft.id) // a new tunnel, not an edit
    }

    @Test
    fun `draft lets the OS assign the local port for a privileged remote port`() {
        // Binding 127.0.0.1:80 needs privileges the app does not have; the forward would fail on
        // exactly the ports discovery surfaces most often.
        val service = ListeningService(port = 80, bindAddress = "0.0.0.0", process = "nginx")

        val draft = serviceTunnelDraft(service, hostId = "h1", fallbackLabel = "port 80")

        assertEquals(0, draft.bindPort)
        assertEquals(80, draft.destPort) // the remote side is untouched
    }

    @Test
    fun `draft mirrors an unprivileged remote port locally`() {
        val service = ListeningService(port = 1024, bindAddress = "0.0.0.0", process = null)

        assertEquals(1024, serviceTunnelDraft(service, hostId = "h1", fallbackLabel = "port 1024").bindPort)
    }

    @Test
    fun `draft keeps a specific bind address as the destination`() {
        // Bound to one interface only: loopback would not reach it from the server's side.
        val service = ListeningService(port = 8086, bindAddress = "10.0.0.5", process = null)

        val draft = serviceTunnelDraft(service, hostId = "h1", fallbackLabel = "port 8086")

        assertEquals("10.0.0.5", draft.destHost)
        assertEquals("port 8086", draft.label) // no process name — caller's localized fallback
    }

    @Test
    fun `draft routes an ipv6 wildcard service through ipv6 loopback`() {
        val service = ListeningService(port = 8080, bindAddress = "::", process = "java")

        val draft = serviceTunnelDraft(service, hostId = "h1", fallbackLabel = "port 8080")

        assertEquals("::1", draft.destHost)
    }

    // Which discovered services already have a saved tunnel.

    @Test
    fun `reports ports already forwarded for the scanned host only`() {
        val saved = listOf(
            tunnelOf("a", hostId = "h1", direction = TunnelDirection.Local, destPort = 5432),
            tunnelOf("b", hostId = "h2", direction = TunnelDirection.Local, destPort = 6379),
            tunnelOf("c", hostId = "h1", direction = TunnelDirection.Remote, destPort = 9000),
            tunnelOf("d", hostId = "h1", direction = TunnelDirection.Dynamic, destPort = null),
        )

        // Only local forwards of the scanned host count: a remote forward goes the other way.
        assertEquals(setOf(5432), forwardedPorts(saved, hostId = "h1"))
    }
}

private fun tunnelOf(id: String, hostId: String, direction: TunnelDirection, destPort: Int?) = Tunnel(
    id = id, label = id, hostId = hostId, direction = direction,
    bindHost = "127.0.0.1", bindPort = destPort ?: 1080,
    destHost = destPort?.let { "127.0.0.1" }, destPort = destPort,
)
