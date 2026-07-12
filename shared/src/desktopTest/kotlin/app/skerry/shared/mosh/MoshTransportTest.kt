package app.skerry.shared.mosh

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class FakeSshConnection(
    private val execResults: MutableList<ExecResult>,
) : SshConnection {
    val execCommands = mutableListOf<String>()
    var disconnected = false

    override val isConnected: Boolean get() = !disconnected

    override suspend fun exec(command: String): ExecResult {
        execCommands += command
        return execResults.removeAt(0)
    }

    override suspend fun openShell(size: PtySize, term: String): ShellChannel =
        throw UnsupportedOperationException("not used in bootstrap tests")

    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward =
        throw UnsupportedOperationException()

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward =
        throw UnsupportedOperationException()

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward =
        throw UnsupportedOperationException()

    override suspend fun disconnect() {
        disconnected = true
    }
}

private class FakeSshTransport(private val connection: FakeSshConnection) : SshTransport {
    var lastTarget: SshTarget? = null

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        lastTarget = target
        return connection
    }
}

private val AUTH = SshAuth.Password("secret")

private fun target() = SshTarget(
    host = "example.com",
    port = 22,
    username = "dev",
    connectionType = ConnectionType.MOSH,
)

private fun connectLine(port: Int = 60001) =
    ExecResult(exitCode = 0, stdout = "MOSH CONNECT $port zM7RhBUAAcTLKwZTHYzGaw", stderr = "")

class MoshTransportTest {

    @Test
    fun `bootstrap launches mosh-server over ssh and drops the ssh leg`() = runBlocking {
        val conn = FakeSshConnection(mutableListOf(connectLine()))
        val ssh = FakeSshTransport(conn)
        val result = MoshTransport(ssh).connect(target(), AUTH)
        assertEquals(
            "mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -l LC_ALL=en_US.UTF-8",
            conn.execCommands.single(),
        )
        // The SSH hop authenticates as plain SSH (auth/jump/host keys), not as MOSH.
        assertEquals(ConnectionType.SSH, ssh.lastTarget?.connectionType)
        assertTrue(conn.disconnected, "SSH must be released once mosh-server is up")
        assertEquals("aes128-ocb@mosh", result.cipher)
        assertTrue(result.isConnected)
    }

    @Test
    fun `missing mosh-server maps to the not-installed reason and still drops ssh`() =
        runBlocking {
            val conn = FakeSshConnection(
                mutableListOf(ExecResult(127, "", "bash: mosh-server: command not found")),
            )
            val e = assertFailsWith<MoshSetupException> {
                MoshTransport(FakeSshTransport(conn)).connect(target(), AUTH)
            }
            assertEquals(MoshSetupException.Reason.SERVER_NOT_INSTALLED, e.reason)
            assertTrue(conn.disconnected)
        }

    @Test
    fun `locale failure retries once with C UTF-8`() = runBlocking {
        val localeError = ExecResult(
            exitCode = 1,
            stdout = "",
            stderr = "mosh-server needs a UTF-8 native locale to run.",
        )
        val conn = FakeSshConnection(mutableListOf(localeError, connectLine()))
        MoshTransport(FakeSshTransport(conn)).connect(target(), AUTH)
        assertEquals(2, conn.execCommands.size)
        assertTrue(conn.execCommands[1].contains("-l LANG=C.UTF-8"))
    }

    @Test
    fun `locale failure on both attempts maps to the locale reason`() = runBlocking {
        val localeError = ExecResult(
            exitCode = 1,
            stdout = "",
            stderr = "mosh-server needs a UTF-8 native locale to run.",
        )
        val conn = FakeSshConnection(mutableListOf(localeError, localeError))
        val e = assertFailsWith<MoshSetupException> {
            MoshTransport(FakeSshTransport(conn)).connect(target(), AUTH)
        }
        assertEquals(MoshSetupException.Reason.LOCALE_UNSUPPORTED, e.reason)
    }

    @Test
    fun `any other launch failure carries the server output as detail`() = runBlocking {
        val conn = FakeSshConnection(
            mutableListOf(ExecResult(1, "", "mosh-server: Network is unreachable")),
        )
        val e = assertFailsWith<MoshSetupException> {
            MoshTransport(FakeSshTransport(conn)).connect(target(), AUTH)
        }
        assertEquals(MoshSetupException.Reason.BOOTSTRAP_FAILED, e.reason)
        assertEquals("mosh-server: Network is unreachable", e.detail)
    }
}
