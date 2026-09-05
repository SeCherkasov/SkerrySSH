package app.skerry.ui.vault

import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.terminal.TerminalSessionPrefs
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.FakeShellChannel
import app.skerry.ui.connection.FakeSshConnection
import app.skerry.ui.connection.FakeSshTransport
import app.skerry.ui.session.SessionsController
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.ui.connection.KeyboardInteractivePromptController
import app.skerry.ui.runbook.FakeTerminal
import app.skerry.ui.runbook.POLL
import app.skerry.ui.runbook.RUN_ID
import app.skerry.ui.runbook.RunbookRunner
import app.skerry.ui.runbook.environment
import app.skerry.ui.runbook.runbook
import app.skerry.ui.runbook.startNow
import app.skerry.ui.runbook.step
import app.skerry.ui.trust.HostTrustPromptController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hostKey() = HostTrustRequest(
    kind = HostTrustKind.SshHostKey,
    host = "desk",
    port = 22,
    keyType = "ssh-ed25519",
    fingerprint = "SHA256:aaaa",
)

private fun challenge() = KeyboardInteractiveChallenge(
    name = "Two-factor authentication",
    instruction = "",
    prompts = listOf(KeyboardInteractivePrompt("Verification code:", echo = false)),
)

/**
 * What the lock owes when one of its cleanups fails. Each step here drops a different secret and
 * they are independent, so the failure of one may not become an excuse to keep the rest — while the
 * caller still has to hear that something went wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockTeardownTest {

    @Test
    fun `a cleanup that throws does not cancel the ones after it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val term = FakeTerminal()
        // The history log refusing the record is the realistic failure: a full disk, a read-only home.
        val runbooks = RunbookRunner(
            scope = scope,
            newId = { RUN_ID },
            environment = ::environment,
            pollIntervalMillis = POLL,
            onFinished = { throw IllegalStateException("history log is full") },
        )
        val prompts = KeyboardInteractivePromptController()
        val trust = HostTrustPromptController()
        try {
            runbooks.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
            val answering = async { prompts.responder.respond(challenge()) }
            val vouching = async { trust.confirm(hostKey()) }
            yield()
            assertNotNull(prompts.pending.value, "the prompt should be showing before the lock")
            assertNotNull(trust.pending.value, "the trust question should be showing before the lock")

            val failure = assertFailsWith<IllegalStateException> {
                tearDownForLock(
                    tunnels = null,
                    sessions = null,
                    sync = null,
                    snippets = null,
                    runbooks = runbooks,
                    keyboardInteractive = prompts,
                    hostTrust = trust,
                )
            }

            assertEquals("history log is full", failure.message, "the caller must still hear the failure")
            assertNull(runbooks.run, "the run's resolved values outlived a report that failed to write")
            // No time is advanced here on purpose: the prompt has a timeout of its own, and letting
            // the clock run would let that timeout stand in for the cancellation being tested.
            yield()
            assertNull(prompts.pending.value, "the prompt after the failing step was never cancelled")
            assertNull(answering.await(), "a cancelled prompt must abort authentication, not answer")
            // The handshake behind a trust question is held open the whole time it is on screen, and
            // the lock takes the dialog away — leaving it to its own deadline means the connection
            // fails a minute and a half later, behind the lock screen, for no reason the user can see.
            assertNull(trust.pending.value, "the trust question was left on the other side of the lock")
            assertFalse(vouching.await(), "a question nobody could answer must not trust the key")
        } finally {
            runbooks.close()
            scope.cancel()
        }
    }

    /**
     * The desktop sync-setup modal outlives the lock — its open flag is held above the vault gate — while
     * the password-replace question inside it does not. Closed here, it does not come back after the
     * unlock as a plain connect form standing where the user's question was.
     */
    @Test
    fun `the lock closes the sync setup modal it would otherwise bring back empty`() {
        var closed = false
        tearDownForLock(
            tunnels = null,
            sessions = null,
            sync = null,
            snippets = null,
            closeSyncSetup = { closed = true },
        )
        assertTrue(closed, "the modal survives the lock and would reopen with its question already answered")
    }

    /**
     * Issue #360: the lock has to take the pane's saved sudo password with it. The offer is armed
     * from a live session and the lock deliberately leaves that session open, so nothing below the
     * terminal can drop it — `tearDownForLock` reaches every pane's terminal itself. Delete that
     * call and this is the only test that notices.
     */
    @Test
    fun `locking drops a pane's saved sudo password`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val channel = FakeShellChannel()
        val sessions = SessionsController(
            newId = { "s0" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeSshTransport(FakeSshConnection(channel)),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                    terminalPrefs = { TerminalSessionPrefs(sudoPasswordEnabled = true) },
                )
            },
        )
        sessions.open(
            hostId = "h",
            title = "web",
            subtitle = "deploy@web:22",
            target = SshTarget(host = "web", port = 22, username = "deploy"),
            auth = SshAuth.Password("hunter2"),
        )
        advanceUntilIdle()
        val terminal = sessions.active!!.focusedPane.liveTerminal!!
        channel.emit("[sudo] password for deploy: ".encodeToByteArray())
        advanceUntilIdle()
        assertTrue(terminal.sudoOffer, "the offer did not arm before the lock")

        tearDownForLock(tunnels = null, sessions = sessions, sync = null, snippets = null)

        assertFalse(terminal.sudoOffer, "the offer survived the vault lock")
        terminal.typeInput("\r")
        advanceUntilIdle()
        assertEquals(listOf("\r"), channel.written, "the saved password survived the vault lock")
        scope.cancel()
    }
}
