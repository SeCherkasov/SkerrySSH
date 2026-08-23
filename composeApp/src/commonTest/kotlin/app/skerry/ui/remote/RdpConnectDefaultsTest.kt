package app.skerry.ui.remote

import androidx.compose.ui.unit.IntSize
import app.skerry.shared.host.Host
import app.skerry.shared.rdp.RdpH264Mode
import app.skerry.shared.rdp.RdpSpec
import app.skerry.shared.rdp.RdpImageQuality
import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Connect-time defaults for an RDP session: the desktop size follows the viewport instead of a
 * hardcoded 1080p (F-06), and the keyboard layout follows the local machine instead of a hardcoded
 * US (F-16).
 */
class RdpConnectDefaultsTest {

    @Test
    fun the_requested_desktop_is_the_viewport_rounded_and_clamped() {
        // RDP wants an even width; anything else is passed through as measured.
        assertEquals(IntSize(1918, 1161), rdpDesktopSize(IntSize(1919, 1161), FALLBACK))
        assertEquals(IntSize(2560, 1440), rdpDesktopSize(IntSize(2560, 1440), FALLBACK))
        // Clamped into what the protocol allows rather than refused.
        assertEquals(IntSize(200, 200), rdpDesktopSize(IntSize(64, 90), FALLBACK))
        assertEquals(IntSize(8192, 8192), rdpDesktopSize(IntSize(20_000, 9_000), FALLBACK))
    }

    @Test
    fun every_option_of_the_profile_reaches_the_request() {
        // The step desktop and mobile share. It used to be typed out on both sides, and the last
        // field added to it went missing on one — every option here is non-default on purpose.
        val host = Host(
            id = "h1",
            label = "rds",
            address = "rds.example.com",
            port = 3390,
            username = "CORP\\ann",
            connectionType = ConnectionType.RDP,
            rdp = RdpSpec(
                loadBalanceInfo = "tsv://farm",
                audioOutput = true,
                audioOutputDeviceId = "hdmi-0",
                clipboard = false,
                quality = RdpImageQuality.High,
                graphicsPipeline = false,
                remoteFx = false,
                h264 = RdpH264Mode.Avc420,
            ),
        )

        val request = host.toRdpRequest(
            "secret",
            RemoteViewport(IntSize(2880, 1800), 1.5f),
            clientName = "SKERRY",
            fallback = FALLBACK,
        )

        assertEquals("rds.example.com", request.host)
        assertEquals(3390, request.port)
        assertEquals("CORP\\ann", request.username)
        assertEquals("secret", request.password)
        assertEquals(2880 to 1800, request.width to request.height)
        assertEquals("SKERRY", request.clientName)
        assertEquals("tsv://farm", request.loadBalanceInfo)
        assertTrue(request.audioOutput)
        assertEquals("hdmi-0", request.audioDeviceId)
        assertFalse(request.clipboard)
        assertEquals(RdpImageQuality.High, request.imageQuality)
        assertFalse(request.graphicsPipeline)
        assertFalse(request.remoteFx)
        assertEquals(RdpH264Mode.Avc420, request.h264)
        // The viewport's scaling, not a default: without it the session is drawn at 96 dpi.
        assertEquals(1.5f, request.displayScale)
    }

    @Test
    fun an_unmeasured_viewport_falls_back_to_the_old_default() {
        assertEquals(FALLBACK, rdpDesktopSize(IntSize.Zero, FALLBACK))
    }

    @Test
    fun the_keyboard_layout_follows_the_locale() {
        assertEquals(0x419, keyboardLayoutFor("ru", "RU"))
        assertEquals(0x407, keyboardLayoutFor("de", "DE"))
        assertEquals(0x809, keyboardLayoutFor("en", "GB"))
        assertEquals(0x409, keyboardLayoutFor("en", "US"))
    }

    @Test
    fun unknown_locales_fall_back_to_us_and_bare_languages_to_their_home_country() {
        assertEquals(0x409, keyboardLayoutFor("xx", ""))
        assertEquals(0x407, keyboardLayoutFor("de", ""), "a bare language maps to its main layout")
        assertEquals(0x419, keyboardLayoutFor("ru", ""))
        assertEquals(0x40C, keyboardLayoutFor("fr", "XX"), "an unknown country keeps the language's layout")
    }

    private companion object {
        val FALLBACK = IntSize(1920, 1080)
    }
}
