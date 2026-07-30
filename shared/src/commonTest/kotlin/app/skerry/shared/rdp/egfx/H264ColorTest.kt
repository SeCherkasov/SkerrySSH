package app.skerry.shared.rdp.egfx

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The colour transform against values worked out by hand, not by the function under test.
 *
 * This is the one place in the H.264 suite where the arithmetic itself is pinned. Everything else
 * compares a painted pixel with what [H264Color] says it should be, which would agree just as well
 * with the wrong matrix — BT.601 instead of the BT.709 this codec uses, or red and blue swapped.
 *
 * The primaries below were derived the other way round, through the encoder matrix of MS-RDPEGFX
 * 3.3.8.3.1 (Y = (54R + 183G + 18B) >> 8 and so on), so they say what a server that encoded a
 * saturated primary expects to see back.
 */
class H264ColorTest {

    @Test
    fun `neutral chroma is a grey of the luma`() {
        assertEquals(0xFFFFFFFF.toInt(), H264Color.yuvToArgb(255, 128, 128), "white")
        assertEquals(0xFF000000.toInt(), H264Color.yuvToArgb(0, 128, 128), "black")
        assertEquals(0xFF808080.toInt(), H264Color.yuvToArgb(128, 128, 128), "mid grey")
    }

    @Test
    fun `a saturated primary comes back as that primary`() {
        assertEquals(0xFFFC0000.toInt(), H264Color.yuvToArgb(53, 99, 255), "red")
        assertEquals(0xFF00FE00.toInt(), H264Color.yuvToArgb(182, 29, 12), "green")
        assertEquals(0xFF0000FC.toInt(), H264Color.yuvToArgb(17, 255, 116), "blue")
    }

    @Test
    fun `each chroma channel drives the colour it is supposed to`() {
        // The two that a transposed matrix would swap: U carries blue, V carries red, and neither
        // touches the other's channel at all in this matrix.
        val blueward = H264Color.yuvToArgb(128, 255, 128)
        assertEquals(0x80, blueward shr 16 and 0xFF, "U must not move red")
        assertEquals(0xFF, blueward and 0xFF, "U carries blue")

        val redward = H264Color.yuvToArgb(128, 128, 255)
        assertEquals(0xFF, redward shr 16 and 0xFF, "V carries red")
        assertEquals(0x80, redward and 0xFF, "V must not move blue")
    }

    @Test
    fun `a sample outside the range a picture can show is clamped, not wrapped`() {
        // Chroma at either extreme takes two of the three channels past a byte; the third stays where
        // the matrix puts it.
        assertEquals(0xFFFFABFF.toInt(), H264Color.yuvToArgb(255, 255, 255), "red and blue clamped high")
        assertEquals(0xFF005400.toInt(), H264Color.yuvToArgb(0, 0, 0), "red and blue clamped low")
        // Every channel of every combination stays inside a byte; a wrap would paint noise.
        for (luma in 0..255 step 17) {
            for (chroma in 0..255 step 17) {
                val argb = H264Color.yuvToArgb(luma, chroma, 255 - chroma)
                assertEquals(0xFF, argb ushr 24, "alpha of ($luma, $chroma, ${255 - chroma})")
            }
        }
    }
}
