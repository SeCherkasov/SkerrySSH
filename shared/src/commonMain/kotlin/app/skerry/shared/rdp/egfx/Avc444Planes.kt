package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpRect
import kotlin.math.abs

/**
 * The full-resolution chroma of one surface, assembled from the two 4:2:0 pictures a 4:4:4 message
 * carries (MS-RDPEGFX 3.3.8.3.2).
 *
 * H.264 has no 4:4:4 mode a hardware encoder will use, so the server sends the desktop twice: once
 * as an ordinary 4:2:0 picture, and once as a second picture whose planes are packed with the chroma
 * samples the first one had to drop. Neither half is a picture of anything on its own, and either may
 * arrive without the other — which is why they are assembled here, into planes that live as long as
 * the surface, rather than converted on the spot.
 *
 * The packing is designed around macroblocks: samples are grouped so that a 16×16 block of the
 * auxiliary picture never straddles two kinds of content, and that is the only reason the row
 * arithmetic below looks the way it does.
 */
internal class Avc444Planes(private val width: Int, private val height: Int) {

    private val luma = ByteArray(width * height)
    private val chromaU = ByteArray(width * height)
    private val chromaV = ByteArray(width * height)

    /**
     * The first picture: its luma is the desktop's, and its quarter-resolution chroma is the average
     * of each 2×2 block, which is what makes the sample the block dropped recoverable (see [paint]).
     */
    fun takeLuma(frame: YuvFrame, region: RdpRect) {
        for (row in 0 until region.height) {
            val source = (region.y + row) * frame.yStride + region.x
            frame.y.copyInto(luma, (region.y + row) * width + region.x, source, source + region.width)
        }
        for (row in 0 until (region.height + 1) / 2) {
            val source = (region.y / 2 + row) * frame.chromaStride + region.x / 2
            val top = (region.y + 2 * row) * width
            // A region of odd size has no second row or column to write to; writing the sample twice
            // costs a store and keeps the loop free of bounds checks.
            val bottom = (region.y + 2 * row + 1).coerceAtMost(region.y + region.height - 1) * width
            for (col in 0 until (region.width + 1) / 2) {
                val left = region.x + 2 * col
                val right = (left + 1).coerceAtMost(region.x + region.width - 1)
                val sampleU = frame.u[source + col]
                val sampleV = frame.v[source + col]
                chromaU[top + left] = sampleU
                chromaU[top + right] = sampleU
                chromaU[bottom + left] = sampleU
                chromaU[bottom + right] = sampleU
                chromaV[top + left] = sampleV
                chromaV[top + right] = sampleV
                chromaV[bottom + left] = sampleV
                chromaV[bottom + right] = sampleV
            }
        }
    }

    /**
     * The auxiliary picture of codec 0x000E. Its luma plane holds the odd rows of the chroma planes —
     * eight rows of U, then eight of V, alternating, so a macroblock holds one or the other — and its
     * own chroma planes hold the odd columns of the even rows.
     */
    fun takeChromaV1(frame: YuvFrame, region: RdpRect) {
        var uRow = 0
        var vRow = 0
        // The auxiliary picture is padded to whole 16-row blocks, and the rows past the region's own
        // height are where the last blocks of V sit.
        val rows = region.height + BLOCK_ROWS - region.height % BLOCK_ROWS
        for (row in 0 until rows) {
            val toU = row % BLOCK_ROWS < BLOCK_ROWS / 2
            // The counters advance even when the row they name is outside the region: the packing is
            // by position in the plane, and a skipped row is a row the encoder still counted.
            val target = if (toU) 2 * uRow++ + 1 else 2 * vRow++ + 1
            if (target >= region.height || region.y + row >= frame.height) continue
            val source = (region.y + row) * frame.yStride + region.x
            val plane = if (toU) chromaU else chromaV
            frame.y.copyInto(plane, (region.y + target) * width + region.x, source, source + region.width)
        }
        for (row in 0 until region.height / 2) {
            val source = (region.y / 2 + row) * frame.chromaStride + region.x / 2
            val target = (region.y + 2 * row) * width + region.x
            for (col in 0 until region.width / 2) {
                chromaU[target + 2 * col + 1] = frame.u[source + col]
                chromaV[target + 2 * col + 1] = frame.v[source + col]
            }
        }
    }

