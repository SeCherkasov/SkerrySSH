package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val RED = 0xFFFF0000.toInt()

/** The legacy graphics path: RLE bitmaps, palettes, pointers, fast-path framing, surface bits. */
class GraphicsTest {

    @Test
    fun `a colour run paints a solid line`() {
        // COLOR_RUN (code 3) of 4 pixels in 16bpp red, then black for the rest of the row.
        val data = byteArrayOf(0x64, 0x00, 0xF8.toByte(), 0x04, 0x00)

        val pixels = InterleavedRle.decode(data, width = 4, height = 2, bytesPerPixel = 2, palette = null)

        assertEquals(RED, pixels[4]) // bottom row is decoded first
        assertEquals(RED, pixels[7])
    }

    @Test
    fun `a background run copies the row below, which is black on the first decoded line`() {
        // BG_RUN of 4 (first line -> black), then COLOR_RUN of 4 white on the row above it.
        val data = byteArrayOf(0x04, 0x64, 0xFF.toByte(), 0xFF.toByte())

        val pixels = InterleavedRle.decode(data, width = 4, height = 2, bytesPerPixel = 2, palette = null)

        assertEquals(BLACK, pixels[4]) // bottom row: background on the first line
        assertEquals(WHITE, pixels[0]) // top row: the colour run
    }

    @Test
    fun `a background run on a later line repeats the line below it`() {
        // Bottom row: 2 white pixels via COLOR_RUN. Next row: BG_RUN of 2 -> copies them.
        val data = byteArrayOf(0x62, 0xFF.toByte(), 0xFF.toByte(), 0x02)

        val pixels = InterleavedRle.decode(data, width = 2, height = 2, bytesPerPixel = 2, palette = null)

        assertEquals(WHITE, pixels[2])
        assertEquals(WHITE, pixels[0]) // copied from the row below
        assertEquals(WHITE, pixels[1])
    }

    @Test
    fun `the special white and black codes write one pixel each`() {
        val data = byteArrayOf(0xFD.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFE.toByte())

        val pixels = InterleavedRle.decode(data, width = 2, height = 2, bytesPerPixel = 2, palette = null)

        assertEquals(WHITE, pixels[2])
        assertEquals(BLACK, pixels[3])
    }

    @Test
    fun `an 8-bit run resolves its colours through the palette`() {
        val palette = IntArray(256).also { it[7] = RED }
        val data = byteArrayOf(0x62, 0x07)

        val pixels = InterleavedRle.decode(data, width = 2, height = 1, bytesPerPixel = 1, palette = palette)

        assertContentEquals(intArrayOf(RED, RED), pixels)
    }

    @Test
    fun `a run that overruns the bitmap is refused instead of writing past it`() {
        // COLOR_RUN of 31 pixels into a 2x1 bitmap.
        val data = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte())

