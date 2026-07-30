package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Client Info PDU (MS-RDPBCGR 2.2.1.11.1.1) settles two things for the whole session. The audio
 * bit: a server that hears INFO_NOAUDIOPLAYBACK never opens the sound channel, however the client
 * asked for it. And the performance flags: how much of the desktop the server bothers to draw.
 */
class ClientInfoTest {

    @Test
    fun `a session without audio tells the server not to bother rendering it`() {
        assertEquals(INFO_NOAUDIOPLAYBACK, flagsOf(ClientInfo.pdu(logon)) and INFO_NOAUDIOPLAYBACK)
    }

    @Test
    fun `asking for audio is what clears the no-playback flag`() {
        assertEquals(0, flagsOf(ClientInfo.pdu(logon, audioPlayback = true)) and INFO_NOAUDIOPLAYBACK)
    }

    @Test
    fun `the default quality is what the client has always sent`() {
        // Medium is the behaviour every session had before the profile could choose: wallpaper,
        // window drag, menu animations and theming off, nothing switched back on.
        assertEquals(
            PERF_DISABLE_WALLPAPER or PERF_DISABLE_FULLWINDOWDRAG or PERF_DISABLE_MENUANIMATIONS or
                PERF_DISABLE_THEMING,
            performanceFlagsOf(ClientInfo.pdu(logon)),
        )
        assertEquals(performanceFlagsOf(ClientInfo.pdu(logon)), performanceFlagsOf(ClientInfo.pdu(logon, quality = RdpImageQuality.Medium)))
    }

    @Test
    fun `low quality also drops the cursor effects`() {
        val flags = performanceFlagsOf(ClientInfo.pdu(logon, quality = RdpImageQuality.Low))
        assertEquals(
            PERF_DISABLE_WALLPAPER or PERF_DISABLE_FULLWINDOWDRAG or PERF_DISABLE_MENUANIMATIONS or
                PERF_DISABLE_THEMING or PERF_DISABLE_CURSOR_SHADOW or PERF_DISABLE_CURSORSETTINGS,
            flags,
        )
    }

    @Test
    fun `high quality asks for the full desktop back`() {
        val flags = performanceFlagsOf(ClientInfo.pdu(logon, quality = RdpImageQuality.High))
        assertEquals(PERF_ENABLE_FONT_SMOOTHING or PERF_ENABLE_DESKTOP_COMPOSITION, flags)
    }

    /** TS_INFO_PACKET::flags, past the security header and the code page. */
    private fun flagsOf(pdu: ByteArray): Int = RdpReader(pdu).apply { skip(8) }.u32le()

    private fun performanceFlagsOf(pdu: ByteArray): Int =
        readPerformanceFlags(RdpReader(pdu).apply { skip(4) }) // past the security header

    private companion object {
        val logon = RdpLogonInfo(domain = "CORP", username = "elton")
        const val INFO_NOAUDIOPLAYBACK = 0x08000000

        const val PERF_DISABLE_WALLPAPER = 0x00000001
        const val PERF_DISABLE_FULLWINDOWDRAG = 0x00000002
        const val PERF_DISABLE_MENUANIMATIONS = 0x00000004
        const val PERF_DISABLE_THEMING = 0x00000008
        const val PERF_DISABLE_CURSOR_SHADOW = 0x00000020
        const val PERF_DISABLE_CURSORSETTINGS = 0x00000040
        const val PERF_ENABLE_FONT_SMOOTHING = 0x00000080
        const val PERF_ENABLE_DESKTOP_COMPOSITION = 0x00000100
    }
}
