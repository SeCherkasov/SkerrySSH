package app.skerry.android.rdp

import app.skerry.shared.rdp.egfx.AndroidImagePlane
import app.skerry.shared.rdp.egfx.AndroidYuvFrames
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Android side of H.264, as far as a host JVM can reach it: a `MediaCodec` needs a real device, so
 * what is pinned here is what comes out of one — planes padded to a stride, cropped to the encoded
 * size, and chroma that may be two planes or one interleaved pair depending on the decoder.
 */
class AndroidYuvFramesTest {

    private val frames = AndroidYuvFrames()

    @Test
    fun `padded planar planes are read back tightly packed`() {
        val luma = plane(stride = 8, height = 4) { x, y -> 10 * y + x }
        val chroma = plane(stride = 6, height = 2) { x, y -> 100 + 10 * y + x }

        val frame = assertNotNull(
            frames.frame(
                planes = listOf(luma, chroma, chroma),
                width = 4,
                height = 4,
            ),
        )

        assertEquals(4, frame.yStride, "the picture is repacked without the decoder's padding")
        assertEquals(2, frame.chromaStride)
        assertEquals(listOf(0, 1, 2, 3, 10, 11, 12, 13), frame.y.take(8).map { it.toInt() and 0xFF })
        assertEquals(listOf(100, 101, 110, 111), frame.u.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `the crop is where the picture starts, not where the plane does`() {
        val luma = plane(stride = 16, height = 8) { x, y -> y * 16 + x }
        val chroma = plane(stride = 8, height = 4) { x, y -> 200 - (y * 8 + x) }

        val frame = assertNotNull(
            frames.frame(listOf(luma, chroma, chroma), width = 4, height = 4, left = 4, top = 2),
        )

        assertEquals(2 * 16 + 4, frame.y[0].toInt() and 0xFF, "the first sample of the cropped picture")
        assertEquals(200 - (1 * 8 + 2), frame.u[0].toInt() and 0xFF, "chroma is cropped at half the offset")
    }

    @Test
    fun `interleaved chroma is separated into the two planes it stands for`() {
        // NV12, which is what a hardware decoder produces: one plane of alternating U and V, handed
        // over twice with the two starting one byte apart.
        val interleaved = ByteArray(2 * 4 * 2) { it.toByte() }
        val u = AndroidImagePlane(ByteBuffer.wrap(interleaved), rowStride = 8, pixelStride = 2)
        val v = AndroidImagePlane(ByteBuffer.wrap(interleaved, 1, interleaved.size - 1).slice(), rowStride = 8, pixelStride = 2)
        val luma = plane(stride = 4, height = 4) { _, _ -> 128 }

        val frame = assertNotNull(frames.frame(listOf(luma, u, v), width = 4, height = 4))

        assertEquals(listOf(0, 2, 8, 10), frame.u.take(4).map { it.toInt() and 0xFF })
        assertEquals(listOf(1, 3, 9, 11), frame.v.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `a picture in a layout this client cannot read is refused rather than guessed at`() {
        val luma = plane(stride = 4, height = 4) { _, _ -> 128 }

        assertNull(frames.frame(listOf(luma), width = 4, height = 4), "a single plane is not 4:2:0")
        assertNull(frames.frame(listOf(luma, luma, luma), width = 0, height = 4), "an empty picture")
        assertNull(frames.frame(listOf(luma, luma, luma), width = 4, height = 4, top = -1), "a crop outside")
    }

    private fun plane(stride: Int, height: Int, sample: (Int, Int) -> Int): AndroidImagePlane {
        val bytes = ByteArray(stride * height)
        for (row in 0 until height) {
            for (x in 0 until stride) bytes[row * stride + x] = sample(x, row).toByte()
        }
        return AndroidImagePlane(ByteBuffer.wrap(bytes), rowStride = stride, pixelStride = 1)
    }
}
