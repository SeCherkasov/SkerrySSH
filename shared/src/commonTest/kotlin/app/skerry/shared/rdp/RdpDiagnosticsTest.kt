package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteDesktopDiagnostics
import app.skerry.shared.graphics.RemoteFramebuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The legacy graphics paths feed the diagnostics overlay: which path the server is on, and how much
 * of what it sent never reached the framebuffer (F-09 — those numbers decide whether the drawing
 * orders of F-03 are worth implementing).
 */
class RdpDiagnosticsTest {

    private val framebuffer = RemoteFramebuffer(64, 32)
    private val diagnostics = RemoteDesktopDiagnostics()
    private val decoder = FastPathDecoder(
        framebuffer,
        SessionPalette(),
        DroppedGraphics(),
        PointerCache(),
        diagnostics,
    )

    @Test
    fun `a drawing-orders update is counted, because that count decides whether to implement them`() {
        decoder.decode(fastPath(updateCode = 0x0, body = byteArrayOf(1, 0)), SurfaceDecoder())

        assertEquals(1, diagnostics.droppedOrders)
    }

    @Test
    fun `a bitmap rectangle the client refuses to decode is counted as a dropped rectangle`() {
        // One bitmap rectangle declaring 16000x16000 pixels: past the allocation bound, so the
        // decoder skips it — and the skip must be visible in the overlay, not only on screen.
        val body = RdpWriter(32)
            .u16le(0x0001) // updateType, repeated inside the payload
            .u16le(1) // rectangle count
            .u16le(0).u16le(0).u16le(15_999).u16le(15_999) // left, top, right, bottom
            .u16le(16_000).u16le(16_000) // width, height
            .u16le(32) // bits per pixel
            .u16le(0) // flags
            .u16le(0) // declared length
            .toByteArray()

        decoder.decode(fastPath(updateCode = 0x1, body = body), SurfaceDecoder())

        assertEquals(1, diagnostics.droppedRects)
        assertTrue("Bitmap" in diagnostics.paths, "the bitmap path was seen: ${diagnostics.paths}")
    }

    @Test
    fun `a surface-bits command records its path and codec`() {
        // CMDTYPE_SET_SURFACE_BITS carrying a 1x1 uncompressed 32-bit pixel.
        val body = RdpWriter(32)
            .u16le(0x0001) // commandType
            .u16le(0).u16le(0).u16le(1).u16le(1) // destination rect
            .u8(32) // bitsPerPixel
            .u8(0) // flags
            .u8(0) // reserved
            .u8(0) // codecId: uncompressed
            .u16le(1).u16le(1) // width, height
            .u32le(4) // length
            .bytes(byteArrayOf(1, 2, 3, 0))
            .toByteArray()

        decoder.decode(fastPath(updateCode = 0x4, body = body), SurfaceDecoder(diagnostics = diagnostics))

        assertTrue("Surface bits" in diagnostics.paths, "the surface path was seen: ${diagnostics.paths}")
        assertEquals("Raw", diagnostics.lastCodec)
    }

    /** One single-fragment fast-path packet holding one update. */
    private fun fastPath(updateCode: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + 5)
            .u8(0x00) // fast-path output header
            .u8(body.size + 5) // whole-packet length
            .u8(updateCode) // update header: single fragment, no compression
            .u16le(body.size)
            .bytes(body)
            .toByteArray()
}