    /**
     * The auxiliary picture of codec 0x000F — the same samples packed by column instead. Its luma
     * plane holds every odd column, U on the left half of the row and V on the right, and its chroma
     * planes hold the even columns of the odd rows. [totalWidth] is where the V half of a row starts,
     * which is the surface width rounded up to 32 rather than the width of the region.
     */
    fun takeChromaV2(frame: YuvFrame, region: RdpRect, totalWidth: Int) {
        for (row in 0 until region.height) {
            val source = (region.y + row) * frame.yStride + region.x / 2
            val target = (region.y + row) * width + region.x
            for (col in 0 until (region.width + 1) / 2) {
                if (region.x + 2 * col + 1 >= width) break
                chromaU[target + 2 * col + 1] = frame.y[source + col]
                chromaV[target + 2 * col + 1] = frame.y[source + col + totalWidth / 2]
            }
        }
        for (row in 0 until (region.height + 1) / 2) {
            val odd = region.y + 2 * row + 1
            if (odd >= region.y + region.height) break
            val source = (region.y / 2 + row) * frame.chromaStride + region.x / 4
            val target = odd * width + region.x
            for (col in 0 until (region.width + 3) / 4) {
                val even = 4 * col
                if (region.x + even < width) {
                    chromaU[target + even] = frame.u[source + col]
                    chromaV[target + even] = frame.u[source + col + totalWidth / 4]
                }
                if (region.x + even + 2 < width) {
                    chromaU[target + even + 2] = frame.v[source + col]
                    chromaV[target + even + 2] = frame.v[source + col + totalWidth / 4]
                }
            }
        }
    }

    /**
     * Paint [region] onto [surface].
     *
     * One sample in four was never transmitted: the encoder replaced it with the average of its 2×2
     * block, so the original is `4 × average` less the three that did arrive. That recovery is what
     * makes coloured text on this codec sharp rather than smeared, and it is only applied where it
     * changes the sample by more than a little — a server that does not average would otherwise have
     * its rounding turned into visible speckle.
     */
    fun paint(region: RdpRect, surface: GraphicsSurface) {
        for (row in 0 until region.height) {
            val source = (region.y + row) * width + region.x
            var target = (region.y + row) * surface.width + region.x
            val topOfBlock = row % 2 == 0 && row + 1 < region.height
            for (col in 0 until region.width) {
                val index = source + col
                var sampleU = chromaU[index].toInt() and 0xFF
                var sampleV = chromaV[index].toInt() and 0xFF
                if (topOfBlock && col % 2 == 0 && col + 1 < region.width) {
                    sampleU = recover(sampleU, chromaU, index)
                    sampleV = recover(sampleV, chromaV, index)
                }
                surface.pixels[target++] = H264Color.yuvToArgb(luma[index].toInt() and 0xFF, sampleU, sampleV)
            }
        }
    }

    /** The sample the encoder averaged away, from the average and the three neighbours it kept. */
    private fun recover(average: Int, plane: ByteArray, index: Int): Int {
        val right = plane[index + 1].toInt() and 0xFF
        val below = plane[index + width].toInt() and 0xFF
        val diagonal = plane[index + width + 1].toInt() and 0xFF
        val recovered = (4 * average - right - below - diagonal).coerceIn(0, 255)
        return if (abs(recovered - average) < RECOVERY_THRESHOLD) average else recovered
    }

    private companion object {
        /** Rows of one macroblock: the unit the auxiliary luma plane alternates U and V in. */
        const val BLOCK_ROWS = 16

        /** Below this the average is kept: the difference is rounding, not a real sample. */
        const val RECOVERY_THRESHOLD = 30
    }
}
