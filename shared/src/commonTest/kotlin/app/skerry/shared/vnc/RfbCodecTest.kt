package app.skerry.shared.vnc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Builds a server→client byte stream in RFB wire order. */
private class Wire {
    private val b = ArrayList<Byte>()
    fun u8(v: Int) = apply { b.add(v.toByte()) }
    fun u16(v: Int) = apply { b.add((v ushr 8).toByte()); b.add(v.toByte()) }
    fun s32(v: Int) = apply {
        b.add((v ushr 24).toByte()); b.add((v ushr 16).toByte()); b.add((v ushr 8).toByte()); b.add(v.toByte())
    }
    fun bytes(a: ByteArray) = apply { a.forEach { b.add(it) } }
    fun str(s: String) = bytes(s.encodeToByteArray())
    fun build() = b.toByteArray()
}

/** Feeds fixture bytes to the codec, [chunk] bytes at a time (chunk=Int.MAX = whole buffer). */
private class FixtureSource(private val data: ByteArray, private val chunk: Int = Int.MAX_VALUE) : VncSource {
    private var pos = 0
    override suspend fun readFully(dst: ByteArray, offset: Int, len: Int) {
        var written = 0
        while (written < len) {
            if (pos >= data.size) throw VncProtocolException("fixture underrun")
            val take = minOf(len - written, chunk, data.size - pos)
            data.copyInto(dst, offset + written, pos, pos + take)
            pos += take
            written += take
        }
    }
}

private class CapturingSink : VncSink {
    val out = ArrayList<Byte>()

    /** Each write kept whole: the codec writes exactly one message per call, so this is the message log. */
    val messages = ArrayList<ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        messages += bytes
        bytes.forEach { out.add(it) }
    }
    fun bytes() = out.toByteArray()
}

private val fakeInflaters = InflaterFactory {
    object : Inflater {
        override fun inflate(input: ByteArray) = input
        override fun close() {}
    }
}

/** ServerInit body: width, height, 16-byte pixel format (ignored), name. */
private fun Wire.serverInit(width: Int, height: Int, name: String) = apply {
    u16(width); u16(height)
    bytes(ByteArray(16))
    s32(name.length) // nameLen as u32
    str(name)
}

class RfbCodecTest {

    private fun codec(source: VncSource, sink: VncSink, fb: VncFramebuffer, responder: VncChallengeResponder = VncChallengeResponder { _, _ -> ByteArray(16) }) =
        RfbCodec(source, sink, fb, fakeInflaters, responder)

    @Test
    fun handshake_none_security_reads_serverinit_and_replies() = runTest {
        val server = Wire()
            .str("RFB 003.008\n")
            .u8(1).u8(RfbCodec.SEC_NONE)      // one security type: None
            .s32(0)                            // SecurityResult: OK
            .serverInit(4, 2, "desktop")
            .build()
        val sink = CapturingSink()
        val fb = VncFramebuffer(1, 1)
        val name = codec(FixtureSource(server), sink, fb).handshake(VncAuth.None)

        assertEquals("desktop", name)
        assertEquals(4, fb.width)
        assertEquals(2, fb.height)

        val client = sink.bytes()
        assertContentEquals("RFB 003.008\n".encodeToByteArray(), client.copyOfRange(0, 12))
        assertEquals(RfbCodec.SEC_NONE, client[12].toInt())  // chosen security
        assertEquals(1, client[13].toInt())                  // ClientInit: shared = 1
        assertEquals(0, client[14].toInt())                  // SetPixelFormat message type
    }

    @Test
    fun handshake_vnc_auth_sends_des_response() = runTest {
        val challenge = ByteArray(16) { it.toByte() }
        val response = ByteArray(16) { 0xAB.toByte() }
        var seenPassword: String? = null
        val server = Wire()
            .str("RFB 003.008\n")
            .u8(1).u8(RfbCodec.SEC_VNC_AUTH)
            .bytes(challenge)
            .s32(0)
            .serverInit(2, 1, "x")
            .build()
        val sink = CapturingSink()
        val responder = VncChallengeResponder { pw, ch ->
            seenPassword = pw
            assertContentEquals(challenge, ch)
            response
        }
        codec(FixtureSource(server), sink, VncFramebuffer(1, 1), responder).handshake(VncAuth.Password("hunter2"))

        assertEquals("hunter2", seenPassword)
        val client = sink.bytes()
        assertEquals(RfbCodec.SEC_VNC_AUTH, client[12].toInt())      // chosen security
        assertContentEquals(response, client.copyOfRange(13, 29))    // 16-byte DES response follows
    }

