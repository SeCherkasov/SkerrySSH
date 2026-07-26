package app.skerry.shared.runbook

import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetVariableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunbookScriptTest {

    private fun env(): SnippetRunEnvironment {
        var uuids = 0
        var randoms = 0
        return SnippetRunEnvironment(
            moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
            newUuid = { "uuid-${++uuids}" },
            randomChars = { n -> "r${++randoms}".padEnd(n, 'x') },
        )
    }

    private fun runbook(vararg commands: String) = Runbook(
        id = "rb",
        label = "Deploy",
        steps = commands.mapIndexed { i, c -> RunbookStep(id = "s$i", title = "step $i", command = c) },
    )

    @Test
    fun `variables of every step are collected once in first-appearance order`() {
        val script = RunbookScript.of(runbook("echo \${{service}}", "systemctl restart \${{service}} \${{zone}}"), env())
        assertEquals(listOf("service", "zone"), script.variables.map { it.name })
        assertTrue(script.variables.all { it.kind == SnippetVariableKind.PARAM })
    }

    @Test
    fun `a machine variable is drawn once for the whole run, not once per step`() {
        // A runbook creates a resource in one step and refers to it in the next: the same
        // placeholder must carry the same value, or the second step addresses nothing.
        val script = RunbookScript.of(runbook("create \${{uuid}}", "verify \${{uuid}}"), env())
        assertEquals("create uuid-1", script.line(0) { "" })
        assertEquals("verify uuid-1", script.line(1) { "" })
    }

    @Test
    fun `different placeholders keep their own draws`() {
        val script = RunbookScript.of(runbook("a \${{uuid}} \${{random:4}}"), env())
        assertEquals("a uuid-1 r1xx", script.line(0) { "" })
    }

    @Test
    fun `the same date is stamped across the whole run`() {
        val script = RunbookScript.of(runbook("tag \${{date}}", "log \${{date}}"), env())
        assertEquals("tag 2026-07-26", script.line(0) { "" })
        assertEquals("log 2026-07-26", script.line(1) { "" })
    }

    @Test
    fun `repeated line calls are stable`() {
        val script = RunbookScript.of(runbook("id \${{uuid}}"), env())
        assertEquals(script.line(0) { "" }, script.line(0) { "" })
    }

    @Test
    fun `context values come from the caller and are sanitized`() {
        val script = RunbookScript.of(runbook("deploy \${{target}}"), env())
        // A newline in a prompted value would run the rest as a second command.
        assertEquals("deploy web-1 rm -rf /", script.line(0) { "web-1\nrm -rf /" })
    }

    @Test
    fun `a step without variables passes through as written`() {
        val script = RunbookScript.of(runbook("df -h | sort -k5 -r"), env())
        assertEquals("df -h | sort -k5 -r", script.line(0) { "" })
    }

    @Test
    fun `out of range step index is empty rather than a crash`() {
        val script = RunbookScript.of(runbook("uptime"), env())
        assertEquals("", script.line(5) { "" })
    }
}
