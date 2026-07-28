package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Client Info PDU (MS-RDPBCGR 2.2.1.11.1.1). What matters here is the audio bit: a server that
 * hears INFO_NOAUDIOPLAYBACK never opens the sound channel, however the client asked for it.
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

    /** TS_INFO_PACKET::flags, past the security header and the code page. */
    private fun flagsOf(pdu: ByteArray): Int = RdpReader(pdu).apply { skip(8) }.u32le()

    private companion object {
        val logon = RdpLogonInfo(domain = "CORP", username = "elton")
        const val INFO_NOAUDIOPLAYBACK = 0x08000000
    }
}
