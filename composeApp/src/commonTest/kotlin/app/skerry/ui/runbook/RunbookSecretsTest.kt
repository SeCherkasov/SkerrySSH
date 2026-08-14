package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The resolved vault secrets ride with every line a runbook run sends, so the production guard's
 * confirmation can mask them instead of printing the resolved step (issue #246). A wiring break
 * here would silently print a secret in clear one dialog after the start dialog masked it. Its own
 * class beside [RunbookRunnerTest], which is at the size limit and owns the run-lifecycle fixture
 * this test does not need.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookSecretsTest {

    @Test
    fun `a run's resolved vault secrets ride with every sent line`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val runner = RunbookRunner(scope, newId = { "run" }, environment = {
            SnippetRunEnvironment(
                moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
                newUuid = { "uuid" },
                randomChars = { n, _ -> "r".repeat(n) },
            )
        })
        val sentSecrets = mutableListOf<List<String>>()
        val target = RunbookTarget(
            sessionId = "tab-1",
            send = { _, secrets -> sentSecrets += secrets },
            expectStep = { _, _ -> },
            takeMark = { null },
            outputVersion = { 0L },
            isLive = { true },
        )
        val step = RunbookStep.Command(id = "s1", title = "s1", command = "echo \${{vault:db}}", confirm = false)
        val runbook = Runbook(id = "rb", label = "Deploy", steps = listOf(step), policy = RunbookPolicy(watchdogMinutes = 1))
        try {
            assertTrue(runner.requestStart(runbook, target), "requestStart refused")

            assertTrue(runner.confirmStart(secrets = listOf("hunter2")) { "hunter2" })

            assertEquals(RunbookPhase.RUNNING, runner.phase, "run did not start")
            assertEquals(listOf("hunter2"), sentSecrets.single())
        } finally {
            runner.close()
            scope.cancel()
        }
    }
}
