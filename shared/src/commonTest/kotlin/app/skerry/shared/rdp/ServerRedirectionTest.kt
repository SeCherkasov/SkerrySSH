package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server Redirection Packet (MS-RDPBCGR 2.2.13.1): a farm's broker answers the first connection with
 * this and expects the client to dial the host it names. The layout is a flag-gated field sequence,
 * so the tests build packets field by field rather than round-tripping.
 */
class ServerRedirectionTest {

    private fun packet(flags: Int, body: RdpWriter.() -> Unit = {}): RdpReader {
        val writer = RdpWriter(128)
        writer.u16le(0x0400) // SEC_REDIRECTION_PKT
        writer.u16le(0) // length, patched below
        writer.u32le(SESSION_ID)
        writer.u32le(flags)
        writer.body()
        val bytes = writer.toByteArray()
        writer.patchU16le(2, bytes.size)
        return RdpReader(writer.toByteArray())
    }

    private fun RdpWriter.field(text: String) {
        val encoded = RdpWriter(text.length * 2 + 2).utf16le(text, nullTerminated = true).toByteArray()
        u32le(encoded.size)
        bytes(encoded)
    }

    @Test
    fun `reads the target and the routing token the broker chose`() {
        val redirection = ServerRedirection.parse(
            packet(ServerRedirection.LB_TARGET_NET_ADDRESS or ServerRedirection.LB_LOAD_BALANCE_INFO) {
                field("192.168.0.10")
                val token = "tsv://MS Terminal Services Plugin.1.Employees".encodeToByteArray()
                u32le(token.size)
                bytes(token)
            },
        )

        assertEquals(SESSION_ID, redirection.sessionId)
        assertEquals("192.168.0.10", redirection.targetNetAddress)
        assertEquals("tsv://MS Terminal Services Plugin.1.Employees", redirection.loadBalanceInfo)
        assertNull(redirection.targetFqdn)
    }

    @Test
    fun `reads every field of a full redirection in order`() {
        val flags = ServerRedirection.LB_TARGET_NET_ADDRESS or
            ServerRedirection.LB_LOAD_BALANCE_INFO or
            ServerRedirection.LB_USERNAME or
            ServerRedirection.LB_DOMAIN or
            ServerRedirection.LB_PASSWORD or
            ServerRedirection.LB_TARGET_FQDN or
            ServerRedirection.LB_TARGET_NETBIOS_NAME
        val redirection = ServerRedirection.parse(
            packet(flags) {
                field("10.0.0.5")
                val token = "tsv://x".encodeToByteArray()
                u32le(token.size)
                bytes(token)
                field("alice")
                field("CORP")
                field("s3cret")
                field("rds01.corp.example.com")
                field("RDS01")
            },
        )

        assertEquals("10.0.0.5", redirection.targetNetAddress)
        assertEquals("alice", redirection.username)
        assertEquals("CORP", redirection.domain)
        assertEquals("s3cret", redirection.password)
        assertEquals("rds01.corp.example.com", redirection.targetFqdn)
        assertEquals("RDS01", redirection.targetNetBiosName)
    }

    @Test
    fun `an informational packet asks for no reconnection`() {
        val redirection = ServerRedirection.parse(
            packet(ServerRedirection.LB_NOREDIRECT or ServerRedirection.LB_LOAD_BALANCE_INFO) {
                val token = "tsv://x".encodeToByteArray()
                u32le(token.size)
                bytes(token)
            },
        )

        assertTrue(redirection.informationalOnly)
    }

    @Test
    fun `an encrypted password is not mistaken for one we can type`() {
        val redirection = ServerRedirection.parse(
            packet(ServerRedirection.LB_PASSWORD or ServerRedirection.LB_PASSWORD_IS_PK_ENCRYPTED) {
                u32le(4)
                bytes(byteArrayOf(1, 2, 3, 4))
            },
        )

        // The blob belongs to RDSTLS, which this client doesn't speak; treating it as text would
        // send binary noise as the user's password.
        assertNull(redirection.password)
        assertTrue(redirection.passwordIsEncrypted)
    }

    @Test
    fun `a field longer than the packet is refused`() {
        assertFailsWith<RdpProtocolException> {
            ServerRedirection.parse(
                packet(ServerRedirection.LB_TARGET_NET_ADDRESS) {
                    u32le(Int.MAX_VALUE)
                    bytes("short".encodeToByteArray())
                },
            )
        }
    }

    @Test
    fun `a packet that is not a redirection is refused`() {
        val reader = RdpReader(RdpWriter(8).u16le(0x0080).u16le(8).u32le(0).toByteArray())
        assertFailsWith<RdpProtocolException> { ServerRedirection.parse(reader) }
    }

    @Test
    fun `the FQDN is preferred as the host to dial`() {
        val redirection = RdpRedirection(
            sessionId = SESSION_ID,
            flags = 0,
            targetNetAddress = "10.0.0.5",
            targetFqdn = "rds01.corp.example.com",
            targetNetBiosName = "RDS01",
        )

        // The redirected connection runs TLS again, and only the FQDN matches the certificate the
        // target presents; an IP would turn every farm hop into a certificate prompt.
        assertEquals("rds01.corp.example.com", redirection.targetHost)
    }

    @Test
    fun `an address is used when the broker names no FQDN`() {
        assertEquals(
            "10.0.0.5",
            RdpRedirection(SESSION_ID, flags = 0, targetNetAddress = "10.0.0.5", targetNetBiosName = "RDS01").targetHost,
        )
    }

    @Test
    fun `applying a redirection retargets the connection and keeps what it does not name`() {
        val target = RdpTarget(host = "farm.example.com", port = 3390, desktopWidth = 1920, desktopHeight = 1080)
        val credentials = RdpCredentials(username = "alice", password = "s3cret", domain = "CORP")
        val redirection = RdpRedirection(
            sessionId = SESSION_ID,
            flags = ServerRedirection.LB_USERNAME,
            targetFqdn = "rds01.corp.example.com",
            loadBalanceInfo = "tsv://x",
            username = "alice.admin",
        )

        val next = redirection.applyTo(target)
        assertEquals("rds01.corp.example.com", next.host)
        // The port is the farm's, not a default: a broker redirects to the same service.
        assertEquals(3390, next.port)
        assertEquals("tsv://x", next.loadBalanceInfo)
        assertEquals(SESSION_ID, next.redirectedSessionId)

        val nextCredentials = redirection.applyTo(credentials)
        assertEquals("alice.admin", nextCredentials.username)
        assertEquals("CORP", nextCredentials.domain)
        assertEquals("s3cret", nextCredentials.password)
    }

    @Test
    fun `a redirection without a target keeps the host it came from`() {
        val target = RdpTarget(host = "farm.example.com", desktopWidth = 1920, desktopHeight = 1080)
        val next = RdpRedirection(SESSION_ID, flags = 0, loadBalanceInfo = "tsv://x").applyTo(target)

        assertEquals("farm.example.com", next.host)
        assertEquals("tsv://x", next.loadBalanceInfo)
    }

    private companion object {
        const val SESSION_ID = 0x0000002A
    }
}
