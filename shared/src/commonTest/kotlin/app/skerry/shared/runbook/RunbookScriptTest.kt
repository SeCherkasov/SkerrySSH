package app.skerry.shared.runbook

import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetVariableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunbookScriptTest {

    private fun env(): SnippetRunEnvironment {
        var uuids = 0
        var randoms = 0
        return SnippetRunEnvironment(
            moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
            newUuid = { "uuid-${++uuids}" },
            randomChars = { n, _ -> "r${++randoms}".padEnd(n, 'x') },
        )
    }

    private fun runbook(vararg commands: String) = Runbook(
        id = "rb",
        label = "Deploy",
        steps = commands.mapIndexed { i, c -> RunbookStep.Command(id = "s$i", title = "step $i", command = c) },
    )

    /** The resolved command line of step [index] — what every command-step assertion here reads. */
    private fun RunbookScript.line(index: Int, contextValue: (SnippetSegment.Variable) -> String = { "" }): String? =
        (resolve(index, contextValue) as? ResolvedRunbookStep.Command)?.line

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
        assertEquals("create uuid-1", script.line(0))
        assertEquals("verify uuid-1", script.line(1))
    }

    @Test
    fun `different placeholders keep their own draws`() {
        val script = RunbookScript.of(runbook("a \${{uuid}} \${{random:4}}"), env())
        assertEquals("a uuid-1 r1xx", script.line(0))
    }

    @Test
    fun `the same date is stamped across the whole run`() {
        val script = RunbookScript.of(runbook("tag \${{date}}", "log \${{date}}"), env())
        assertEquals("tag 2026-07-26", script.line(0))
        assertEquals("log 2026-07-26", script.line(1))
    }

    @Test
    fun `repeated resolve calls are stable`() {
        val script = RunbookScript.of(runbook("id \${{uuid}}"), env())
        assertEquals(script.line(0), script.line(0))
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
        assertEquals("df -h | sort -k5 -r", script.line(0))
    }

    @Test
    fun `out of range step index resolves to nothing rather than a crash`() {
        val script = RunbookScript.of(runbook("uptime"), env())
        assertNull(script.resolve(5) { "" })
    }

    @Test
    fun `both paths of a transfer step are resolved`() {
        val runbook = Runbook(
            id = "rb",
            label = "Deploy",
            steps = listOf(
                RunbookStep.Transfer(
                    id = "s0",
                    localPath = "build/app-\${{version}}.tar.gz",
                    remotePath = "/var/www/releases/\${{version}}",
                ),
            ),
        )

        val resolved = RunbookScript.of(runbook, env()).resolve(0) { "0.2.1" }

        val transfer = assertIs<ResolvedRunbookStep.Transfer>(resolved)
        assertEquals("build/app-0.2.1.tar.gz", transfer.localPath)
        assertEquals("/var/www/releases/0.2.1", transfer.remotePath)
        assertEquals(RunbookTransferDirection.UPLOAD, transfer.direction)
    }

    @Test
    fun `a transfer step's placeholders are asked for like any other step's`() {
        val runbook = Runbook(
            id = "rb",
            label = "Deploy",
            steps = listOf(
                RunbookStep.Transfer(id = "s0", localPath = "\${{archive}}", remotePath = "/srv/\${{zone}}"),
                RunbookStep.Command(id = "s1", command = "systemctl restart \${{zone}}"),
            ),
        )

        val script = RunbookScript.of(runbook, env())

        assertEquals(listOf("archive", "zone"), script.variables.map { it.name })
    }

    @Test
    fun `a machine variable shared by a command and a transfer path draws once`() {
        val runbook = Runbook(
            id = "rb",
            label = "Deploy",
            steps = listOf(
                RunbookStep.Command(id = "s0", command = "tar czf /tmp/\${{uuid}}.tgz ."),
                RunbookStep.Transfer(id = "s1", localPath = "/tmp/\${{uuid}}.tgz", remotePath = "/srv/incoming"),
            ),
        )

        val script = RunbookScript.of(runbook, env())

        assertEquals("tar czf /tmp/uuid-1.tgz .", script.line(0))
        assertEquals("/tmp/uuid-1.tgz", (script.resolve(1) { "" } as ResolvedRunbookStep.Transfer).localPath)
    }
}