    @Test
    fun handshake_auth_failure_throws_with_reason() = runTest {
        val server = Wire()
            .str("RFB 003.008\n")
            .u8(1).u8(RfbCodec.SEC_NONE)
            .s32(1)                            // SecurityResult: failed
            .s32(4).str("nope")                // reason string (3.8)
            .build()
        val ex = assertFailsWith<VncAuthException> {
            codec(FixtureSource(server), CapturingSink(), VncFramebuffer(1, 1)).handshake(VncAuth.None)
        }
        assertTrue(ex.message!!.contains("nope"))
    }

    @Test
    fun handshake_empty_security_list_throws() = runTest {
        val server = Wire()
            .str("RFB 003.008\n")
            .u8(0).s32(6).str("locked")        // count 0 -> failure reason
            .build()
        val ex = assertFailsWith<VncAuthException> {
            codec(FixtureSource(server), CapturingSink(), VncFramebuffer(1, 1)).handshake(VncAuth.None)
        }
        assertTrue(ex.message!!.contains("locked"))
    }

    @Test
    fun quality_maps_to_tight_pseudo_encodings() {
        assertContentEquals(intArrayOf(), RfbCodec.qualityPseudoEncodings(VncQuality.Auto))
        assertContentEquals(intArrayOf(-30, -247), RfbCodec.qualityPseudoEncodings(VncQuality.Low))
        assertContentEquals(intArrayOf(-26, -250), RfbCodec.qualityPseudoEncodings(VncQuality.Medium))
        assertContentEquals(intArrayOf(-23, -253), RfbCodec.qualityPseudoEncodings(VncQuality.High))
    }

