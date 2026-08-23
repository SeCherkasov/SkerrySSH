package app.skerry.android

import app.skerry.shared.rdp.RdpCredentials
import app.skerry.shared.rdp.RdpSession
import app.skerry.shared.rdp.RdpTarget
import app.skerry.shared.rdp.RdpTransport
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.remote.RdpConnectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What the process-lived session graph dials RDP with. This is the graph that ships on a device, and
 * it used to spell the target out by hand: a field added to [RdpTarget] reached desktop and the
 * mobile preview and silently missed this one, leaving the feature inert on the only build users
 * run. It now delegates to `rdpSessionFactory`, and this pins that it keeps doing so.
 */
class KeepAliveRuntimeRdpTest {

    private class CapturingRdpTransport : RdpTransport {
        var target: RdpTarget? = null
        var credentials: RdpCredentials? = null

        // Throwing is the whole session: the controller records the failure and the test reads what
        // was dialled. Standing up a live RdpSession would test the fake, not the wiring.
        override suspend fun connect(target: RdpTarget, credentials: RdpCredentials): RdpSession {
            this.target = target
            this.credentials = credentials
            error("dialled")
        }
    }

    private object UnusedSshTransport : SshTransport {
        override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
            error("an RDP session must not open an SSH connection")
    }

    @BeforeTest
    fun setUp() {
        // The graph's scope is Main.immediate (see KeepAliveRuntime.scope); a host JVM has no Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        KeepAliveRuntime.deps = null
        Dispatchers.resetMain()
    }

    @Test
    fun `the keep-alive graph dials with the request as it stands, display scaling included`() {
        val rdp = CapturingRdpTransport()
        KeepAliveRuntime.deps = KeepAliveRuntime.GraphDeps(
            transport = UnusedSshTransport,
            vncTransport = null,
            rdpTransport = rdp,
            vault = null,
            teams = null,
        )

        KeepAliveRuntime.sessionsController().openRdp(
            hostId = null,
            title = "host",
            subtitle = "10.0.0.1",
            request = RdpConnectRequest(
                host = "10.0.0.1",
                port = 3390,
                username = "CORP\\ann",
                password = "pw",
                width = 1080,
                height = 2340,
                clientName = "Skerry",
                displayScale = 3f,
            ),
        )

        val target = assertNotNull(rdp.target, "the keep-alive graph opened no RDP session at all")
        assertEquals(3f, target.displayScale, "the display scaling never left the phone")
        assertEquals(1080 to 2340, target.desktopWidth to target.desktopHeight)
        assertEquals(3390, target.port)
        assertEquals("ann", rdp.credentials?.username)
        assertEquals("CORP", rdp.credentials?.domain)
    }
}
