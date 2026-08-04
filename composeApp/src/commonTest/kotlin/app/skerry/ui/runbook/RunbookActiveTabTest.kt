package app.skerry.ui.runbook

import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.FakeShellChannel
import app.skerry.ui.connection.FakeSshConnection
import app.skerry.ui.connection.FakeSshTransport
import app.skerry.ui.session.SessionsController
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which tab a run belongs to. The run screen and the mobile progress panel both ask this question,
 * and both take "no run here" as an instruction to leave — so a run that answers `null` for the tab
 * it is actually running in loses its Run/Skip/Stop buttons mid-procedure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookActiveTabTest {

    private val sshTarget = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    private fun TestScope.sessions(): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val controller = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeSshTransport(FakeSshConnection(FakeShellChannel())),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
        )
        return controller to scope
    }

    private fun SessionsController.openHost(hostId: String) =
        open(hostId = hostId, title = hostId, subtitle = "u@h:22", target = sshTarget, auth = auth)

    private fun TestScope.runner(scope: CoroutineScope) = RunbookRunner(scope = scope, newId = { "run-1" })

    private fun runbookTarget(sessionId: String) = RunbookTarget(
        sessionId = sessionId,
        send = {},
        readOutput = { "" },
        isLive = { true },
        openSftp = null,
    )

    private fun runbook() = Runbook(id = "rb", label = "Deploy", steps = listOf(RunbookStep.Command(id = "s1", command = "uptime")))

    @Test
    fun `a run started in a pane belongs to that pane's tab`() = runTest {
        val (sessions, scope) = sessions()
        sessions.openHost("web-01")
        advanceUntilIdle()
        val pane = sessions.tabs.single().focusedPane.id
        val runner = runner(scope)

        assertTrue(runner.requestStart(runbook(), runbookTarget(pane)) && runner.confirmStart { "" })

        assertSame(runner.run, runner.runInActiveTab(sessions))
        runner.close()
        scope.cancel()
    }

    @Test
    fun `a run in the other pane of a split still belongs to the tab on screen`() = runTest {
        val (sessions, scope) = sessions()
        sessions.openHost("web-01")
        advanceUntilIdle()
        val tab = sessions.tabs.single()
        val first = tab.focusedPane.id
        // The split focuses the new pane; the run stays with the one it was started in.
        val second = sessions.addPane()
        advanceUntilIdle()
        val runner = runner(scope)
        assertTrue(runner.requestStart(runbook(), runbookTarget(first)) && runner.confirmStart { "" })

        assertTrue(second != null && second != first, "the split opened a second pane")
        assertSame(runner.run, runner.runInActiveTab(sessions), "the caret moved, the run did not")
        runner.close()
        scope.cancel()
    }

    @Test
    fun `a run in another tab is not this tab's run`() = runTest {
        val (sessions, scope) = sessions()
        sessions.openHost("web-01")
        advanceUntilIdle()
        val firstTabPane = sessions.tabs.single().focusedPane.id
        sessions.openHost("db-01")
        advanceUntilIdle()
        val runner = runner(scope)
        assertTrue(runner.requestStart(runbook(), runbookTarget(firstTabPane)) && runner.confirmStart { "" })

        // db-01 is the active tab now: its work area must not show web-01's run.
        assertNull(runner.runInActiveTab(sessions))
        runner.close()
        scope.cancel()
    }
}
