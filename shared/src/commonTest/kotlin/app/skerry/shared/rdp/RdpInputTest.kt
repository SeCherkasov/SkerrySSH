package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fast-path input events (MS-RDPBCGR 2.2.8.1.2) and the client PDUs that pace the session. */
class RdpInputTest {

    @Test
    fun `a key press carries its scancode and a release sets the release flag`() {
        val press = RdpInput.key(scancode = 0x1E, down = true)
        val release = RdpInput.key(scancode = 0x1E, down = false)

        // action 0 with numberEvents = 1 in bits 2-5 (0x04), the two-byte length of the whole
        // packet, then the event header and the scancode.
        assertEquals("04 80 05 00 1e", press.toHex())
        assertEquals("04 80 05 01 1e", release.toHex())
    }

    @Test
    fun `extended keys are flagged so the server maps the right side of the keyboard`() {
        // Right Alt shares scancode 0x38 with left Alt and is told apart only by the extended flag.
        val rightAlt = RdpInput.key(scancode = 0x38, down = true, extended = true)

        assertEquals(0x02, rightAlt[3].toInt() and 0x1F)
    }

    @Test
    fun `the E1 prefix reaches the wire, which is what Pause is made of`() {
        // Pause is not an E0 key: its two scancodes ride the EXTENDED1 flag (MS-RDPBCGR 2.2.8.1.2.2.1).
        val pauseHalf = RdpInput.key(scancode = 0x1D, down = true, extended1 = true)

        assertEquals(0x04, pauseHalf[3].toInt() and 0x1F)
    }

    @Test
    fun `mouse buttons use the pointer flags of their family`() {
        val left = RdpInput.mouseButton(RdpMouseButton.Left, down = true, x = 100, y = 200)
        val middle = RdpInput.mouseButton(RdpMouseButton.Middle, down = false, x = 100, y = 200)
        val back = RdpInput.mouseButton(RdpMouseButton.Extended1, down = true, x = 1, y = 2)

        assertEquals(0x9000, readFlags(left)) // DOWN | BUTTON1
        assertEquals(0x4000, readFlags(middle)) // BUTTON3, released
        assertEquals(2 shl 5, back[3].toInt() and 0xE0) // event code 2: extended mouse, not plain
        assertEquals(0x8001, readFlags(back)) // DOWN | XBUTTON1
    }

    @Test
    fun `pointer coordinates survive the round trip`() {
        val move = RdpInput.mouseMove(x = 1919, y = 1079)

        assertEquals(1919, readU16le(move, 6))
        assertEquals(1079, readU16le(move, 8))
    }

    @Test
    fun `a wheel notch is 120 units and its sign lives in a separate flag`() {
        val up = RdpInput.mouseWheel(clicks = 1, axis = RdpWheelAxis.Vertical, x = 0, y = 0)
        val down = RdpInput.mouseWheel(clicks = -1, axis = RdpWheelAxis.Vertical, x = 0, y = 0)
        val sideways = RdpInput.mouseWheel(clicks = 1, axis = RdpWheelAxis.Horizontal, x = 0, y = 0)

        assertEquals(0x0200 or 120, readFlags(up))
        assertTrue(readFlags(down) and 0x0100 != 0, "negative rotation is flagged")
        assertTrue(readFlags(sideways) and 0x0400 != 0, "horizontal wheel")
    }

    @Test
    fun `a large scroll delta is clamped instead of wrapping into the flag bits`() {
        // Nine bits hold the rotation; an unclamped delta of 40 notches would overflow into
        // PTRFLAGS_BUTTON1 and arrive at the server as a click.
        val huge = RdpInput.mouseWheel(clicks = 40, axis = RdpWheelAxis.Vertical, x = 0, y = 0)

        val flags = readFlags(huge)
        assertEquals(0, flags and 0xF000, "no button bits are set")
        assertEquals(0xFF, flags and 0x01FF)
    }

    @Test
    fun `lock key state is sent as one sync event`() {
        val sync = RdpInput.syncLockKeys(scrollLock = false, numLock = true, capsLock = true, kanaLock = false)

        assertEquals(3 shl 5 or 0x06, sync[3].toInt() and 0xFF)
    }

    @Test
    fun `a unicode event carries the code point`() {
        val typed = RdpInput.unicode(code = 0x0416, down = true) // Ж

        assertEquals(4 shl 5, typed[3].toInt() and 0xE0)
        assertEquals(0x0416, readU16le(typed, 4))
    }

    @Test
    fun `frame acknowledgement names the frame the server asked about`() {
        val ack = RdpClientPdus.frameAcknowledge(shareId = 0x103EA, userId = 1007, frameId = 77)

        val reader = RdpReader(ack)
        val control = RdpShare.readControlHeader(reader)
        val data = RdpShare.readDataHeader(reader)
        assertEquals(RdpShare.PDUTYPE_DATA, control.pduType)
        assertEquals(RdpShare.PDUTYPE2_FRAME_ACKNOWLEDGE, data.pduType2)
        assertEquals(0x103EA, data.shareId)
        assertEquals(77, reader.u32le())
    }

    @Test
    fun `a refresh request lists rectangles as inclusive bounds`() {
        val refresh = RdpClientPdus.refreshRect(
            shareId = 1,
            userId = 1007,
            rects = listOf(RdpRect(x = 10, y = 20, width = 30, height = 40)),
        )

        val reader = RdpReader(refresh)
        RdpShare.readControlHeader(reader)
        RdpShare.readDataHeader(reader)
        assertEquals(1, reader.u8())
        reader.skip(3)
        assertEquals(10, reader.u16le())
        assertEquals(20, reader.u16le())
        assertEquals(39, reader.u16le()) // right edge is inclusive
        assertEquals(59, reader.u16le())
    }

    @Test
    fun `suppressing output drops the rectangle the server would otherwise keep painting`() {
        val hidden = RdpClientPdus.suppressOutput(shareId = 1, userId = 1007, visible = false, 800, 600)
        val shown = RdpClientPdus.suppressOutput(shareId = 1, userId = 1007, visible = true, 800, 600)

        assertTrue(shown.size > hidden.size, "the visible form carries the desktop rectangle")
    }

    private fun readFlags(packet: ByteArray): Int = readU16le(packet, 4)

    private fun readU16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
