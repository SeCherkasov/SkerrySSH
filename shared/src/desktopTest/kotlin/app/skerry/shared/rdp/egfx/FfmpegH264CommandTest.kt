package app.skerry.shared.rdp.egfx

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ffmpeg command line, without an ffmpeg binary (F-29): the hardware preference must appear as
 * an input option — before `-i` — or ffmpeg reads it as an output option and ignores it, and it must
 * be absent entirely when the app is pinned to software rendering.
 */
class FfmpegH264CommandTest {

    @Test
    fun `the hardware preference puts hwaccel auto before the input`() {
        val cmd = ffmpegH264Command("ffmpeg", hardwareDecode = true)

        val hwaccel = cmd.indexOf("-hwaccel")
        assertTrue(hwaccel >= 0, cmd.toString())
        assertTrue(cmd[hwaccel + 1] == "auto", cmd.toString())
        assertTrue(hwaccel < cmd.indexOf("-i"), "an input option must precede -i: $cmd")
    }

    @Test
    fun `software decode carries no hwaccel at all`() {
        assertFalse("-hwaccel" in ffmpegH264Command("ffmpeg", hardwareDecode = false))
    }
}
