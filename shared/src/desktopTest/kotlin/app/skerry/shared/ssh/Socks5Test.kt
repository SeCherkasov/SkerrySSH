package app.skerry.shared.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Юнит-тесты разбора SOCKS5-хэндшейка (RFC 1928, метод no-auth + CONNECT). Логика чистая —
 * гоняем её на байтовых потоках в памяти, без сети и SSH. Покрывает то, что трудно проверить в
 * интеграционном тесте: разбор всех типов адреса и корректные отказные ответы.
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
        // Сервер сначала выбирает метод no-auth.
        assertContentEquals(byteArrayOf(5, 0), written)
    }

    @Test
    fun `parses a domain CONNECT`() {
        val host = "example.com"
        val request = byteArrayOf(5, 1, 0) +
            byteArrayOf(5, 1, 0, 0x03, host.length.toByte()) + host.encodeToByteArray() +
            byteArrayOf(0x01, 0xBB.toByte()) // порт 443
        val (target, _) = accept(request)

        assertEquals(Socks5.Target("example.com", 443), target)
    }

    @Test
    fun `parses an IPv6 CONNECT`() {
        // ::1 (loopback) порт 22
        val addr = ByteArray(16).also { it[15] = 1 }
        val request = byteArrayOf(5, 1, 0) +
            byteArrayOf(5, 1, 0, 0x04) + addr + byteArrayOf(0, 22)
        val (target, _) = accept(request)

        assertEquals(22, target?.port)
        assertEquals("0:0:0:0:0:0:0:1", target?.host)
    }

    @Test
    fun `rejects a non-SOCKS5 greeting without offering a method`() {
        val (target, written) = accept(byteArrayOf(4, 1, 0)) // SOCKS4 — не поддерживаем

        assertNull(target)
        assertContentEquals(byteArrayOf(5, 0xFF.toByte()), written) // «нет приемлемого метода»
    }

    @Test
    fun `rejects when no-auth is not offered`() {
        val (target, written) = accept(byteArrayOf(5, 1, 2)) // только GSSAPI(2)

        assertNull(target)
        assertContentEquals(byteArrayOf(5, 0xFF.toByte()), written)
    }

    @Test
    fun `rejects a non-CONNECT command with reply 0x07`() {
        // метод ок, но команда BIND(2) — не поддерживаем
        val request = byteArrayOf(5, 1, 0, 5, 2, 0, 0x01, 127, 0, 0, 1, 0, 80)
        val (target, written) = accept(request)

        assertNull(target)
        // [05,00] выбор метода, затем отказ REP=0x07 с BND 0.0.0.0:0
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
