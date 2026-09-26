package app.skerry.shared.jumpshell

import app.skerry.shared.ssh.CountingTransport
import app.skerry.shared.ssh.FakeShell
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.SharedConnectionPool
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.carriesSftp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A destination reached by typing `ssh` at a jump host's prompt: the line that is typed, when it is
 * typed (only at a settled prompt, once, never over the person), and what the session refuses.
 */
class JumpShellTest {

    @Test
    fun `the typed line names port and user only when they differ from the defaults`() {
        assertEquals("ssh db.internal", JumpShellSpec("db.internal").loginCommand())
        assertEquals("ssh -p 2222 ops@10.0.0.5", JumpShellSpec("10.0.0.5", 2222, "ops").loginCommand())
        assertEquals("ssh fe80::1", JumpShellSpec(" fe80::1 ", username = " ").loginCommand())
    }

    @Test
    fun `an address the shell would read as more than an address is refused`() {
        listOf("db; rm -rf ~", "db\$(id)", "db`id`", "db|sh", "-oProxyCommand=sh", "db name", "", "db\nid").forEach {
            assertNull(JumpShellSpec(it).loginCommand(), it)
        }
        assertNull(JumpShellSpec("db", username = "-l").loginCommand())
        assertNull(JumpShellSpec("db", username = "a;b").loginCommand())
        assertNull(JumpShellSpec("db", port = 0).loginCommand())
    }

    @Test
    fun `the command is typed once the prompt settles`() = runTest {
        val (channel, shell) = launched()
        shell.emit("Last login: Mon\r\n\u001b[1;32mme@jump\u001b[0m:~$ ")
        runCurrent()
        assertTrue(shell.written.isEmpty())
        advanceTimeBy(QUIET + 1)
        assertEquals(listOf("ssh db.internal\r"), shell.written)
        // The destination's own prompt later on is not the jump host's.
        shell.emit("\r\nroot@db:~# ")
        advanceTimeBy(QUIET * 3)
        assertEquals(1, shell.written.size)
        channel.close()
    }

    @Test
    fun `nothing is typed while the jump host is still asking`() = runTest {
        val (_, shell) = launched()
        shell.emit("Open https://sso.example/device?code=ABCD and press Enter >")
        advanceTimeBy(QUIET * 3)
        assertTrue(shell.written.isEmpty())
    }

    @Test
    fun `output arriving within the quiet period postpones the command`() = runTest {
        val (_, shell) = launched()
        shell.emit("me@jump:~$ ")
        advanceTimeBy(QUIET / 2)
        shell.emit("\r\nwelcome back\r\n")
        advanceTimeBy(QUIET * 3)
        assertTrue(shell.written.isEmpty())
        shell.emit("me@jump:~$ ")
        advanceTimeBy(QUIET + 1)
        assertEquals(listOf("ssh db.internal\r"), shell.written)
    }

    @Test
    fun `the Enter a login script waits for does not cancel the launch`() = runTest {
        val (channel, shell) = launched()
        shell.emit("Log in at the link above, then press Enter")
        runCurrent()
        channel.write("\r".encodeToByteArray())
        shell.emit("\r\nme@jump:~$ ")
        advanceTimeBy(QUIET + 1)
        assertEquals(listOf("\r", "ssh db.internal\r"), shell.written)
    }

    @Test
    fun `typing at the prompt hands the session to the person`() = runTest {
        val (channel, shell) = launched()
        shell.emit("me@jump:~$ ")
        runCurrent()
        channel.write("l".encodeToByteArray())
        advanceTimeBy(QUIET * 3)
        assertEquals(listOf("l"), shell.written)
    }

    @Test
    fun `the terminal answering a query does not count as the person typing`() = runTest {
        val (channel, shell) = launched()
        shell.emit("me@jump:~$ ")
        runCurrent()
        channel.write("\u001b[?1;2c".encodeToByteArray())
        advanceTimeBy(QUIET + 1)
        assertEquals(listOf("\u001b[?1;2c", "ssh db.internal\r"), shell.written)
    }

    @Test
    fun `a shell that closes before settling types nothing`() = runTest {
        val (_, shell) = launched()
        shell.emit("me@jump:~$ ")
        runCurrent()
        shell.end()
        advanceTimeBy(QUIET * 3)
        assertTrue(shell.written.isEmpty())
    }

