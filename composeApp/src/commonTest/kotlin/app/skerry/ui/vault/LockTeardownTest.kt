package app.skerry.ui.vault

import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import app.skerry.ui.connection.KeyboardInteractivePromptController
import app.skerry.ui.runbook.FakeTerminal
import app.skerry.ui.runbook.POLL
import app.skerry.ui.runbook.RUN_ID
import app.skerry.ui.runbook.RunbookRunner
import app.skerry.ui.runbook.environment
import app.skerry.ui.runbook.runbook
import app.skerry.ui.runbook.startNow
import app.skerry.ui.runbook.step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        try {
            runbooks.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
            val answering = async { prompts.responder.respond(challenge()) }
            yield()
            assertNotNull(prompts.pending.value, "the prompt should be showing before the lock")

            val failure = assertFailsWith<IllegalStateException> {
                tearDownForLock(
                    tunnels = null,
                    sessions = null,
                    sync = null,
                    snippets = null,
                    runbooks = runbooks,
                    keyboardInteractive = prompts,
                )
            }

            assertEquals("history log is full", failure.message, "the caller must still hear the failure")
            assertNull(runbooks.run, "the run's resolved values outlived a report that failed to write")
            // No time is advanced here on purpose: the prompt has a timeout of its own, and letting
            // the clock run would let that timeout stand in for the cancellation being tested.
            yield()
            assertNull(prompts.pending.value, "the prompt after the failing step was never cancelled")
            assertNull(answering.await(), "a cancelled prompt must abort authentication, not answer")
        } finally {
            runbooks.close()
            scope.cancel()
        }
    }
}
