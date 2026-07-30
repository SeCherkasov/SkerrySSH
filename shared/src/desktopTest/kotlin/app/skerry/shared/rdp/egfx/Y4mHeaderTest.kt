package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The one line of the desktop decoder's output that decides how much memory the next picture costs.
 *
 * It is read apart from the process that produces it because that is where an untrusted size arrives:
 * the H.264 stream carries its own parameter sets, so the picture behind this header is as large as
 * the server chose to make it, not as large as the surface it will be painted on.
 */
class Y4mHeaderTest {

    @Test
    fun `a header states the size of the pictures behind it`() {
        val size = y4mPictureSize("YUV4MPEG2 W1920 H1088 F30:1 Ip A1:1 C420mpeg2 XYSCSS=420MPEG2")

        assertEquals(1920, size.width)
        assertEquals(1088, size.height)
    }

    @Test
    fun `both spellings of 4 to 2 to 0 chroma siting are read, and nothing else is`() {
        // A live session produced C420jpeg where the test vector produces C420mpeg2: the two differ by
        // where the chroma sample sits, which this decoder does not depend on.
        assertEquals(32, y4mPictureSize("YUV4MPEG2 W32 H32 F30:1 Ip A1:1 C420jpeg").width)
        assertEquals(32, y4mPictureSize("YUV4MPEG2 W32 H32 F30:1 Ip A1:1").width)

        assertFailsWith<IOException> { y4mPictureSize("YUV4MPEG2 W32 H32 F30:1 Ip A1:1 C444") }
    }

    @Test
    fun `a picture too large to allocate is refused as a protocol error`() {
        // Inside the per-side limit and 400 MB of planes; an OutOfMemoryError here would take the
        // process down past the catch that ends a session cleanly.
        assertFailsWith<RdpProtocolException> { y4mPictureSize("YUV4MPEG2 W16384 H16384 F30:1 Ip A1:1 C420mpeg2") }
        assertFailsWith<RdpProtocolException> { y4mPictureSize("YUV4MPEG2 W99999 H2 F30:1 Ip A1:1 C420mpeg2") }
        assertFailsWith<RdpProtocolException> { y4mPictureSize("YUV4MPEG2 W0 H0 F30:1 Ip A1:1 C420mpeg2") }
    }

    @Test
    fun `anything that is not a stream header is refused`() {
        assertFailsWith<IOException> { y4mPictureSize("") }
        assertFailsWith<IOException> { y4mPictureSize("ffmpeg version 8.1.2") }
        assertFailsWith<IOException> { y4mPictureSize("YUV4MPEG2 H32 F30:1") }
        assertFailsWith<IOException> { y4mPictureSize("YUV4MPEG2 Wwide H32 F30:1") }
    }
}
