package app.skerry.ui.vnc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_error_cert_rejected
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.RemoteDesktopUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * A remote-desktop session that ends on its own swaps the picture for a line of text. Sighted, that
 * is the whole story; without sight the surface simply goes quiet — and the line the user most needs
 * is the one saying their answer to the certificate question is why nothing connected (WCAG 4.1.3).
 */
@OptIn(ExperimentalTestApi::class)
class RemoteDesktopAnnouncementTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `a failure and a drop are announced, and connecting is not`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var ui: RemoteDesktopUiState by mutableStateOf(RemoteDesktopUiState.Connecting)
        try {
            runForm({ StatusAnnouncer(remoteDesktopAnnouncement(ui)) }) {
                // Connecting follows a keystroke the user just made: saying so is noise.
                onNode(polite).assert(hasContentDescription(""))

                ui = RemoteDesktopUiState.Error(VncFailure.CertificateRejected)
                waitForIdle()
                onNode(polite).assert(hasContentDescription(string(Res.string.vnc_error_cert_rejected)))

                ui = RemoteDesktopUiState.Disconnected(
                    screen = RemoteDesktopScreenState(FakeRemoteDesktop(), scope),
                    cleanExit = false,
                )
                waitForIdle()
                onNode(polite).assert(hasContentDescription(string(Res.string.vnc_connection_lost)))
            }
        } finally {
            scope.cancel()
        }
    }
}
