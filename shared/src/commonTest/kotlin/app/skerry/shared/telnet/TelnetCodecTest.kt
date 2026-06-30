package app.skerry.shared.telnet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val IAC = 255
private const val SE = 240
private const val SB = 250
private const val WILL = 251
private const val WONT = 252
private const val DO = 253
private const val DONT = 254
private const val ECHO = 1
private const val SGA = 3
private const val TERMINAL_TYPE = 24
private const val NAWS = 31

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class TelnetCodecTest {

    @Test
    fun `plain data passes through untouched`() {
        val d = TelnetCodec().consume("hello".encodeToByteArray())
        assertEquals("hello", d.data.decodeToString())
        assertTrue(d.reply.isEmpty())
    }

    @Test
    fun `escaped IAC becomes a single literal 0xFF in data`() {
        val d = TelnetCodec().consume(bytes(0x41, IAC, IAC, 0x42))
        assertContentEquals(bytes(0x41, 0xFF, 0x42), d.data)
        assertTrue(d.reply.isEmpty())
    }

    @Test
    fun `server WILL ECHO is answered with DO ECHO`() {
        val d = TelnetCodec().consume(bytes(IAC, WILL, ECHO))
        assertContentEquals(bytes(IAC, DO, ECHO), d.reply)
        assertTrue(d.data.isEmpty())
    }

    @Test
    fun `server DO NAWS is answered with WILL NAWS plus window size`() {
        val d = TelnetCodec(cols = 80, rows = 24).consume(bytes(IAC, DO, NAWS))
        // WILL NAWS, затем SB NAWS 0 80 0 24 SE
        assertContentEquals(
            bytes(IAC, WILL, NAWS, IAC, SB, NAWS, 0, 80, 0, 24, IAC, SE),
            d.reply,
        )
    }

    @Test
    fun `server DO for an unsupported option is refused with WONT`() {
        val d = TelnetCodec().consume(bytes(IAC, DO, 99))
        assertContentEquals(bytes(IAC, WONT, 99), d.reply)
    }

    @Test
    fun `repeated identical DO is answered only once (loop guard)`() {
        val codec = TelnetCodec()
        val first = codec.consume(bytes(IAC, DO, SGA))
        assertContentEquals(bytes(IAC, WILL, SGA), first.reply)
        val second = codec.consume(bytes(IAC, DO, SGA))
        assertTrue(second.reply.isEmpty(), "повторный DO SGA не должен вызывать ответ")
    }

    @Test
    fun `terminal-type subnegotiation SEND is answered with IS termtype`() {
        val codec = TelnetCodec(termType = "xterm-256color")
        // IAC SB TERMINAL_TYPE SEND IAC SE
        val d = codec.consume(bytes(IAC, SB, TERMINAL_TYPE, 1, IAC, SE))
        val expected = bytes(IAC, SB, TERMINAL_TYPE, 0) +
            "xterm-256color".encodeToByteArray() +
            bytes(IAC, SE)
        assertContentEquals(expected, d.reply)
    }

    @Test
    fun `negotiation split across two reads is parsed correctly`() {
        val codec = TelnetCodec()
        val a = codec.consume(bytes(0x41, IAC))
        assertEquals("A", a.data.decodeToString())
        assertTrue(a.reply.isEmpty())
        val b = codec.consume(bytes(WILL, ECHO, 0x42))
        assertContentEquals(bytes(IAC, DO, ECHO), b.reply)
        assertEquals("B", b.data.decodeToString())
    }

    @Test
    fun `encode doubles literal IAC in user input`() {
        val out = TelnetCodec().encode(bytes(0x61, 0xFF, 0x62))
        assertContentEquals(bytes(0x61, IAC, IAC, 0x62), out)
    }

    @Test
    fun `single-byte IAC command like NOP is swallowed`() {
        val NOP = 241
        val d = TelnetCodec().consume(bytes(0x41, IAC, NOP, 0x42))
        assertEquals("AB", d.data.decodeToString())
        assertTrue(d.reply.isEmpty())
    }

    @Test
    fun `server WILL ECHO toggles serverEchoEnabled`() {
        val codec = TelnetCodec()
        assertTrue(!codec.serverEchoEnabled) // до неготиации сервер не подтвердил эхо
        codec.consume(bytes(IAC, WILL, ECHO))
        assertTrue(codec.serverEchoEnabled)
        codec.consume(bytes(IAC, WONT, ECHO)) // сервер выключает эхо (напр. prompt пароля)
        assertTrue(!codec.serverEchoEnabled)
    }

    @Test
    fun `oversized subnegotiation without SE is dropped and parser recovers`() {
        val codec = TelnetCodec()
        // IAC SB NAWS, затем 100k байт БЕЗ IAC SE — не должно копиться бесконечно (защита от OOM).
        codec.consume(bytes(IAC, SB, NAWS))
        val flood = ByteArray(100_000) { 0x20 }
        codec.consume(flood)
        // Закрываем «зависшее» SB и шлём обычные данные — парсер должен вернуться в DATA и отдать их.
        val d = codec.consume(bytes(IAC, SE) + "ok".encodeToByteArray())
        assertEquals("ok", d.data.decodeToString())
    }

    @Test
    fun `windowSize builds a NAWS subnegotiation with escaped 0xFF`() {
        // 255 в размере окна должен экранироваться удвоением внутри тела SB.
        val out = TelnetCodec().windowSize(newCols = 255, newRows = 24)
        assertContentEquals(bytes(IAC, SB, NAWS, 0, 255, 255, 0, 24, IAC, SE), out)
    }
}
