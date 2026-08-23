package app.skerry.ui.remote

import app.skerry.shared.rdp.RdpCredentials
import app.skerry.shared.rdp.RdpH264Mode
import app.skerry.shared.rdp.RdpImageQuality
import app.skerry.shared.rdp.RdpSession
import app.skerry.shared.rdp.RdpTarget
import app.skerry.shared.rdp.RdpTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The request a tab dials with, and the target it becomes. Worth a test of its own for the same
 * reason [app.skerry.shared.rdp.RdpClientSettings] has one: it is a field-by-field copy, and this
 * one used to be typed out in three session graphs — the copy that ships on Android was the one the
 * display scaling was missed on, so the feature was dead on the platform that needed it most.
 */
class RdpConnectRequestTest {

    private val request = RdpConnectRequest(
        host = "rds.example.com",
        port = 3390,
        username = "CORP\\ann",
        password = "secret",
        width = 2880,
        height = 1800,
        clientName = "SKERRY",
        loadBalanceInfo = "tsv://farm",
        audioOutput = true,
        audioDeviceId = "hdmi-0",
        clipboard = false,
        imageQuality = RdpImageQuality.High,
        keyboardLayout = 0x419,
        graphicsPipeline = false,
        remoteFx = false,
        h264 = RdpH264Mode.Avc420,
        displayScale = 1.5f,
    )

    @Test
    fun `every option of the request reaches the target`() {
        val target = request.toTarget()

        assertEquals("rds.example.com", target.host)
        assertEquals(3390, target.port)
        assertEquals(2880, target.desktopWidth)
        assertEquals(1800, target.desktopHeight)
        assertEquals("SKERRY", target.clientName)
        assertEquals("tsv://farm", target.loadBalanceInfo)
        assertEquals("hdmi-0", target.audioDeviceId)
        assertEquals(0x419, target.keyboardLayout)
        assertEquals(RdpImageQuality.High, target.imageQuality)
        assertEquals(RdpH264Mode.Avc420, target.h264)
        // Non-default in the fixture on purpose: a dropped mapping reads back the default.
        assertEquals(true, target.audioOutput)
        assertFalse(target.clipboard)
        assertFalse(target.graphicsPipeline)
        assertFalse(target.remoteFx)
        assertEquals(1.5f, target.displayScale)
    }

    @Test
    fun `the domain travels in the credentials, not in the user name`() {
        val credentials = request.toCredentials()

        assertEquals("ann", credentials.username)
        assertEquals("CORP", credentials.domain)
        assertEquals("secret", credentials.password)
    }

    @Test
    fun `the session every graph opens is dialled with the request as it stands`() = runTest {
        // The one factory the desktop, mobile and Android graphs share. When each of them spelled
        // this out, the copy that ships on a device was the one a field went missing on.
        val dialled = assertFailsWith<Dialled> { rdpSessionFactory(RefusingRdpTransport)(request) }

        assertEquals(2880 to 1800, dialled.target.desktopWidth to dialled.target.desktopHeight)
        assertEquals(1.5f, dialled.target.displayScale)
        assertEquals("ann", dialled.credentials.username)
    }
}

/** What the transport was asked to dial, raised instead of building a session no test would drive. */
private class Dialled(val target: RdpTarget, val credentials: RdpCredentials) : Exception()

private object RefusingRdpTransport : RdpTransport {
    override suspend fun connect(target: RdpTarget, credentials: RdpCredentials): RdpSession =
        throw Dialled(target, credentials)
}
