package app.skerry.shared.vnc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    override suspend fun write(bytes: ByteArray) { bytes.forEach { out.add(it) } }
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
        val msg = c.readMessage()

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
        val msg = c.readMessage()

        assertTrue(msg is VncUpdate.Region)
        assertEquals(0xFF0000FF.toInt(), fb.pixels[1]) // opaque blue at x=1
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

        val msg = codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage()
        assertEquals(VncUpdate.Bell, msg)
    }

    @Test
    fun server_cut_text_decodes_latin1_not_utf8() = runTest {
        // RFB text is Latin-1: byte 0xE9 is "é". Decoding it as UTF-8 would yield U+FFFD.
        val update = Wire()
            .u8(RfbCodec.MSG_SERVER_CUT_TEXT).u8(0).u8(0).u8(0)
            .s32(3).bytes(byteArrayOf(0xE9.toByte(), 0x61, 0x62))
            .build()
        val msg = codec(FixtureSource(update), CapturingSink(), VncFramebuffer(1, 1)).readMessage()

        assertTrue(msg is VncUpdate.ClipboardText)
        assertEquals("éab", msg.text)
    }
}
