package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.rdp.RdpImageQuality
import app.skerry.shared.rdp.RdpResolution
import app.skerry.shared.rdp.RdpSpec
import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals

class RdpDisplayFormStateTest {
    @Test
    fun `RDP display choices survive edit and save without changing the quality preset`() {
        val host = Host(id = "rdp", label = "Windows", address = "host", port = 3389,
            username = "user", connectionType = ConnectionType.RDP,
            rdp = RdpSpec(quality = RdpImageQuality.Low,
                wallpaper = true, desktopScalePercent = 100,
                resolution = RdpResolution(1920, 1080)))
        val form = NewConnectionFormState.fromHost(host)
        assertEquals(true, form.rdpWallpaper)
        assertEquals(100, form.rdpScalePercent)
        val saved = checkNotNull(form.toDraft(id = host.id).rdp)
        assertEquals(host.rdp, saved)
        form.rdpQuality = RdpImageQuality.High
        form.rdpWallpaper = false
        assertEquals(false, checkNotNull(form.toDraft().rdp).wallpaper)
    }

    @Test
    fun `color choices survive editing and invalid hex cannot be saved`() {
        val host = Host(id = "rdp", label = "Windows", address = "host", port = 3389,
            username = "user", connectionType = ConnectionType.RDP,
            rdp = RdpSpec(colorDepth = 16, backgroundRgb = 0x112233))
        val form = NewConnectionFormState.fromHost(host)
        assertEquals(16, form.rdpColorDepth)
        assertEquals("#112233", form.rdpBackgroundHex)
        assertEquals(host.rdp, form.toDraft(id = host.id).rdp)
        form.rdpBackgroundHex = "#ABCDEF"
        assertEquals(0xABCDEF, form.toDraft().rdp?.backgroundRgb)
        for (hex in listOf("#12345G", "+12345", "#12345", "#11223344", "##112233")) {
            form.rdpBackgroundHex = hex
            assertEquals(false, form.canSave, hex)
        }
        form.rdpBackgroundHex = ""
        assertEquals(null, form.toDraft().rdp?.backgroundRgb)
    }
}
