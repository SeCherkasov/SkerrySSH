@file:OptIn(ExperimentalCoroutinesApi::class)

package app.skerry.ui.connection

import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.mosh.MoshSetupException
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.HostKeyRefusal
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionControllerTest {

    @Test
    fun `a hostile connect failure reaches the form sanitised`() = runTest {
        val transport = ScriptedTransport(
            listOf(Result.failure(IllegalStateException("Access denied\u202E\nby policy"))),
        )
        val (controller, scope) = controllerWith(transport)
        controller.connect(testTarget, SshAuth.Password("pw"))
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Error>(st)
        // The first connect fails through the same transport call the reconnect loop uses, so the
        // server's reason is just as untrusted here — bidi override dropped, newline folded.
        assertEquals("Access denied by policy", st.message)
        scope.cancel()
    }

    @Test
    fun `starts in Form state`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `connect transitions to Connected and streams shell output`() = runTest {
        val channel = FakeShellChannel()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(channel)))

        controller.connect(testTarget, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Connected>(state)
        channel.emit("hi".encodeToByteArray())
        assertEquals("hi", state.terminal.output)
        scope.cancel()
    }

    @Test
    fun `connect invokes onConnected once with the live terminal`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        var calls = 0
        var received: Any? = null

        controller.connect(testTarget, SshAuth.Password("pw")) { t -> calls++; received = t }

        val state = controller.uiState
        assertIs<ConnectionUiState.Connected>(state)
        assertEquals(1, calls)
        assertSame(state.terminal, received)
        scope.cancel()
    }

    @Test
    fun `connect failure transitions to Error with message`() = runTest {
        val transport = FakeSshTransport(error = SshAuthenticationException("access denied"))
        val (controller, scope) = controllerWith(transport)

        controller.connect(testTarget, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals("access denied", state.message)
        assertNull(state.moshReason)
        scope.cancel()
    }

    @Test
    fun `mosh setup failure carries the typed reason into Error`() = runTest {
        val transport = FakeSshTransport(
            error = MoshSetupException(
                reason = MoshSetupException.Reason.SERVER_NOT_INSTALLED,
                message = "mosh-server was not found",
            ),
        )
        val (controller, scope) = controllerWith(transport)

        controller.connect(testTarget, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals(MoshSetupException.Reason.SERVER_NOT_INSTALLED, state.moshReason)
        scope.cancel()
    }

    @Test
    fun `a rejected host key carries its reason into Error`() = runTest {
        // The English transport text is diagnostics; what the view needs to explain the refusal in
        // the user's language is the typed reason, same rule as the Mosh case above.
        val transport = FakeSshTransport(
            error = SshHostKeyRejectedException("Host key rejected by verifier", HostKeyRefusal.KeyChanged),
        )
        val (controller, scope) = controllerWith(transport)

        controller.connect(testTarget, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals(HostKeyRefusal.KeyChanged, state.hostKeyRefusal)
        scope.cancel()
    }

    @Test
    fun `connect shows Connecting while in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel()), gate = gate))

        controller.connect(testTarget, SshAuth.Password("pw"))
        assertEquals(ConnectionUiState.Connecting, controller.uiState)

        gate.complete(Unit)
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        scope.cancel()
    }

    @Test
    fun `disconnect returns to Form and disconnects connection`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.disconnect()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        assertTrue(conn.disconnected)
        scope.cancel()
    }

    @Test
    fun `losing the shell transitions Connected to Disconnected keeping the frozen terminal`() = runTest {
        val channel = FakeShellChannel()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(channel)))
        controller.connect(testTarget, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        // Server closed the channel / transport drop (not our disconnect): no EOF ends output collection.
        channel.close()

        val lost = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(lost)
        // The screen freezes at the moment of loss — it's the same TerminalScreenState as Connected had.
        assertSame(connected.terminal, lost.terminal)
        scope.cancel()
    }

    @Test
    fun `our disconnect goes to Form and never flips to Disconnected on channel close`() = runTest {
        val channel = FakeShellChannel()
        val conn = FakeSshConnection(channel)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.disconnect() // cancels session-scope before Closed would have been observed
        channel.close()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `container session keeps the profile's keep-alive cadence`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(
            testTarget.copy(
                connectionType = ConnectionType.CONTAINER,
                container = app.skerry.shared.container.ContainerSpec(target = "web"),
                keepAliveSeconds = 30,
            ),
            SshAuth.Password("pw"),
        )
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        assertEquals(1, conn.roundTrips) // the SSH leg still needs keepalives behind a NAT
        advanceTimeBy(65_000)
        assertEquals(3, conn.roundTrips)
        scope.cancel()
    }

    @Test
    fun `connect is a no-op while already connected`() = runTest {
        val firstChannel = FakeShellChannel()
        val conn = CountingSshConnection(firstChannel)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(testTarget, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        // A repeated connect from Connected must not open a second shell/session.
        controller.connect(testTarget, SshAuth.Password("pw"))

        assertEquals(connected, controller.uiState)
        assertEquals(1, conn.openShellCalls)
        scope.cancel()
    }

    @Test
    fun `openSftp opens a channel on the live connection`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        val opened = controller.openSftp()

        assertSame(sftp, opened)
        scope.cancel()
    }

    @Test
    fun `openSftp without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openSftp() }
        scope.cancel()
    }

    @Test
    fun `openTransferCoordinator caches one coordinator and opens a single channel`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Cached per connection: the dual-pane SFTP survives view switches, so a repeated
        // call returns the same coordinator and doesn't open a second channel.
        val first = controller.openTransferCoordinator(FakeFileBrowser(), "host")
        val second = controller.openTransferCoordinator(FakeFileBrowser(), "host")

        assertSame(first, second)
        assertEquals(1, conn.openSftpCalls)
        scope.cancel()
    }

    @Test
    fun `openTransferCoordinator without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openTransferCoordinator(FakeFileBrowser(), "host") }
        scope.cancel()
    }

    @Test
    fun `disconnect closes the opened sftp channel`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        controller.openTransferCoordinator(FakeFileBrowser(), "host")
        assertTrue(!sftp.closed)

        controller.disconnect()

        assertTrue(sftp.closed)
        scope.cancel()
    }

    @Test
    fun `openPortForwards returns the same controller for one session`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Cached per connection: tunnels must survive UI tab switches, so every
        // openPortForwards call returns the same controller instead of a new one.
        val first = controller.openPortForwards()
        val second = controller.openPortForwards()

        assertSame(first, second)
        scope.cancel()
    }

    @Test
    fun `openPortForwards without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openPortForwards() }
        scope.cancel()
    }

    @Test
    fun `openMetrics caches one controller for one session`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Cached per connection: the info panel survives tab switches, a repeated call returns
        // the same controller (and the same polling job) instead of spinning up a second one.
        val first = controller.openMetrics()
        val second = controller.openMetrics()

        assertSame(first, second)
        controller.disconnect()
        scope.cancel()
    }

    @Test
    fun `disconnect stops the metrics poller`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        controller.openMetrics()
        testScheduler.advanceTimeBy(1)
        val pollsWhileConnected = conn.execCalls

        controller.disconnect()
        testScheduler.advanceTimeBy(30_000)

        // The poller lives on the session scope: dropping the session must end the round-trips,
        // not leave a loop polling a dead connection.
        assertEquals(pollsWhileConnected, conn.execCalls)
        scope.cancel()
    }

    @Test
    fun `openMetrics without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openMetrics() }
        scope.cancel()
    }

    // Keep-alive: SshTarget.keepAliveSeconds > 0 starts a keepalive ping loop with the session
    // itself (not lazily from the status bar), so an idle session behind a NAT stays alive.
    // These tests advance virtual time explicitly (advanceTimeBy) — advanceUntilIdle would never
    // return with a periodic loop running.

    @Test
    fun `keep-alive pings from connect at the target interval`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(testTarget.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        assertEquals(1, conn.roundTrips) // first ping fires immediately on connect
        advanceTimeBy(65_000) // two more cycles land at t=30s and t=60s
        assertEquals(3, conn.roundTrips)
        scope.cancel()
    }

    @Test
    fun `keep-alive off never pings and openPing exposes nothing`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(testTarget, SshAuth.Password("pw")) // default keepAliveSeconds = 0
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        advanceTimeBy(120_000)
        assertEquals(0, conn.roundTrips)
        assertNull(controller.openPing()) // no poller -> no RTT for the status bar
        scope.cancel()
    }

    @Test
    fun `openPing exposes the running keep-alive poller with its RTT`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(testTarget.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))

        val ping = controller.openPing()
        assertNotNull(ping)
        assertEquals(7L, ping.rttMs) // published by the immediate first ping
        assertSame(ping, controller.openPing())
        scope.cancel()
    }

    @Test
    fun `disconnect stops keep-alive pings`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        advanceTimeBy(35_000)
        assertEquals(2, conn.roundTrips)

        controller.disconnect()
        advanceTimeBy(120_000)

        assertEquals(2, conn.roundTrips)
        scope.cancel()
    }

    @Test
    fun `disconnect stops the port-forward telemetry poller`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(testTarget, SshAuth.Password("pw"))
        controller.openPortForwards()

        controller.disconnect()

        // The forward controller polls on the shared controller scope (it outlives the session), so
        // disconnect must cancel the poll job — otherwise every closed session leaks a live loop.
        // Check before cancel, but cancel unconditionally: a leaked poller re-schedules forever and
        // would hang runTest's idle-wait.
        val leaked = scope.coroutineContext[Job]!!.children.any { it.isActive }
        scope.cancel()
        assertFalse(leaked)
    }

    @Test
    fun `a throwing onConnected action stops the started keep-alive`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(testTarget.copy(keepAliveSeconds = 30), SshAuth.Password("pw")) { error("boom") }

        assertIs<ConnectionUiState.Error>(controller.uiState)
        assertTrue(conn.disconnected) // full session teardown, not just the ping loop
        val before = conn.roundTrips
        advanceTimeBy(120_000)
        assertEquals(before, conn.roundTrips) // loop died with the failed session
        assertNull(controller.openPing())
        scope.cancel()
    }

    @Test
    fun `only an ssh session reports sftp support`() = runTest {
        val (ssh, sshScope) = controllerWith(ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel())))))
        assertFalse(ssh.supportsSftp) // nothing connected yet
        ssh.connect(testTarget, SshAuth.Password("pw"))
        assertTrue(ssh.supportsSftp)
        sshScope.cancel()

        val (local, localScope) = controllerWith(ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel())))))
        // Local shell / Mosh / Telnet / serial / container are stream-only: openSftp would throw.
        local.connect(testTarget.copy(connectionType = ConnectionType.LOCAL), SshAuth.Password(""))
        assertFalse(local.supportsSftp)
        localScope.cancel()
    }

    @Test
    fun `a reveal request is delivered once`() = runTest {
        val (controller, scope) = controllerWith(ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel())))))

        assertNull(controller.takeRevealRequest())
        controller.requestReveal("/var/log/syslog")
        assertEquals("/var/log/syslog", controller.pendingRevealPath)
        assertEquals("/var/log/syslog", controller.takeRevealRequest())
        // Taken means gone: reopening the file view must not jump back to an old path.
        assertNull(controller.takeRevealRequest())
        assertNull(controller.pendingRevealPath)
        scope.cancel()
    }

    @Test
    fun `a newer reveal request replaces a pending one`() = runTest {
        val (controller, scope) = controllerWith(ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel())))))

        controller.requestReveal("/etc/hosts")
        controller.requestReveal("/var/log/syslog")
        assertEquals("/var/log/syslog", controller.takeRevealRequest())
        scope.cancel()
    }

}

