package app.skerry.shared.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for SOCKS5 handshake parsing (RFC 1928, no-auth + CONNECT). Pure logic, exercised
 * over in-memory byte streams with no network or SSH. Covers what's hard to check in an
 * integration test: parsing of all address types and correct rejection replies.
 */
class Socks5Test {

    private fun accept(request: ByteArray): Pair<Socks5.Target?, ByteArray> {
        val out = ByteArrayOutputStream()
        val target = Socks5.accept(ByteArrayInputStream(request), out)
        return target to out.toByteArray()
    }

    @Test
    fun `selects no-auth and parses an IPv4 CONNECT`() {
        // greeting: VER=5, NMETHODS=1, METHOD=0(no-auth); request: CONNECT 127.0.0.1:80
        val request = byteArrayOf(
            5, 1, 0,
            5, 1, 0, 0x01, 127, 0, 0, 1, 0x00, 0x50,
        )
        val (target, written) = accept(request)

        assertEquals(Socks5.Target("127.0.0.1", 80), target)
        // Server selects the no-auth method first.
        assertContentEquals(byteArrayOf(5, 0), written)
    }

    @Test
    fun `parses a domain CONNECT`() {
        val host = "example.com"
        val request = byteArrayOf(5, 1, 0) +
            byteArrayOf(5, 1, 0, 0x03, host.length.toByte()) + host.encodeToByteArray() +
            byteArrayOf(0x01, 0xBB.toByte()) // port 443
        val (target, _) = accept(request)

        assertEquals(Socks5.Target("example.com", 443), target)
    }

    @Test
    fun `parses an IPv6 CONNECT`() {
        // ::1 (loopback) port 22
        val addr = ByteArray(16).also { it[15] = 1 }
        val request = byteArrayOf(5, 1, 0) +
            byteArrayOf(5, 1, 0, 0x04) + addr + byteArrayOf(0, 22)
        val (target, _) = accept(request)

        assertEquals(22, target?.port)
        assertEquals("0:0:0:0:0:0:0:1", target?.host)
    }

    @Test
    fun `rejects a non-SOCKS5 greeting without offering a method`() {
        val (target, written) = accept(byteArrayOf(4, 1, 0)) // SOCKS4 — unsupported

        assertNull(target)
        assertContentEquals(byteArrayOf(5, 0xFF.toByte()), written) // "no acceptable method"
    }

    @Test
    fun `rejects when no-auth is not offered`() {
        val (target, written) = accept(byteArrayOf(5, 1, 2)) // GSSAPI(2) only

        assertNull(target)
        assertContentEquals(byteArrayOf(5, 0xFF.toByte()), written)
    }

    @Test
    fun `rejects a non-CONNECT command with reply 0x07`() {
        // method ok, but command BIND(2) — unsupported
        val request = byteArrayOf(5, 1, 0, 5, 2, 0, 0x01, 127, 0, 0, 1, 0, 80)
        val (target, written) = accept(request)

        assertNull(target)
        // [05,00] method selection, then rejection REP=0x07 with BND 0.0.0.0:0
        assertContentEquals(
            byteArrayOf(5, 0) + byteArrayOf(5, 0x07, 0, 0x01, 0, 0, 0, 0, 0, 0),
            written,
        )
    }

    @Test
    fun `replySuccess writes a success reply with a null bound address`() {
        val out = ByteArrayOutputStream()
        Socks5.replySuccess(out)

        assertContentEquals(byteArrayOf(5, 0x00, 0, 0x01, 0, 0, 0, 0, 0, 0), out.toByteArray())
    }
}
