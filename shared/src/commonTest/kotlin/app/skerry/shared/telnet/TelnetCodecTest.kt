package app.skerry.shared.telnet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Aliases, not copies: a second spelling of 255 in a test file is a number that can drift away from
// the parser it claims to describe.
private const val IAC = TelnetCodec.IAC
private const val SE = TelnetCodec.SE
private const val SB = TelnetCodec.SB
private const val WILL = TelnetCodec.WILL
private const val WONT = TelnetCodec.WONT
private const val DO = TelnetCodec.DO
private const val DONT = TelnetCodec.DONT
private const val ECHO = TelnetCodec.ECHO
private const val SGA = TelnetCodec.SGA
private const val TERMINAL_TYPE = TelnetCodec.TERMINAL_TYPE
private const val NAWS = TelnetCodec.NAWS
private const val TT_SEND = TelnetCodec.TT_SEND

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
        // WILL NAWS, then SB NAWS 0 80 0 24 SE
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
        assertTrue(second.reply.isEmpty(), "a repeated DO SGA should not trigger a reply")
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
        assertTrue(!codec.serverEchoEnabled) // before negotiation the server hasn't confirmed echo
        codec.consume(bytes(IAC, WILL, ECHO))
        assertTrue(codec.serverEchoEnabled)
        codec.consume(bytes(IAC, WONT, ECHO)) // server disables echo (e.g. password prompt)
        assertTrue(!codec.serverEchoEnabled)
    }

    @Test
    fun `oversized subnegotiation without SE is dropped and parser recovers`() {
        val codec = TelnetCodec()
        // IAC SB NAWS, then 100k bytes with no IAC SE: must not buffer unboundedly (OOM guard).
        codec.consume(bytes(IAC, SB, NAWS))
        val flood = ByteArray(100_000) { 0x20 }
        codec.consume(flood)
        // Close the stuck SB and send plain data; the parser must return to DATA and emit it.
        val d = codec.consume(bytes(IAC, SE) + "ok".encodeToByteArray())
        assertEquals("ok", d.data.decodeToString())
    }

    /**
     * The drop scanner has to read an escaped `IAC IAC` the same way the buffering path does: as
     * one literal 0xFF in the body, not as the start of the closing sequence. Reading it the other
     * way ends the subnegotiation at the next byte, and the rest of a body the server chose —
     * escape sequences included — is emitted into the terminal as data.
     */
    @Test
    fun `an escaped IAC inside an oversized subnegotiation does not end it`() {
        val codec = TelnetCodec()
        codec.consume(bytes(IAC, SB, NAWS))
        codec.consume(ByteArray(16_384) { 0x20 }) // past the 8 KiB buffering cap
        // An escaped 0xFF followed by a literal SE byte, then the rest of the body.
        val d = codec.consume(bytes(IAC, IAC, SE) + "smuggled".encodeToByteArray())

        assertEquals("", d.data.decodeToString(), "subnegotiation body reached the terminal as data")
        // The real end of the subnegotiation still works, and data after it is passed through.
        assertEquals("ok", codec.consume(bytes(IAC, SE) + "ok".encodeToByteArray()).data.decodeToString())
    }

    @Test
    fun `a body of escaped IACs trips the cap instead of buffering without bound`() {
        val codec = TelnetCodec()
        // TERMINAL-TYPE SEND: a body the codec acts on, so "was it buffered?" is observable.
        codec.consume(bytes(IAC, SB, TERMINAL_TYPE, TT_SEND))
        // A body made only of escaped 0xFF. Each pair is one byte of body, and the escape path is
        // the one that used to append without ever consulting the cap.
        val d = codec.consume(ByteArray(4 * TelnetCodec.MAX_SUBNEG_BYTES) { IAC.toByte() })
        assertTrue(d.reply.isEmpty(), "an oversized body must not be answered mid-flood")

        // The body is past the cap, so the closing IAC SE must find nothing left to act on.
        val closed = codec.consume(bytes(IAC, SE))
        assertTrue(closed.reply.isEmpty(), "an oversized subnegotiation was buffered and answered")
        assertEquals("", closed.data.decodeToString())

        // The stream recovers: the next subnegotiation is parsed normally.
        val next = codec.consume(bytes(IAC, SB, TERMINAL_TYPE, TT_SEND, IAC, SE))
        assertTrue(next.reply.isNotEmpty(), "parser did not recover after the dropped body")
    }

    @Test
    fun `an escaped IAC straddling the cap does not swallow the closing IAC SE`() {
        val codec = TelnetCodec()
        // TERMINAL-TYPE SEND again, so a body that was buffered would be answered and a body that
        // was dropped would not — otherwise the two orderings of the cap check look identical here.
        codec.consume(bytes(IAC, SB, TERMINAL_TYPE, TT_SEND))
        // Fill the buffer to exactly the cap, so the very next byte trips it.
        codec.consume(ByteArray(TelnetCodec.MAX_SUBNEG_BYTES - 2) { 0x20 })
        // That next byte is the IAC of the closing sequence: the cap must not eat the marker.
        val d = codec.consume(bytes(IAC, SE) + "after".encodeToByteArray())

        assertTrue(d.reply.isEmpty(), "a body past the cap was buffered and answered")
        assertEquals("after", d.data.decodeToString(), "the closing IAC SE was swallowed by the cap")
    }

    @Test
    fun `windowSize builds a NAWS subnegotiation with escaped 0xFF`() {
        // A 255 in the window size must be escaped by doubling inside the SB body.
        val out = TelnetCodec().windowSize(newCols = 255, newRows = 24)
        assertContentEquals(bytes(IAC, SB, NAWS, 0, 255, 255, 0, 24, IAC, SE), out)
    }
}
