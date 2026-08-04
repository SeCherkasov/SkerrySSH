package app.skerry.ui.runbook

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.connection.FakeShellChannel
import app.skerry.ui.connection.FakeSshConnection
import app.skerry.ui.connection.FakeSshTransport
import app.skerry.ui.connection.controllerWith
import app.skerry.ui.connection.testTarget
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The wiring between a session and a transfer step. Without it every
 * [app.skerry.shared.runbook.RunbookStep.Transfer] would fail as
 * [RunbookStepFailure.NoSftpChannel] on a perfectly good SSH session — a silent hole the runner's
 * own tests can't see, because they fake the channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookSftpOpenerTest {

    @Test
    fun `an ssh session hands the runner a way to open sftp`() = runTest {
        val sftp = FakeSftpClient()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel(), sftp = sftp)))
        controller.connect(testTarget, SshAuth.Password("pw"))
        advanceUntilIdle()

        val opener = assertNotNull(sftpOpener(controller))
        assertNotNull(opener())

        scope.cancel()
    }

    @Test
    fun `a stream-only session hands it nothing, so the step fails instead of throwing`() = runTest {
        // Local shell, Mosh, Telnet, serial and container exec have no SFTP channel at all.
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        controller.connect(testTarget.copy(connectionType = ConnectionType.LOCAL), SshAuth.Password(""))
        advanceUntilIdle()

        assertNull(sftpOpener(controller))

        scope.cancel()
    }
}
