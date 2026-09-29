package app.skerry.ui.vnc

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.skerry.shared.rdp.RdpConnectStage
import app.skerry.shared.rdp.RdpDrop
import app.skerry.ui.remote.RemoteDesktopUiState
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The line under a failed connect's reason: the step and, when the network ended it, how. It is the
 * whole point of the stage plumbing, so the text itself is pinned — and the screen-reader
 * announcement with it, which reads the same string.
 */
@OptIn(ExperimentalTestApi::class)
class RdpStageTextTest {

    private lateinit var saved: Locale

    @BeforeTest
    fun english() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restore() {
        Locale.setDefault(saved)
    }

    private fun render(ui: RemoteDesktopUiState.Error): Pair<String, String> {
        var shown = ""
        var announced = ""
        runComposeUiTest {
            setContent {
                shown = remoteDesktopErrorText(ui)
                announced = remoteDesktopAnnouncement(ui)
            }
            waitForIdle()
        }
        return shown to announced
    }

    @Test
    fun `no step leaves the reason alone`() {
        val (shown, announced) = render(RemoteDesktopUiState.Error(VncFailure.Other))

        assertEquals("Failed to connect.", shown)
        assertEquals(shown, announced)
    }

    @Test
    fun `a step the server ended with a reason names only the step`() {
        val (shown, _) = render(RemoteDesktopUiState.Error(VncFailure.RdpCredentials, stage = RdpConnectStage.Nla))

        assertEquals("Step: NLA (CredSSP).", shown.lines().last())
    }

    @Test
    fun `a step the network ended names the step and how`() {
        val (shown, announced) = render(
            RemoteDesktopUiState.Error(VncFailure.Other, stage = RdpConnectStage.Tls, drop = RdpDrop.Closed),
        )

        assertEquals("Failed to connect.\nStep: TLS handshake. The server closed the connection.", shown)
        assertEquals(shown, announced)
    }
}
