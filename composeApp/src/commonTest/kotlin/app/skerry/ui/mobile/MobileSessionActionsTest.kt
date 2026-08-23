package app.skerry.ui.mobile

import androidx.compose.ui.unit.IntSize
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RdpConnectRequest
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.remote.RemoteViewport
import app.skerry.ui.session.SessionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** What the mobile chrome dials an RDP session with, which is fixed for the life of the session. */
class MobileSessionActionsTest {

    private val host = Host(
        id = "h1",
        label = "rds",
        address = "rds.example.com",
        port = 3389,
        username = "ann",
        connectionType = ConnectionType.RDP,
    )

    @Test
    fun `the session is dialled at the screen's pixels and the scaling they are drawn at`() = runTest {
        // RDP fixes the desktop size at connect time, so a phone that reported its pixels without
        // their scaling would be stuck with a 96 dpi desktop for the whole session.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var dialled: RdpConnectRequest? = null
        val sessions = SessionsController(
            newId = { "s1" },
            controllerFactory = { ConnectionController(IdleSshTransport, scope) },
            vncControllerFactory = { RemoteDesktopController(scope) },
            openRdpSession = { request ->
                dialled = request
                FakeRemoteDesktop()
            },
        )

        openMobileRdp(
            sessions,
            MobileDesignState(),
            hostManager = null,
            host = host,
            request = mobileRdpRequest(host, "secret", RemoteViewport(IntSize(1080, 2340), 3f)),
        )

        val request = assertNotNull(dialled, "no RDP session was dialled")
        assertEquals(1080 to 2340, request.width to request.height)
        assertEquals(3f, request.displayScale)
        scope.cancel()
    }
}

/** The idle terminal controller an RDP tab still carries never connects. */
private object IdleSshTransport : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        throw UnsupportedOperationException("an RDP tab does not open an SSH connection")
}