/** SFTP client stub: only object identity and the closed flag matter. */
private class RecordingSftpClient : SftpClient {
    var closed = false
        private set

    override suspend fun list(path: String): List<SftpEntry> = emptyList()
    override suspend fun stat(path: String): SftpEntry? = null
    override suspend fun realpath(path: String): String = "/"
    override suspend fun read(path: String, maxBytes: Long): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) = Unit
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) = Unit
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) = Unit
    override suspend fun mkdir(path: String) = Unit
    override suspend fun remove(path: String) = Unit
    override suspend fun rmdir(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun close() {
        closed = true
    }
}

/** Local file browser stub for the coordinator's left pane: only identity matters. */
private class FakeFileBrowser : FileContentBrowser {
    override val label: String = "local"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun stat(path: String): FileItem? = null
    override suspend fun readFile(path: String, maxBytes: Long): ByteArray = ByteArray(0)
    override suspend fun writeFile(path: String, data: ByteArray) = Unit
}

/** Counts openShell calls — verifies a repeated connect doesn't open a second shell. */
private class CountingSshConnection(private val channel: ShellChannel) : SshConnection {
    var openShellCalls = 0
        private set

    override val isConnected: Boolean = true
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        openShellCalls++
        return channel
    }

    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()

    override suspend fun disconnect() {}
}