        assertFailsWith<RdpProtocolException> {
            InterleavedRle.decode(data, width = 2, height = 1, bytesPerPixel = 2, palette = null)
        }
    }

    @Test
    fun `an unknown code is refused rather than guessed at`() {
        assertFailsWith<RdpProtocolException> {
            InterleavedRle.decode(byteArrayOf(0xA0.toByte()), 2, 1, 2, null)
        }
    }

    @Test
    fun `an uncompressed bitmap update lands in the framebuffer bottom-up`() {
        val framebuffer = RemoteFramebuffer(4, 4)
        val body = RdpWriter(64).apply {
            u16le(1) // numberRectangles
            u16le(0).u16le(0).u16le(1).u16le(1) // destLeft, destTop, destRight, destBottom
            u16le(2).u16le(2) // width, height
            u16le(16) // bitsPerPixel
            u16le(0) // flags: uncompressed
            u16le(8) // bitmapLength
            // Two rows of RGB565: the first row on the wire is the bottom row of the image.
            u16le(0xF800).u16le(0xF800)
            u16le(0xFFFF).u16le(0xFFFF)
        }.toByteArray()

        val update = BitmapUpdate.apply(RdpReader(body), framebuffer, null, DroppedGraphics()) as RdpUpdate.Region

        assertEquals(1, update.rects.size)
        assertEquals(WHITE, framebuffer.pixels[0]) // top row of the image
        assertEquals(RED, framebuffer.pixels[framebuffer.width]) // bottom row
    }

    @Test
    fun `a bitmap update of a size no screen has is skipped instead of allocated`() {
        val framebuffer = RemoteFramebuffer(4, 4)
        val body = RdpWriter(32).apply {
            u16le(1) // numberRectangles
            u16le(0).u16le(0).u16le(1).u16le(1)
            // 2.1 billion pixels: 8 GB of heap asked for by a header of a dozen bytes. The product
            // still fits a positive Int, so nothing downstream notices it is impossible.
            u16le(65535).u16le(32000)
            u16le(16) // bitsPerPixel
            u16le(0) // flags: uncompressed
            u16le(0) // bitmapLength
        }.toByteArray()

        val dropped = DroppedGraphics()
        val update = BitmapUpdate.apply(RdpReader(body), framebuffer, null, dropped) as RdpUpdate.Region

        assertEquals(emptyList(), update.rects, "an impossible rectangle reached the framebuffer")
        assertTrue(dropped.take(), "a rectangle that never reached the screen has to be repainted")
    }

    @Test
    fun `uncompressed surface bits land top-down, and missing pixels stay black`() {
        val framebuffer = RemoteFramebuffer(4, 4)
        val body = RdpWriter(48).apply {
            u16le(0x0001) // SET_SURFACE_BITS
            u16le(1).u16le(1).u16le(3).u16le(3) // destination rect
            u8(32).u8(0).u8(0).u8(0) // bpp, flags, reserved, codecId: uncompressed
            u16le(2).u16le(2) // width, height
            u32le(12) // three of the four pixels are on the wire
            u8(0xEF).u8(0xCD).u8(0xAB).u8(0) // B,G,R,X of the first (top-left) pixel
            u8(0xFF).u8(0xFF).u8(0xFF).u8(0)
            u8(0xFF).u8(0xFF).u8(0xFF).u8(0)
        }.toByteArray()

        val updates = SurfaceDecoder().decode(RdpReader(body), framebuffer)

        val region = updates.single() as RdpUpdate.Region
        assertEquals(listOf(RdpRect(1, 1, 2, 2)), region.rects)
        assertEquals(0xFFABCDEF.toInt(), framebuffer.pixels[1 * 4 + 1], "first wire pixel is the TOP row")
        assertEquals(WHITE, framebuffer.pixels[1 * 4 + 2])
        assertEquals(WHITE, framebuffer.pixels[2 * 4 + 1])
        assertEquals(0, framebuffer.pixels[2 * 4 + 2], "the missing fourth pixel reads as unset")
    }

    @Test
    fun `the reused surface scratch never leaks one command's pixels into the next`() {
        // The decode buffer is reused across commands (F-35); a later command with short wire data
        // must read as unset, not as the previous command's colours.
        val framebuffer = RemoteFramebuffer(8, 8)
        val decoder = SurfaceDecoder()
        decoder.decode(RdpReader(uncompressedSurfaceBits(y = 0, width = 4, pixelCount = 4)), framebuffer)

        decoder.decode(RdpReader(uncompressedSurfaceBits(y = 2, width = 2, pixelCount = 1)), framebuffer)

        assertEquals(WHITE, framebuffer.pixels[2 * 8], "the one provided pixel lands")
        assertEquals(0, framebuffer.pixels[2 * 8 + 1], "the missing pixel is unset, not the last command's white")
    }

    /** One SET_SURFACE_BITS of [width]×1 white 32-bit pixels, of which only [pixelCount] are sent. */
    private fun uncompressedSurfaceBits(y: Int, width: Int, pixelCount: Int): ByteArray =
        RdpWriter(64).apply {
            u16le(0x0001)
            u16le(0).u16le(y).u16le(width).u16le(y + 1)
            u8(32).u8(0).u8(0).u8(0)
            u16le(width).u16le(1)
            u32le(pixelCount * 4)
            repeat(pixelCount) { u8(0xFF).u8(0xFF).u8(0xFF).u8(0) }
        }.toByteArray()

    @Test
    fun `surface bits of a size no screen has are refused`() {
        val body = RdpWriter(32).apply {
            u16le(0x0001) // SET_SURFACE_BITS
            u16le(0).u16le(0).u16le(2).u16le(2)
            u8(32).u8(0).u8(0).u8(0) // bpp, flags, reserved, codecId: uncompressed
            u16le(65535).u16le(32000)
            u32le(0)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { SurfaceDecoder().decode(RdpReader(body), RemoteFramebuffer(4, 4)) }
    }

    @Test
    fun `a palette update is applied to the bitmaps that follow it`() {
        val body = RdpWriter(32).apply {
            u16le(0) // pad
            u32le(2) // numberColors
            u8(0xFF).u8(0x00).u8(0x00) // red
            u8(0x00).u8(0xFF).u8(0x00) // green
        }.toByteArray()

        val palette = BitmapUpdate.readPalette(RdpReader(body))

        assertEquals(RED, palette[0])
        assertEquals(0xFF00FF00.toInt(), palette[1])
    }

    @Test
    fun `a colour pointer becomes an ARGB sprite with transparency from the AND mask`() {
        // 2x2 cursor: top-left opaque red, the rest transparent.
        val body = RdpWriter(64).apply {
            u16le(0) // cacheIndex
            u16le(1).u16le(1) // hotspot
            u16le(2).u16le(2) // width, height
            u16le(4) // lengthAndMask: two rows of two padded bytes
            u16le(12) // lengthXorMask: two rows of (2 px * 3 bytes) padded to 6
            // XOR mask, bottom-up: bottom row black, top row red then black.
            u8(0).u8(0).u8(0)
            u8(0).u8(0).u8(0)
            u8(0).u8(0).u8(0xFF) // BGR red at the top-left
            u8(0).u8(0).u8(0)
            // AND mask: bottom row fully transparent, top row transparent except the first pixel.
            u8(0xFF).u8(0x00)
            u8(0x7F).u8(0x00)
        }.toByteArray()

        val shape = PointerUpdate.colorPointer(RdpReader(body), PointerCache())

        assertEquals(2, shape.width)
        assertEquals(RED, shape.argb[0]) // opaque red where the AND bit is clear
        assertEquals(0, shape.argb[1]) // transparent
        assertEquals(0, shape.argb[2])
    }

    @Test
    fun `a 32-bit pointer keeps the alpha its pixels carry`() {
        // 1x1 new pointer, xorBpp 32: colour 0x332211 with alpha 0x80. Windows sends every modern
        // cursor this way, with an all-zero AND mask — dropping the alpha byte painted the
        // antialiased edge and the drop shadow fully opaque (F-19).
        val body = RdpWriter(32).apply {
            u16le(32) // xorBpp
            u16le(0) // cacheIndex
            u16le(0).u16le(0) // hotspot
            u16le(1).u16le(1) // width, height
            u16le(2) // lengthAndMask: one row padded to 2
            u16le(4) // lengthXorMask
            u8(0x11).u8(0x22).u8(0x33).u8(0x80) // B, G, R, A
            u8(0x00).u8(0x00) // AND mask: opaque
        }.toByteArray()

        val shape = PointerUpdate.newPointer(RdpReader(body), PointerCache())

        assertEquals(0x80332211.toInt(), shape.argb[0])
    }

    @Test
    fun `a 32-bit pointer whose alpha plane is all zero falls back to the AND mask`() {
        // FreeRDP's heuristic: an all-zero alpha plane is not "everything invisible", it is a shape
        // with no real alpha — the AND mask decides, as it does for the lower depths.
        val body = RdpWriter(32).apply {
            u16le(32)
            u16le(0)
            u16le(0).u16le(0)
            u16le(2).u16le(1) // 2x1
            u16le(2) // lengthAndMask
            u16le(8) // lengthXorMask: 2 px * 4 bytes
            u8(0).u8(0).u8(0xFF).u8(0) // red, alpha 0
            u8(0).u8(0).u8(0x00).u8(0) // black, alpha 0
            u8(0x40).u8(0x00) // AND 01......: first pixel opaque, second transparent
        }.toByteArray()

        val shape = PointerUpdate.newPointer(RdpReader(body), PointerCache())

        assertEquals(RED, shape.argb[0], "AND=0 keeps the pixel opaque")
        assertEquals(0, shape.argb[1], "AND=1 with black is fully transparent")
    }

    @Test
    fun `the inverting pixels travel on a plane of their own`() {
        // AND=1 with a non-zero XOR is "invert the screen underneath" — the text I-beam. Painted
        // opaque white it disappears on a white field (F-20); the UI needs it as its own plane to
        // draw with a difference blend.
        val body = RdpWriter(64).apply {
            u16le(0) // cacheIndex
            u16le(0).u16le(0) // hotspot
            u16le(2).u16le(1) // 2x1
            u16le(2) // lengthAndMask
            u16le(8) // lengthXorMask: 2 px * 3 bytes padded to 8
            u8(0xFF).u8(0xFF).u8(0xFF) // white: the inverting pixel
            u8(0).u8(0).u8(0)
            u8(0).u8(0)
            u8(0xC0).u8(0x00) // AND 11......: both pixels in the AND=1 half
        }.toByteArray()

        val shape = PointerUpdate.colorPointer(RdpReader(body), PointerCache())

        assertEquals(0, shape.argb[0], "an inverting pixel is not part of the opaque sprite")
        assertEquals(WHITE, shape.invert!![0], "the inverting pixel lives on the invert plane")
        assertEquals(0, shape.invert!![1], "AND=1 with black stays plain transparent")
    }

    @Test
    fun `a pointer with no inverting pixels carries no invert plane`() {
        val body = RdpWriter(32).apply {
            u16le(0)
            u16le(0).u16le(0)
            u16le(1).u16le(1)
            u16le(2)
            u16le(4)
            u8(0).u8(0).u8(0xFF).u8(0) // red, padded row
            u8(0x00).u8(0x00) // opaque
        }.toByteArray()

        val shape = PointerUpdate.colorPointer(RdpReader(body), PointerCache())

        assertNull(shape.invert, "no invert plane to composite when nothing inverts")
    }

    @Test
    fun `a truncated pointer mask reads as opaque, because a wrong cursor beats no cursor`() {
        // No AND-mask bytes at all: every lookup misses. Reading the miss as transparent made the
        // whole cursor invisible (F-24/F-42); opaque shows whatever colour data there is.
        val body = RdpWriter(32).apply {
            u16le(0)
            u16le(0).u16le(0)
            u16le(1).u16le(1)
            u16le(0) // lengthAndMask: none
            u16le(4)
            u8(0).u8(0).u8(0xFF).u8(0) // red
        }.toByteArray()

        val shape = PointerUpdate.colorPointer(RdpReader(body), PointerCache())

        assertEquals(RED, shape.argb[0])
    }

    @Test
    fun `a malformed pointer update is skipped, not the whole session`() {
        // An unsupported depth throws inside the pointer decoder; a bitmap rectangle with the same
        // problem is contained, and a cursor is worth strictly less than the session (F-40).
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), DroppedGraphics(), PointerCache())
        val pointer = RdpWriter(32).apply {
            u16le(7) // xorBpp: no such depth
            u16le(0)
            u16le(0).u16le(0)
            u16le(1).u16le(1)
            u16le(2)
            u16le(2)
            u8(0).u8(0)
            u8(0).u8(0)
        }.toByteArray()

        val updates = decoder.decode(fastPathPacket(updateCode = 0xB, fragmentation = 0, body = pointer), SurfaceDecoder())

        assertTrue(updates.isEmpty(), "the broken shape is dropped and the session lives: $updates")
    }

    @Test
    fun `a pointer larger than the advertised maximum is refused`() {
        val body = RdpWriter(16).apply {
            u16le(0)
            u16le(0).u16le(0)
            u16le(1024).u16le(1024)
            u16le(0).u16le(0)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { PointerUpdate.colorPointer(RdpReader(body), PointerCache()) }
    }

    @Test
    fun `fast-path reassembles an update split across packets`() {
        val framebuffer = RemoteFramebuffer(4, 4)
        val decoder = FastPathDecoder(framebuffer, SessionPalette(), DroppedGraphics(), PointerCache())
        val bitmap = RdpWriter(64).apply {
            u16le(0x0001) // updateType inside the payload
            u16le(1)
            u16le(0).u16le(0).u16le(1).u16le(1)
            u16le(2).u16le(2)
            u16le(16)
            u16le(0)
            u16le(8)
            u16le(0xF800).u16le(0xF800)
            u16le(0xFFFF).u16le(0xFFFF)
        }.toByteArray()

        val first = fastPathPacket(updateCode = 1, fragmentation = 2, body = bitmap.copyOfRange(0, 10))
        val last = fastPathPacket(updateCode = 1, fragmentation = 1, body = bitmap.copyOfRange(10, bitmap.size))

        assertTrue(decoder.decode(first, SurfaceDecoder()).isEmpty(), "the first fragment produces nothing yet")
        val updates = decoder.decode(last, SurfaceDecoder())

        assertTrue(updates.single() is RdpUpdate.Region)
        assertEquals(WHITE, framebuffer.pixels[0])
    }

    @Test
    fun `a fragment run of the wrong type is refused`() {
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), DroppedGraphics(), PointerCache())
        decoder.decode(fastPathPacket(updateCode = 1, fragmentation = 2, body = byteArrayOf(0, 0)), SurfaceDecoder())

        assertFailsWith<RdpProtocolException> {
            decoder.decode(fastPathPacket(updateCode = 2, fragmentation = 3, body = byteArrayOf(0, 0)), SurfaceDecoder())
        }
    }

    @Test
    fun `drawing orders are skipped rather than ending the session`() {
        val dropped = DroppedGraphics()
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), dropped, PointerCache())

        val updates =
            decoder.decode(fastPathPacket(updateCode = 0, fragmentation = 0, body = byteArrayOf(1)), SurfaceDecoder())

        assertTrue(updates.isEmpty())
        assertTrue(dropped.take(), "the skipped orders have to be reported so the pixels can be asked for again")
        assertFalse(dropped.take(), "taking the flag clears it")
    }

    @Test
    fun `an update after skipped orders in the same packet still paints`() {
        val framebuffer = RemoteFramebuffer(4, 4)
        val decoder = FastPathDecoder(framebuffer, SessionPalette(), DroppedGraphics(), PointerCache())
        val bitmap = RdpWriter(64).apply {
            u16le(0x0001) // updateType, repeated inside the payload
            u16le(1) // one rectangle
            u16le(0).u16le(0).u16le(1).u16le(1)
            u16le(2).u16le(2)
            u16le(16) // bitsPerPixel
            u16le(0) // flags: uncompressed
            u16le(8)
            u16le(0xF800).u16le(0xF800)
            u16le(0xFFFF).u16le(0xFFFF)
        }.toByteArray()

        val updates = decoder.decode(
            fastPathPacket(
                fastPathUpdate(updateCode = 0, fragmentation = 0, body = byteArrayOf(1)),
                fastPathUpdate(updateCode = 1, fragmentation = 0, body = bitmap),
            ),
            SurfaceDecoder(),
        )

        assertTrue(updates.single() is RdpUpdate.Region)
        assertEquals(WHITE, framebuffer.pixels[0])
    }

    @Test
    fun `surface bits paint uncompressed pixels top-down and report the frame`() {
        val framebuffer = RemoteFramebuffer(4, 4)
        val body = RdpWriter(64).apply {
            u16le(0x0004) // FRAME_MARKER
            u16le(0) // begin
            u32le(42)
            u16le(0x0001) // SET_SURFACE_BITS
            u16le(0).u16le(0).u16le(2).u16le(2)
            u8(32) // bpp
            u8(0) // flags
            u8(0) // reserved
            u8(0) // codecId: uncompressed
            u16le(2).u16le(2)
            u32le(16)
            // Top-down BGRA: first row red, second white.
            u8(0).u8(0).u8(0xFF).u8(0xFF)
            u8(0).u8(0).u8(0xFF).u8(0xFF)
            u8(0xFF).u8(0xFF).u8(0xFF).u8(0xFF)
            u8(0xFF).u8(0xFF).u8(0xFF).u8(0xFF)
        }.toByteArray()

        val updates = SurfaceDecoder().decode(RdpReader(body), framebuffer)

        assertEquals(RdpUpdate.Frame(42, begin = true), updates.first())
        assertEquals(RED, framebuffer.pixels[0])
        assertEquals(WHITE, framebuffer.pixels[framebuffer.width])
    }

    @Test
    fun `surface bits in a codec that was never negotiated are refused`() {
        val body = RdpWriter(32).apply {
            u16le(0x0001)
            u16le(0).u16le(0).u16le(2).u16le(2)
            u8(32).u8(0).u8(0).u8(3) // codecId 3 = RemoteFX, with no decoder plugged in
            u16le(2).u16le(2)
            u32le(0)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { SurfaceDecoder().decode(RdpReader(body), RemoteFramebuffer(4, 4)) }
    }

    @Test
    fun `surface bits claiming more data than they carry are refused`() {
        val body = RdpWriter(32).apply {
            u16le(0x0001)
            u16le(0).u16le(0).u16le(2).u16le(2)
            u8(32).u8(0).u8(0).u8(0)
            u16le(2).u16le(2)
            u32le(1_000_000)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { SurfaceDecoder().decode(RdpReader(body), RemoteFramebuffer(4, 4)) }
    }

    /** Wrap [body] in a fast-path output packet with one update of [updateCode]. */
    private fun fastPathPacket(updateCode: Int, fragmentation: Int, body: ByteArray): ByteArray =
        fastPathPacket(fastPathUpdate(updateCode, fragmentation, body))

    /** One update record: the header a fast-path packet carries a run of. */
    private fun fastPathUpdate(updateCode: Int, fragmentation: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + 8).apply {
            u8(updateCode or (fragmentation shl 4))
            u16le(body.size)
            bytes(body)
        }.toByteArray()

    private fun fastPathPacket(vararg updates: ByteArray): ByteArray {
        val total = updates.sumOf { it.size } + 3
        return RdpWriter(total).apply {
            u8(0) // action = fast-path output, no flags
            u16be(total or 0x8000) // two-byte length form
            for (update in updates) bytes(update)
        }.toByteArray()
    }
}