    @Test
    fun read_message_decodes_a_raw_rectangle() = runTest {
        // Two pixels: red (FF0000) then green (00FF00), big-endian [pad, R, G, B].
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)   // type, padding, 1 rect
            .u16(0).u16(0).u16(2).u16(1).s32(RfbCodec.ENC_RAW)  // rect 2x1 at (0,0), Raw
            .bytes(byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 0))
            .build()
        val fb = VncFramebuffer(2, 1)
        val c = codec(FixtureSource(update), CapturingSink(), fb)
        val msg = c.readMessage().single()

        assertTrue(msg is VncUpdate.Region)
        assertEquals(listOf(VncRect(0, 0, 2, 1)), msg.rects)
        assertEquals(0xFFFF0000.toInt(), fb.pixels[0]) // opaque red
        assertEquals(0xFF00FF00.toInt(), fb.pixels[1]) // opaque green
    }

    @Test
    fun read_message_survives_byte_by_byte_delivery() = runTest {
        // Same Raw update, but the source releases one byte per call — proves partial-read tolerance.
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
            .u16(1).u16(0).u16(1).u16(1).s32(RfbCodec.ENC_RAW)  // 1x1 at (1,0)
            .bytes(byteArrayOf(0, 0, 0, 0xFF.toByte()))          // blue
            .build()
        val fb = VncFramebuffer(2, 1)
        val c = codec(FixtureSource(update, chunk = 1), CapturingSink(), fb)
        val msg = c.readMessage().single()

        assertTrue(msg is VncUpdate.Region)
        assertEquals(0xFF0000FF.toInt(), fb.pixels[1]) // opaque blue at x=1
    }

    @Test
    fun close_releases_the_persistent_zlib_streams() = runTest {
        class CountingInflater : Inflater {
            var closed = false
            // Ignores input, returns a solid-red ZRLE tile (subencoding 1 + CPIXEL R,G,B).
            override fun inflate(input: ByteArray) = byteArrayOf(1, 0xFF.toByte(), 0, 0)
            override fun close() {
                closed = true
            }
        }

        val created = ArrayList<CountingInflater>()
        val factory = InflaterFactory { CountingInflater().also { created += it } }
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
            .u16(0).u16(0).u16(2).u16(2).s32(RfbCodec.ENC_ZRLE) // 2x2 rect, ZRLE
            .s32(1).u8(0)                                        // compressed length 1 + dummy byte
            .build()
        val fb = VncFramebuffer(2, 2)
        val c = RfbCodec(FixtureSource(update), CapturingSink(), fb, factory, VncChallengeResponder { _, _ -> ByteArray(16) })
        c.readMessage()

        assertEquals(1, created.size) // the persistent ZRLE stream was created
        assertFalse(created.single().closed)

        c.close()

        assertTrue(created.all { it.closed })
        c.close() // idempotent
    }

    @Test
    fun close_before_any_zlib_stream_is_a_no_op() {
        val c = codec(FixtureSource(ByteArray(0)), CapturingSink(), VncFramebuffer(1, 1))
        c.close() // nothing created yet — must not throw
    }

    // --- hardening against a hostile/buggy server (the RFB socket is plaintext and untrusted) ---

    @Test
    fun server_cut_text_with_huge_length_throws_before_allocating() = runTest {
        // A 2 GB clipboard length must be rejected on the header alone — allocating it first would
        // OOM the process (on Android, fatally) for eight bytes of attacker traffic.
        val update = Wire()
            .u8(RfbCodec.MSG_SERVER_CUT_TEXT).u8(0).u8(0).u8(0)
            .s32(Int.MAX_VALUE)
            .build()
        assertFailsWith<VncProtocolException> {
            codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage()
        }
    }

    @Test
    fun server_init_with_oversized_desktop_throws() = runTest {
        // 65535x65535 overflows Int in width*height; reject the dimensions instead of resizing.
        val server = Wire()
            .str("RFB 003.008\n")
            .u8(1).u8(RfbCodec.SEC_NONE)
            .s32(0)
            .serverInit(65535, 65535, "huge")
            .build()
        assertFailsWith<VncProtocolException> {
            codec(FixtureSource(server), CapturingSink(), VncFramebuffer(1, 1)).handshake(VncAuth.None)
        }
    }

    @Test
    fun oversized_rectangle_throws_before_decoding() = runTest {
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
            .u16(0).u16(0).u16(65535).u16(65535).s32(RfbCodec.ENC_RAW)
            .build()
        assertFailsWith<VncProtocolException> {
            codec(FixtureSource(update), CapturingSink(), VncFramebuffer(2, 1)).readMessage()
        }
    }

    @Test
    fun repeated_colour_map_messages_do_not_recurse() = runTest {
        // Many SetColourMaps are skipped (true-colour is forced) and must not grow the call stack;
        // the following Bell proves the loop keeps reading past them.
        val w = Wire()
        repeat(10_000) { w.u8(RfbCodec.MSG_SET_COLOUR_MAP).u8(0).u16(0).u16(0) }
        val update = w.u8(RfbCodec.MSG_BELL).build()

        val msg = codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage().single()
        assertEquals(VncUpdate.Bell, msg)
    }

    @Test
    fun server_cut_text_decodes_latin1_not_utf8() = runTest {
        // RFB text is Latin-1: byte 0xE9 is "é". Decoding it as UTF-8 would yield U+FFFD.
        val update = Wire()
            .u8(RfbCodec.MSG_SERVER_CUT_TEXT).u8(0).u8(0).u8(0)
            .s32(3).bytes(byteArrayOf(0xE9.toByte(), 0x61, 0x62))
            .build()
        val msg = codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage().single()

        assertTrue(msg is VncUpdate.ClipboardText)
        assertEquals("éab", msg.text)
    }

    // --- Cursor pseudo-encoding (client-side cursor) ---

    @Test
    fun cursor_rectangle_decodes_shape_hotspot_and_mask() = runTest {
        // 2x1 cursor, hotspot at (1,0): red then green, mask 0b10xxxxxx = first pixel opaque, second
        // transparent. The rect must NOT touch the framebuffer — the client draws this itself.
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
            .u16(1).u16(0).u16(2).u16(1).s32(RfbCodec.ENC_CURSOR)   // hotspot (1,0), size 2x1
            .bytes(byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 0)) // red, green
            .bytes(byteArrayOf(0b1000_0000.toByte()))                // one mask row, MSB = x0
            .build()
        val fb = VncFramebuffer(2, 1)
        val updates = codec(FixtureSource(update), CapturingSink(), fb).readMessage()

        val cursor = updates.filterIsInstance<VncUpdate.CursorShape>().single()
        assertEquals(2, cursor.width)
        assertEquals(1, cursor.height)
        assertEquals(1, cursor.hotspotX)
        assertEquals(0, cursor.hotspotY)
        // Masked-out pixels are zeroed whole (not just alpha) — see readCursor.
        assertContentEquals(intArrayOf(0xFFFF0000.toInt(), 0), cursor.argb)
        assertEquals(0, fb.pixels[0]) // framebuffer untouched
        assertEquals(0, fb.pixels[1])
    }

    @Test
    fun cursor_and_framebuffer_rects_in_one_message_both_surface() = runTest {
        // Servers pack the cursor shape alongside real rects; neither may swallow the other.
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(2)
            .u16(0).u16(0).u16(1).u16(1).s32(RfbCodec.ENC_CURSOR)    // 1x1 cursor
            .bytes(byteArrayOf(0, 0, 0, 0xFF.toByte()))              // blue
            .bytes(byteArrayOf(0b1000_0000.toByte()))
            .u16(0).u16(0).u16(2).u16(1).s32(RfbCodec.ENC_RAW)       // then a Raw rect
            .bytes(byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 0))
            .build()
        val fb = VncFramebuffer(2, 1)
        val updates = codec(FixtureSource(update), CapturingSink(), fb).readMessage()

        assertEquals(1, updates.filterIsInstance<VncUpdate.CursorShape>().size)
        val region = updates.filterIsInstance<VncUpdate.Region>().single()
        assertEquals(listOf(VncRect(0, 0, 2, 1)), region.rects)
        assertEquals(0xFFFF0000.toInt(), fb.pixels[0])
    }

    @Test
    fun empty_cursor_means_no_cursor() = runTest {
        // A 0x0 shape is how a server says "the cursor is hidden right now".
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
            .u16(0).u16(0).u16(0).u16(0).s32(RfbCodec.ENC_CURSOR)
            .build()
        val updates = codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage()

        val cursor = updates.filterIsInstance<VncUpdate.CursorShape>().single()
        assertEquals(0, cursor.width)
        assertEquals(0, cursor.height)
    }

    @Test
    fun oversized_cursor_throws_before_allocating() = runTest {
        // A cursor is a sprite, not a screen: a 65535-square one is a hostile allocation, not a shape.
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
            .u16(0).u16(0).u16(65535).u16(65535).s32(RfbCodec.ENC_CURSOR)
            .build()
        assertFailsWith<VncProtocolException> {
            codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage()
        }
    }

    @Test
    fun a_desktop_resize_is_not_swallowed_by_rects_in_the_same_message() = runTest {
        // Both must reach the UI: the Resize reallocates the bitmap the Region then writes into.
        val update = Wire()
            .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(2)
            .u16(0).u16(0).u16(4).u16(2).s32(RfbCodec.ENC_DESKTOP_SIZE)
            .u16(0).u16(0).u16(1).u16(1).s32(RfbCodec.ENC_RAW)
            .bytes(byteArrayOf(0, 0, 0, 0xFF.toByte()))
            .build()
        val updates = codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage()

        assertEquals(VncUpdate.Resize(4, 2), updates.filterIsInstance<VncUpdate.Resize>().single())
        assertEquals(listOf(VncRect(0, 0, 1, 1)), updates.filterIsInstance<VncUpdate.Region>().single().rects)
    }

    @Test
    fun set_encodings_offers_the_cursor_pseudo_encoding_until_it_is_turned_off() = runTest {
        // Advertising Cursor is what stops the server painting the cursor into the framebuffer, so it
        // is exactly the switch between server-side and client-side cursor rendering.
        val server = Wire()
            .str("RFB 003.008\n").u8(1).u8(RfbCodec.SEC_NONE).s32(0).serverInit(2, 1, "x")
            .build()
        val sink = CapturingSink()
        val c = codec(FixtureSource(server), sink, VncFramebuffer(1, 1))
        c.handshake(VncAuth.None)
        assertTrue(sink.lastEncodings().contains(RfbCodec.ENC_CURSOR))

        c.setLocalCursor(false)
        assertFalse(sink.lastEncodings().contains(RfbCodec.ENC_CURSOR))
        // The rest of the list must survive the switch — it isn't a cursor-only re-advertisement.
        assertTrue(sink.lastEncodings().contains(RfbCodec.ENC_TIGHT))

        c.setLocalCursor(true)
        assertTrue(sink.lastEncodings().contains(RfbCodec.ENC_CURSOR))
    }

    // --- ExtendedDesktopSize / SetDesktopSize ---

    @Test
    fun default_encodings_advertise_extended_desktop_size() {
        assertTrue(RfbCodec.DEFAULT_ENCODINGS.contains(RfbCodec.ENC_EXTENDED_DESKTOP_SIZE))
    }

    @Test
    fun extended_desktop_size_resizes_and_reports_capability() = runTest {
        val fb = VncFramebuffer(1, 1)
        val updates = codec(FixtureSource(extendedSizeUpdate(0, 0, 4, 2)), CapturingSink(), fb).readMessage()

        assertEquals(VncUpdate.Resize(4, 2), updates.filterIsInstance<VncUpdate.Resize>().single())
        assertTrue(updates.any { it is VncUpdate.SetDesktopSizeSupported })
        assertEquals(4, fb.width)
        assertEquals(2, fb.height)
    }

    @Test
    fun extended_desktop_size_with_unchanged_size_does_not_clear_the_framebuffer() = runTest {
        val fb = VncFramebuffer(2, 1)
        fb.setPixel(0, 0, 0xFF123456.toInt())
        val stream = extendedSizeUpdate(0, 0, 2, 1) + extendedSizeUpdate(2, 0, 2, 1)
        val c = codec(FixtureSource(stream), CapturingSink(), fb)

        // Same size: a Resize would reallocate the buffer and wipe the picture for nothing.
        val first = c.readMessage()
        assertTrue(first.none { it is VncUpdate.Resize })
        assertTrue(first.any { it is VncUpdate.SetDesktopSizeSupported })
        assertEquals(0xFF123456.toInt(), fb.pixels[0])

        // Capability is announced once, not on every ExtendedDesktopSize rect.
        assertTrue(c.readMessage().none { it is VncUpdate.SetDesktopSizeSupported })
    }

    @Test
    fun failed_own_resize_request_does_not_resize() = runTest {
        val fb = VncFramebuffer(2, 1)
        // reason=1 (our SetDesktopSize), status=1 (prohibited). Dimensions deliberately differ from
        // the current size to prove the status guard (not a same-size comparison) blocks the resize.
        val updates = codec(FixtureSource(extendedSizeUpdate(1, 1, 9, 9)), CapturingSink(), fb).readMessage()

        assertTrue(updates.none { it is VncUpdate.Resize })
        assertEquals(2, fb.width)
        assertEquals(1, fb.height)
    }

    @Test
    fun set_desktop_size_echoes_the_server_screen_layout() = runTest {
        val sink = CapturingSink()
        val c = codec(FixtureSource(extendedSizeUpdate(0, 0, 4, 2, screenId = 0x11223344, flags = 5)), sink, VncFramebuffer(1, 1))
        c.readMessage()
        c.writeSetDesktopSize(640, 480)

        val msg = sink.messages.last()
        assertEquals(24, msg.size)
        assertEquals(251, msg[0].toInt() and 0xFF)   // SetDesktopSize
        assertEquals(640, u16At(msg, 2))             // requested width
        assertEquals(480, u16At(msg, 4))             // requested height
        assertEquals(1, msg[6].toInt())              // one screen
        assertEquals(0x11223344, s32At(msg, 8))      // echoed screen id
        assertEquals(0, u16At(msg, 12))              // screen x
        assertEquals(0, u16At(msg, 14))              // screen y
        assertEquals(640, u16At(msg, 16))            // screen covers the whole desktop
        assertEquals(480, u16At(msg, 18))
        assertEquals(5, s32At(msg, 20))              // echoed flags
    }

    @Test
    fun set_desktop_size_before_server_support_is_a_noop() = runTest {
        // The spec forbids SetDesktopSize until the server has sent an ExtendedDesktopSize rect.
        val sink = CapturingSink()
        codec(FixtureSource(ByteArray(0)), sink, VncFramebuffer(1, 1)).writeSetDesktopSize(640, 480)
        assertTrue(sink.messages.isEmpty())
    }
}

