package app.skerry.ui.runbook

import app.skerry.shared.runbook.ResolvedRunbookStep
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookScript
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The start dialog asks for each distinct `${{…}}` value once and hands the run a snapshot of what
 * it collected. What "distinct" means has to be the same on both sides — the dialog asking under one
 * key and the run looking up under another is a step that runs with an empty value nobody typed.
 */
class RunbookValueSnapshotTest {

    private val environment = SnippetRunEnvironment(
        moment = SnippetMoment(2026, 8, 4, 12, 0, 0, epochSeconds = 1_785_000_000L),
        newUuid = { "uuid" },
        randomChars = { n, _ -> "r".repeat(n) },
    )

    private fun scriptOf(vararg commands: String): RunbookScript = RunbookScript.of(
        Runbook(
            id = "rb",
            label = "Deploy",
            steps = commands.mapIndexed { index, command -> RunbookStep.Command(id = "s$index", command = command) },
        ),
        environment,
    )

    private fun lineOf(script: RunbookScript, index: Int, values: (app.skerry.shared.snippet.SnippetSegment.Variable) -> String) =
        assertIs<ResolvedRunbookStep.Command>(script.resolve(index, values)).line

    @Test
    fun `a value typed once reaches every step that asks for it`() {
        val script = scriptOf("deploy \${{svc}}", "restart \${{svc}}")
        val values = runbookValueSnapshot(script.variables) { "billing" }

        assertEquals("deploy billing", lineOf(script, 0, values))
        assertEquals("restart billing", lineOf(script, 1, values))
    }

    @Test
    fun `the same placeholder written two ways still gets the value that was typed for it`() {
        // `${{svc}}` and `${{svc:}}` parse to the same variable with different original text. The
        // dialog only asks once (the script dedupes them), so the second step has to find that answer
        // — keyed by the whole segment it would not, and the step would run with an empty value.
        val script = scriptOf("deploy \${{svc}}", "restart \${{svc:}}")
        assertEquals(1, script.variables.size, "the dialog asks for this value once")

        val values = runbookValueSnapshot(script.variables) { "billing" }

        assertEquals("deploy billing", lineOf(script, 0, values))
        assertEquals("restart billing", lineOf(script, 1, values))
    }

    @Test
    fun `a placeholder nobody was asked about resolves to nothing rather than crashing`() {
        val script = scriptOf("deploy \${{svc}}")
        val values = runbookValueSnapshot(variables = emptyList()) { "billing" }

        assertEquals("deploy ", lineOf(script, 0, values))
    }
}
