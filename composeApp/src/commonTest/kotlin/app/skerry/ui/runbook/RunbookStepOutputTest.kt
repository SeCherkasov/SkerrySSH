package app.skerry.ui.runbook

import app.skerry.shared.runbook.RunbookMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cutting one step's output out of the terminal tail. The buffer holds the echo of the line the
 * runner typed (which carries the token, since the probe passes it as an argument), whatever the
 * command printed, and finally the marker `token:code` — so the step's output is what sits between
 * the echo and the marker. Buffers here are built with the real [RunbookMarker] so the two can't
 * drift apart.
 */
class RunbookStepOutputTest {

    private val token = RunbookMarker.token("run", 1)
    private val earlierToken = RunbookMarker.token("run", 0)

    /** The terminal as it looks after [command] ran under [token] and printed [output]. */
    private fun buffer(command: String, output: String, exitCode: Int? = 0, token: String = this.token): String =
        buildString {
            append("user@host:~$ ").append(RunbookMarker.probeLine(command, token)).append('\n')
            if (output.isNotEmpty()) append(output).append('\n')
            if (exitCode != null) append('\n').append(token).append(':').append(exitCode).append('\n')
        }

    @Test
    fun `output is what the command printed between its echo and its marker`() {
        val text = buffer("curl -fsS localhost/healthz", "healthz 200 OK")

        assertEquals("healthz 200 OK", runbookStepOutput(text, token))
    }

    @Test
    fun `a step that printed several lines keeps them all, in order`() {
        val text = buffer("apt upgrade", "Reading package lists...\nBuilding dependency tree...\n2 upgraded")

        assertEquals(
            "Reading package lists...\nBuilding dependency tree...\n2 upgraded",
            runbookStepOutput(text, token),
        )
    }

    @Test
    fun `a step that printed nothing has empty output rather than none at all`() {
        val text = buffer("true", "")

        assertEquals("", runbookStepOutput(text, token))
    }

    @Test
    fun `a step still running has no output yet`() {
        val text = buffer("./migrate.sh", "migrating 1/400", exitCode = null)

        assertNull(runbookStepOutput(text, token))
    }

    @Test
    fun `an earlier step of the same run does not leak into this one`() {
        val text = buffer("echo one", "one", token = earlierToken) + buffer("echo two", "two")

        assertEquals("two", runbookStepOutput(text, token))
        assertEquals("one", runbookStepOutput(text, earlierToken))
    }

    @Test
    fun `a flood of output is cut to its tail rather than kept whole`() {
        val flood = (1..5_000).joinToString("\n") { "line $it" }
        val text = buffer("./noisy", flood)

        val output = assertNotNull(runbookStepOutput(text, token))
        assertTrue(output.length <= RUNBOOK_STEP_OUTPUT_LIMIT, "kept ${output.length} chars")
        assertTrue(output.endsWith("line 5000"), "the tail is the part worth keeping")
        assertTrue(output.lineSequence().first().startsWith("line "), "a cut must not leave half a line")
    }

    @Test
    fun `a buffer whose start scrolled past the echo yields nothing rather than a guess`() {
        // The tail window is finite: with the echo gone there is no way to tell where the step's
        // own output begins, and inventing a start would attribute the previous step's lines to it.
        val text = "some older output\n\n$token:0\n"

        assertNull(runbookStepOutput(text, token))
    }
}
