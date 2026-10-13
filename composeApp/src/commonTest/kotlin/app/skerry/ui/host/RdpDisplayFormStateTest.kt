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

}