/** FramebufferUpdate with one ExtendedDesktopSize rect: header x/y carry [reason]/[status]. */
private fun extendedSizeUpdate(reason: Int, status: Int, w: Int, h: Int, screenId: Int = 7, flags: Int = 3) = Wire()
    .u8(RfbCodec.MSG_FRAMEBUFFER_UPDATE).u8(0).u16(1)
    .u16(reason).u16(status).u16(w).u16(h).s32(RfbCodec.ENC_EXTENDED_DESKTOP_SIZE)
    .u8(1).u8(0).u8(0).u8(0)                          // one screen + 3 bytes padding
    .s32(screenId).u16(0).u16(0).u16(w).u16(h).s32(flags)
    .build()

private fun u16At(msg: ByteArray, off: Int): Int =
    ((msg[off].toInt() and 0xFF) shl 8) or (msg[off + 1].toInt() and 0xFF)

private fun s32At(msg: ByteArray, off: Int): Int =
    ((msg[off].toInt() and 0xFF) shl 24) or ((msg[off + 1].toInt() and 0xFF) shl 16) or
        ((msg[off + 2].toInt() and 0xFF) shl 8) or (msg[off + 3].toInt() and 0xFF)

/** The encoding list from the most recent SetEncodings (type 2) message the codec wrote. */
private fun CapturingSink.lastEncodings(): List<Int> {
    val msg = messages.last { it.isNotEmpty() && it[0].toInt() == 2 }
    val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
    return (0 until count).map { k ->
        val o = 4 + k * 4
        ((msg[o].toInt() and 0xFF) shl 24) or ((msg[o + 1].toInt() and 0xFF) shl 16) or
            ((msg[o + 2].toInt() and 0xFF) shl 8) or (msg[o + 3].toInt() and 0xFF)
    }
}
