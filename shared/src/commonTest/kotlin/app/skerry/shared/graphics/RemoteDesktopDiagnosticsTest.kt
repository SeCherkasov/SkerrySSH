package app.skerry.shared.graphics

import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-session counters behind the diagnostics overlay. */
class RemoteDesktopDiagnosticsTest {

    private val diagnostics = RemoteDesktopDiagnostics()

    @Test
    fun `paths are recorded once each, in the order they were first seen`() {
        diagnostics.notePath("Surface bits")
        diagnostics.notePath("Bitmap")
        diagnostics.notePath("Surface bits")

        assertEquals(listOf("Surface bits", "Bitmap"), diagnostics.paths)
    }

    @Test
    fun `the decoder note survives into the snapshot`() {
        // F-29: which H.264 decoder is live (ffmpeg hwaccel vs software, MediaCodec) is otherwise
        // invisible — a switch whose effect cannot be seen is a switch nobody can use.
        assertEquals(null, diagnostics.snapshot().decoder)

        diagnostics.noteDecoder("ffmpeg (hwaccel auto)")

        assertEquals("ffmpeg (hwaccel auto)", diagnostics.snapshot().decoder)
    }

    @Test
    fun `counters accumulate and the snapshot carries all of them at once`() {
        diagnostics.noteCodec("RemoteFX")
        diagnostics.noteNegotiated("GFX 10.4")
        diagnostics.serverFrame()
        diagnostics.serverFrame()
        diagnostics.droppedOrder()
        diagnostics.droppedRect()
        diagnostics.fullRepaint()
        diagnostics.readBytes(100)
        diagnostics.readBytes(28)
        diagnostics.wroteBytes(7)
        diagnostics.decodeTime(1_000_000)

        val snapshot = diagnostics.snapshot()
        assertEquals("RemoteFX", snapshot.lastCodec)
        assertEquals("GFX 10.4", snapshot.negotiated)
        assertEquals(2, snapshot.serverFrames)
        assertEquals(1, snapshot.droppedOrders)
        assertEquals(1, snapshot.droppedRects)
        assertEquals(1, snapshot.fullRepaints)
        assertEquals(128, snapshot.bytesIn)
        assertEquals(7, snapshot.bytesOut)
        assertEquals(1_000_000, snapshot.decodeNanos)
        assertEquals(1, snapshot.decodeCount)
    }
}
