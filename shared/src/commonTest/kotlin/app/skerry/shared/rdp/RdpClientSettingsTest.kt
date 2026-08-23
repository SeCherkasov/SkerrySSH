package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the transport tells the server about the session it wants, derived from the profile's target.
 *
 * Worth a test of its own because it is a field-by-field copy: every option a profile can set passes
 * through here on its way to the wire, and a line left behind would ship a default no one asked for
 * with nothing to notice it.
 */
class RdpClientSettingsTest {

    private val target = RdpTarget(
        host = "rds.example.com",
        desktopWidth = 1600,
        desktopHeight = 900,
        clientName = "SKERRY",
        keyboardLayout = 0x419,
        imageQuality = RdpImageQuality.High,
        redirectedSessionId = 42,
        remoteFx = false,
        h264 = RdpH264Mode.Avc420,
        displayScale = 1.5f,
    )

    @Test
    fun `every option of the profile reaches the settings`() {
        val settings = target.clientSettings(RdpSecurityProtocol.HYBRID, audioOpened = false)

        assertEquals(1600, settings.desktopWidth)
        assertEquals(900, settings.desktopHeight)
        assertEquals("SKERRY", settings.clientName)
        assertEquals(RdpSecurityProtocol.HYBRID, settings.selectedProtocol)
        assertEquals(0x419, settings.keyboardLayout)
        assertEquals(42, settings.redirectedSessionId)
        assertEquals(RdpImageQuality.High, settings.imageQuality)
        assertTrue(settings.wantsGraphicsPipeline)
        // Non-default in the fixture on purpose: a dropped mapping would read back the default.
        assertFalse(settings.wantsRemoteFx)
        assertEquals(RdpH264Mode.Avc420, settings.h264)
        assertEquals(1.5f, settings.displayScale)
    }

    @Test
    fun `the protocol comes from the negotiation, not from the profile`() {
        // The server picks it; echoing back anything else is what the server drops the connection on.
        val settings = target.clientSettings(RdpSecurityProtocol.SSL, audioOpened = false)

        assertEquals(RdpSecurityProtocol.SSL, settings.selectedProtocol)
    }

    @Test
    fun `a channel is asked for only when something will speak on it`() {
        val plain = target.copy(clipboard = false, graphicsPipeline = false, dynamicResize = false)
            .clientSettings(RdpSecurityProtocol.SSL, audioOpened = false)
        assertEquals(emptyList<String>(), plain.channels)

        val full = target.clientSettings(RdpSecurityProtocol.SSL, audioOpened = true)
        assertEquals(
            listOf(
                RdpClientSettings.CHANNEL_CLIPBOARD,
                RdpClientSettings.CHANNEL_AUDIO,
                RdpClientSettings.CHANNEL_DYNAMIC,
            ),
            full.channels,
        )
    }

    @Test
    fun `sound the device refused to play is not asked for on the wire`() {
        // The profile wanting audio is not enough: with no device open there is nothing to render
        // it here, and a channel the client never reads would cost bandwidth for every beep.
        val settings = target.clientSettings(RdpSecurityProtocol.SSL, audioOpened = false)

        assertFalse(RdpClientSettings.CHANNEL_AUDIO in settings.channels)
    }

    @Test
    fun `resizing alone still opens the dynamic channel the display control rides`() {
        val settings = target.copy(graphicsPipeline = false, dynamicResize = true)
            .clientSettings(RdpSecurityProtocol.SSL, audioOpened = false)

        assertTrue(RdpClientSettings.CHANNEL_DYNAMIC in settings.channels)
        // A profile that turned the pipeline off must not still advertise MS-RDPEGFX in the GCC
        // data: both sides default to true, so a dropped mapping would be invisible otherwise.
        assertFalse(settings.wantsGraphicsPipeline)
    }
}