    @Test
    fun `a write that fails under the command does not break the session's output`() = runTest {
        val shell = FakeShell().apply { failWrites = true }
        val channel = PromptLaunchChannel(shell, "ssh db.internal", QUIET)
        val collected = backgroundScope.launch { channel.output.toList() }
        shell.emit("me@jump:~$ ")
        advanceTimeBy(QUIET + 1)
        shell.end()
        runCurrent()
        assertTrue(collected.isCompleted)
    }

    @Test
    fun `sessions behind one jump host share its connection and stay terminal-only`() = runTest {
        val transport = CountingTransport()
        val jump = JumpShellTransport(SharedConnectionPool(transport, backgroundScope), enabled = { true })
        val first = jump.connect(target("db1.internal"), SshAuth.Password("not the jump host's"))
        val second = jump.connect(target("db2.internal"), SshAuth.Password("not the jump host's"))
        assertEquals(1, transport.dials)
        val dialed = transport.lastTarget!!
        assertFalse(dialed.jumpShell)
        assertEquals("jump.example", dialed.host)
        assertEquals("me", dialed.username)
        assertEquals("outer.example", dialed.jump?.host)
        assertEquals(SshAuth.Interactive, transport.lastAuth)
        assertTrue(first.isConnected)
        assertEquals("aes256-gcm@openssh.com", second.cipher)
        assertFailsWith<UnsupportedOperationException> { first.openSftp() }
        assertFailsWith<UnsupportedOperationException> { first.exec("id") }
        assertFailsWith<UnsupportedOperationException> {
            first.forwardLocal(LocalForwardSpec(bindPort = 0, destHost = "h", destPort = 1))
        }
        assertFalse(target("db1.internal").carriesSftp)
    }

    @Test
    fun `a destination that is not an address fails before anything is dialed`() = runTest {
        val transport = CountingTransport()
        val jump = JumpShellTransport(SharedConnectionPool(transport, backgroundScope), enabled = { true })
        val refused = assertFailsWith<JumpShellRefusedException> { jump.connect(target("db;id"), SshAuth.Interactive) }
        assertEquals(JumpShellRefusedException.Reason.NOT_AN_ADDRESS, refused.reason)
        assertEquals(0, transport.dials)
    }

    @Test
    fun `a target without a jump host fails before anything is dialed`() = runTest {
        val transport = CountingTransport()
        val jump = JumpShellTransport(SharedConnectionPool(transport, backgroundScope), enabled = { true })
        assertFailsWith<SshConnectionException> {
            jump.connect(target("db.internal").copy(jump = null), SshAuth.Interactive)
        }
        assertEquals(0, transport.dials)
    }

    @Test
    fun `a question ending in an angle bracket is not taken for a prompt`() = runTest {
        val (_, shell) = launched()
        shell.emit("Select a method [1-3]> ")
        advanceTimeBy(QUIET * 3)
        assertTrue(shell.written.isEmpty())
    }

    @Test
    fun `a prompt after a very long line is still found`() = runTest {
        val (_, shell) = launched()
        shell.emit("x".repeat(100_000))
        shell.emit("\r\nme@jump:~$ ")
        advanceTimeBy(QUIET + 1)
        assertEquals(listOf("ssh db.internal\r"), shell.written)
    }

    @Test
    fun `a url split across reads still keeps the line from being a prompt`() = runTest {
        val (_, shell) = launched()
        shell.emit("Visit https:/")
        shell.emit("/sso.example/x then come back $ ")
        advanceTimeBy(QUIET * 3)
        assertTrue(shell.written.isEmpty())
    }

    @Test
    fun `with the setting off a profile carrying the mode is refused before anything is dialed`() = runTest {
        val transport = CountingTransport()
        val jump = JumpShellTransport(SharedConnectionPool(transport, backgroundScope), enabled = { false })
        val refused = assertFailsWith<JumpShellRefusedException> { jump.connect(target("db.internal"), SshAuth.Interactive) }
        assertEquals(JumpShellRefusedException.Reason.DISABLED, refused.reason)
        assertEquals(0, transport.dials)
    }

    private fun target(destination: String) = SshTarget(
        host = destination,
        username = "",
        jump = SshJump(
            host = "jump.example",
            username = "me",
            auth = SshAuth.Interactive,
            jump = SshJump(host = "outer.example", username = "me", auth = SshAuth.Password("outer")),
        ),
        jumpShell = true,
    )

    private fun TestScope.launched(): Pair<PromptLaunchChannel, FakeShell> {
        val shell = FakeShell()
        val channel = PromptLaunchChannel(shell, "ssh db.internal", QUIET)
        backgroundScope.launch { channel.output.collect { } }
        return channel to shell
    }

    private companion object {
        const val QUIET = 400L
    }
}
