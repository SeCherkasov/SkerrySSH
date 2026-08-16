package app.skerry.shared.rdp.egfx

import app.skerry.shared.process.resolveExecutableOnPath
import app.skerry.shared.rdp.RdpRect
import kotlin.io.encoding.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The desktop decoder against a real `ffmpeg`, on a stream small enough to keep in the test.
 *
 * What it is here for is the exchange, not the arithmetic: one picture per access unit, in step, with
 * nothing held back. Every plausible way of driving `ffmpeg` gets that wrong in a different way — it
 * buffers the output, it drops the pictures it consumed while probing, or it hands over a picture only
 * once the next one has been pushed in — and each of those looks, in a session, like a desktop that
 * lags by one update or freezes when it goes still.
 */
class FfmpegH264DecoderTest {

    /**
     * Skipped, not passed, where there is no `ffmpeg`. This is the only test of flags that were
     * arrived at empirically, and a green tick from a machine that never ran it is how a regression in
     * them would ship — so CI installs `ffmpeg` rather than trusting the runner image to carry one.
     */
    @BeforeTest
    fun requireFfmpeg() {
        assumeTrue(resolveExecutableOnPath("ffmpeg") != null, "no ffmpeg on PATH")
    }

    @Test
    fun `every access unit produces its own picture, in step`() {
        val decoders = FfmpegH264Decoders()
        assertTrue(decoders.available, "ffmpeg is on PATH but the factory says H.264 is unavailable")
        val decoder = assertNotNull(decoders.open(WIDTH, HEIGHT), "no decoder opened")

        try {
            for ((index, accessUnit) in stream().withIndex()) {
                val frame = assertNotNull(decoder.decode(accessUnit), "access unit $index produced no picture")
                assertEquals(WIDTH, frame.width)
                assertEquals(HEIGHT, frame.height)
                assertEquals(
                    LUMA[index],
                    frame.y[0].toInt() and 0xFF,
                    "picture $index is the one from another access unit",
                )
                assertTrue(
                    (0 until WIDTH * HEIGHT).all { (frame.y[it].toInt() and 0xFF) == LUMA[index] },
                    "picture $index is not the flat one that was encoded",
                )
                assertEquals(NEUTRAL_CHROMA, frame.u[0].toInt() and 0xFF, "the chroma of a grey picture")
            }
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `a 4 to 2 to 0 message paints the surface it names, picture by picture`() {
        // The whole desktop path, from the bytes a server would put on the wire to the pixels of a
        // surface: the message wrapper, the real decoder, and the colour transform.
        val codec = AvcCodec(FfmpegH264Decoders())
        val surface = GraphicsSurface(1, WIDTH, HEIGHT)

        try {
            for ((index, accessUnit) in stream().withIndex()) {
                val touched = codec.decodeAvc420(
                    avc420Message(listOf(RdpRect(0, 0, WIDTH, HEIGHT)), accessUnit),
                    surface,
                )

                assertEquals(listOf(RdpRect(0, 0, WIDTH, HEIGHT)), touched, "picture $index painted nothing")
                val grey = H264Color.yuvToArgb(LUMA[index], NEUTRAL_CHROMA, NEUTRAL_CHROMA)
                assertTrue(
                    surface.pixels.all { it == grey },
                    "picture $index left the surface something other than the flat grey it encodes",
                )
            }
        } finally {
            codec.close()
        }
    }

    @Test
    fun `a decoder that has been closed decodes nothing more`() {
        val decoder = assertNotNull(FfmpegH264Decoders().open(WIDTH, HEIGHT))
        decoder.close()

        assertEquals(null, decoder.decode(stream().first()), "a closed decoder answered with a picture")
        decoder.close()
    }

    @Test
    fun `a decoder that died keeps saying so instead of going quiet`() {
        // The contract: a dead decoder THROWS on every call — a quiet null after an internal
        // failure would read as "no picture this update" and freeze the surface without a word.
        // Garbage that is not an H.264 stream makes ffmpeg give up; worst case this waits out the
        // reader's own deadline once, never twice.
        val decoder = assertNotNull(FfmpegH264Decoders().open(WIDTH, HEIGHT))
        val first = runCatching { decoder.decode(ByteArray(64) { 0x5A }) }
        assumeTrue(first.isFailure, "this ffmpeg accepted garbage; nothing to verify here")

        assertFailsWith<IllegalStateException> { decoder.decode(stream().first()) }
    }

    private fun stream(): List<ByteArray> = ACCESS_UNITS.map { Base64.decode(it) }

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 32

        const val NEUTRAL_CHROMA = 128

        /** Three flat pictures, so a picture that arrives out of step is obvious from one sample. */
        val LUMA = intArrayOf(60, 120, 180)

        /**
         * A 32×32 baseline stream of three frames — a key frame carrying its own parameter sets, then
         * two that are differences from it, which is the shape of what a Windows host sends. Encoded
         * at a quantiser fine enough that a flat picture survives exactly.
         */
        val ACCESS_UNITS = listOf(
            "AAAAAWdCwArZCWhAAAADAEAAAA8DxImSAAAAAWjLgFssgAAAAWWIhDomKAAOHsnXXg==",
            "AAAAAUGIiOiYoAAipyddeA==",
            "AAAAAUGIkOiYoAAypSddeA==",
        )
    }
}
