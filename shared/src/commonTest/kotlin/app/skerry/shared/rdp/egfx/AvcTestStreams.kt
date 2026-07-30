package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpWriter

/**
 * The wire wrappers of the H.264 codecs, built the way a server builds them, and the encoder side of
 * the 4:4:4 split — a full-resolution picture cut into the two 4:2:0 frames the wire carries.
 *
 * The split is what makes a round-trip test possible: it is written from the same specification
 * clause as the decoder ([MS-RDPEGFX] 3.3.8.3.2) but from the other end, so a picture that survives
 * the trip pins the packing rather than one reading of it. Its individual steps are also pinned
 * directly in `AvcCodecTest`, so that a mistake made twice in the same direction cannot cancel out.
 */

/** MS-RDPEGFX 2.2.4.4 RFX_AVC420_BITMAP_STREAM. */
internal fun avc420Message(regions: List<RdpRect>, bitstream: ByteArray): ByteArray {
    val writer = RdpWriter(regions.size * 10 + bitstream.size + 4).u32le(regions.size)
    for (rect in regions) {
        writer.u16le(rect.x).u16le(rect.y).u16le(rect.x + rect.width).u16le(rect.y + rect.height)
    }
    // qpVal and qualityVal, which say how the region was encoded and not how it is read back.
    repeat(regions.size) { writer.u8(0x1E).u8(70) }
    return writer.bytes(bitstream).toByteArray()
}

/**
 * MS-RDPEGFX 2.2.4.5 RFX_AVC444_BITMAP_STREAM. [contents] is the LC field: 0 both halves, 1 luma
 * only, 2 chroma only.
 */
internal fun avc444Message(contents: Int, first: ByteArray, second: ByteArray? = null): ByteArray {
    val declared = if (contents == AVC444_BOTH) first.size else 0
    val header = RdpWriter(4).u32le((contents shl 30) or declared).toByteArray()
    return header + first + (second ?: ByteArray(0))
}

internal const val AVC444_BOTH = 0
internal const val AVC444_LUMA_ONLY = 1
internal const val AVC444_CHROMA_ONLY = 2

/** A full-resolution YUV picture: one chroma sample per pixel, which is what 4:4:4 means. */
internal class Yuv444Image(val width: Int, val height: Int) {
    val y = ByteArray(width * height)
    val u = ByteArray(width * height)
    val v = ByteArray(width * height)

    operator fun set(x: Int, row: Int, argb: Int) {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        val index = row * width + x
        y[index] = ((54 * red + 183 * green + 18 * blue) shr 8).toByte()
        u[index] = (((-29 * red - 99 * green + 128 * blue) shr 8) + 128).toByte()
        v[index] = (((128 * red - 116 * green - 12 * blue) shr 8) + 128).toByte()
    }

    /**
     * The 4:2:0 frame the server sends first: the luma untouched, and one chroma sample per 2×2 block
     * — the average of the four, which is what makes the missing sample recoverable.
     */
    fun mainFrame(): YuvFrame {
        val halfWidth = (width + 1) / 2
        val halfHeight = (height + 1) / 2
        val chromaU = ByteArray(halfWidth * halfHeight)
        val chromaV = ByteArray(halfWidth * halfHeight)
        for (row in 0 until halfHeight) {
            for (col in 0 until halfWidth) {
                chromaU[row * halfWidth + col] = average(u, col, row).toByte()
                chromaV[row * halfWidth + col] = average(v, col, row).toByte()
            }
        }
        return YuvFrame(y.copyOf(), chromaU, chromaV, width, height, chromaStride = halfWidth)
    }

    /**
     * The auxiliary frame of codec 0x000E: its luma plane holds the odd chroma rows, sixteen rows of
     * U and V alternating in blocks of eight so that a macroblock never straddles the two, and its
     * own chroma planes hold the odd columns of the even rows.
     */
    fun auxFrameV1(): YuvFrame {
        val halfWidth = (width + 1) / 2
        val halfHeight = (height + 1) / 2
        val luma = ByteArray(width * height)
        var uRow = 0
        var vRow = 0
        for (row in 0 until height) {
            val fromU = row % 16 < 8
            val source = if (fromU) 2 * uRow++ + 1 else 2 * vRow++ + 1
            if (source >= height) continue
            val plane = if (fromU) u else v
            plane.copyInto(luma, row * width, source * width, source * width + width)
        }
        val chromaU = ByteArray(halfWidth * halfHeight)
        val chromaV = ByteArray(halfWidth * halfHeight)
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                chromaU[row * halfWidth + col] = u[2 * row * width + 2 * col + 1]
                chromaV[row * halfWidth + col] = v[2 * row * width + 2 * col + 1]
            }
        }
        return YuvFrame(luma, chromaU, chromaV, width, height, chromaStride = halfWidth)
    }

    /**
     * The auxiliary frame of codec 0x000F. The same samples, packed by column instead: the luma plane
     * holds every odd column, U on its left half and V on its right, and the chroma planes hold the
     * even columns of the odd rows.
     */
    fun auxFrameV2(): YuvFrame {
        val halfWidth = (width + 1) / 2
        val halfHeight = (height + 1) / 2
        val quarterWidth = (width + 3) / 4
        val luma = ByteArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until halfWidth) {
                luma[row * width + col] = u[row * width + 2 * col + 1]
                luma[row * width + width / 2 + col] = v[row * width + 2 * col + 1]
            }
        }
        val chromaU = ByteArray(halfWidth * halfHeight)
        val chromaV = ByteArray(halfWidth * halfHeight)
        for (row in 0 until halfHeight) {
            val odd = 2 * row + 1
            if (odd >= height) continue
            for (col in 0 until quarterWidth) {
                chromaU[row * halfWidth + col] = u[odd * width + 4 * col]
                chromaU[row * halfWidth + width / 4 + col] = v[odd * width + 4 * col]
                chromaV[row * halfWidth + col] = u[odd * width + 4 * col + 2]
                chromaV[row * halfWidth + width / 4 + col] = v[odd * width + 4 * col + 2]
            }
        }
        return YuvFrame(luma, chromaU, chromaV, width, height, chromaStride = halfWidth)
    }

    private fun average(plane: ByteArray, col: Int, row: Int): Int {
        val right = minOf(2 * col + 1, width - 1)
        val below = minOf(2 * row + 1, height - 1)
        val sum = sample(plane, 2 * col, 2 * row) + sample(plane, right, 2 * row) +
            sample(plane, 2 * col, below) + sample(plane, right, below)
        return sum / 4
    }

    private fun sample(plane: ByteArray, x: Int, row: Int): Int = plane[row * width + x].toInt() and 0xFF
}
