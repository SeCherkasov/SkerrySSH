package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overlay's numbers are computed from two counter snapshots — everything the user reads off the
 * diagnostics overlay is a delta over the interval between them, so the arithmetic is pure and
 * testable without a session.
 */
class RemoteStatsValuesTest {

    @Test
    fun rates_are_deltas_over_the_elapsed_interval() {
        val before = sample(serverFrames = 10, redraws = 5, bytesIn = 0, bytesOut = 0)
        val after = sample(
            serverFrames = 40, // +30 over 2 s = 15.0/s
            redraws = 29, // +24 over 2 s = 12.0/s
            bytesIn = 2_097_152, // 1 MB/s
            bytesOut = 2_048, // 1 KB/s
            decodeNanos = 3_000_000, decodeCount = 2, // 1.5 ms average
            drawNanos = 4_000_000, drawCount = 2, // 2.0 ms average
            bridgeNanos = 1_000_000, bridgeCount = 2, // 0.5 ms average
        )

        val values = remoteStatsValues(before, after, elapsedMillis = 2_000)

        assertEquals("15.0", values.serverFps)
        assertEquals("12.0", values.redrawFps)
        assertEquals("1.0 MB/s", values.rateIn)
        assertEquals("1.0 KB/s", values.rateOut)
        assertEquals("1.5", values.decodeMs)
        assertEquals("2.0", values.drawMs)
        assertEquals("0.5", values.bridgeMs)
    }

    @Test
    fun what_never_happened_reads_as_absent_rather_than_zero() {
        val empty = sample()

        val values = remoteStatsValues(empty, empty, elapsedMillis = 1_000)

        assertEquals("—", values.path)
        assertEquals("—", values.codec)
        assertEquals("—", values.decodeMs)
        assertEquals("0.0", values.serverFps)
        assertEquals("0 B/s", values.rateIn)
    }

    @Test
    fun paths_dropped_counts_and_repaints_are_readable_at_a_glance() {
        val diagnostics = RemoteDesktopDiagnostics().apply {
            notePath("Surface bits")
            notePath("Bitmap")
            noteCodec("RemoteFX")
            droppedOrder()
            droppedOrder()
            droppedRect()
            fullRepaint()
        }
        val now = RemoteStatsSample(diagnostics.snapshot(), 0, 0, 0, 0, 0)

        val values = remoteStatsValues(sample(), now, elapsedMillis = 1_000)

        assertEquals("Surface bits + Bitmap", values.path)
        assertEquals("RemoteFX", values.codec)
        assertEquals("2 / 1", values.dropped)
        assertEquals("1", values.repaints)
    }

    private fun sample(
        serverFrames: Long = 0,
        redraws: Int = 0,
        bytesIn: Long = 0,
        bytesOut: Long = 0,
        decodeNanos: Long = 0,
        decodeCount: Long = 0,
        drawNanos: Long = 0,
        drawCount: Long = 0,
        bridgeNanos: Long = 0,
        bridgeCount: Long = 0,
    ): RemoteStatsSample {
        val diagnostics = RemoteDesktopDiagnostics()
        repeat(serverFrames.toInt()) { diagnostics.serverFrame() }
        if (bytesIn > 0) diagnostics.readBytes(bytesIn.toInt())
        if (bytesOut > 0) diagnostics.wroteBytes(bytesOut.toInt())
        repeat(decodeCount.toInt()) { diagnostics.decodeTime(decodeNanos / decodeCount) }
        return RemoteStatsSample(
            diagnostics = diagnostics.snapshot(),
            redraws = redraws,
            drawNanos = drawNanos,
            drawCount = drawCount,
            bridgeNanos = bridgeNanos,
            bridgeCount = bridgeCount,
        )
    }
}
