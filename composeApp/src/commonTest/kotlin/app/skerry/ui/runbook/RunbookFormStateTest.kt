package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookParallelism
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunbookFormStateTest {

    private fun entry(vararg steps: RunbookStep) = RunbookEntry(
        Runbook(id = "rb", label = "Deploy", description = "note", steps = steps.toList(), tags = listOf("ops")),
    )

    @Test
    fun `a new form starts with one empty step to type into`() {
        val form = RunbookFormState.fromEntry(null)
        assertEquals(1, form.steps.size)
        assertEquals("", form.steps[0].command)
        assertFalse(form.canSave)
    }

    @Test
    fun `an edited runbook seeds every field`() {
        val form = RunbookFormState.fromEntry(
            entry(RunbookStep.Command(id = "s1", title = "Drain", command = "drain", confirm = false, continueOnError = true)),
        )
        assertEquals("Deploy", form.label)
        assertEquals("note", form.description)
        assertEquals(listOf("ops"), form.tags)
        assertEquals("Drain", form.steps[0].title)
        assertEquals("drain", form.steps[0].command)
        assertFalse(form.steps[0].confirm)
        assertTrue(form.steps[0].continueOnError)
    }

    @Test
    fun `save needs a name and at least one command`() {
        val form = RunbookFormState.fromEntry(null)
        form.label = "Deploy"
        assertFalse(form.canSave, "a runbook with no commands has nothing to run")
        form.steps[0].command = "uptime"
        assertTrue(form.canSave)
        form.label = "   "
        assertFalse(form.canSave)
    }

    @Test
    fun `steps are added, removed and reordered`() {
        val form = RunbookFormState.fromEntry(entry(RunbookStep.Command(id = "s1", command = "a")))
        form.addStep()
        form.steps[1].command = "b"
        assertEquals(listOf("a", "b"), form.steps.map { it.command })

        form.moveStep(1, 0)
        assertEquals(listOf("b", "a"), form.steps.map { it.command })

        form.removeStep(form.steps[0])
        assertEquals(listOf("a"), form.steps.map { it.command })
    }

    @Test
    fun `reordering past the ends does nothing`() {
        val form = RunbookFormState.fromEntry(entry(RunbookStep.Command(id = "s1", command = "a"), RunbookStep.Command(id = "s2", command = "b")))
        form.moveStep(0, -1)
        form.moveStep(1, 2)
        assertEquals(listOf("a", "b"), form.steps.map { it.command })
    }

    @Test
    fun `removing the last step leaves an empty one rather than nothing to type into`() {
        val form = RunbookFormState.fromEntry(entry(RunbookStep.Command(id = "s1", command = "a")))
        form.removeStep(form.steps[0])
        assertEquals(1, form.steps.size)
        assertEquals("", form.steps[0].command)
    }

    @Test
    fun `the draft keeps step ids, flags and an uncommitted tag`() {
        val form = RunbookFormState.fromEntry(
            entry(RunbookStep.Command(id = "s1", title = "Drain", command = "drain", confirm = true, continueOnError = true)),
        )
        form.tagDraft = "disk"
        val draft = form.toDraft()

        assertEquals("rb", draft.id)
        assertEquals(listOf("ops", "disk"), draft.tags)
        assertEquals("s1", draft.steps[0].id)
        assertTrue(draft.steps[0].confirm)
        assertTrue(draft.steps[0].continueOnError)
    }

    @Test
    fun `a step added in the form carries no id so the manager assigns one`() {
        val form = RunbookFormState.fromEntry(null)
        form.steps[0].command = "uptime"
        assertEquals("", form.toDraft().steps[0].id)
    }

    @Test
    fun `a transfer row saves as a transfer step and needs both of its ends`() {
        val form = RunbookFormState.fromEntry(null)
        form.label = "Deploy"
        form.steps[0].kind = RunbookStepKind.TRANSFER
        form.steps[0].localPath = "release.tgz"
        assertFalse(form.canSave, "a transfer with nowhere to put the file has nothing to run")

        form.steps[0].remotePath = "/srv/incoming"
        assertTrue(form.canSave)

        val step = assertIs<RunbookStep.Transfer>(form.toDraft().steps[0])
        assertEquals("release.tgz", step.localPath)
        assertEquals("/srv/incoming", step.remotePath)
        assertEquals(RunbookTransferDirection.UPLOAD, step.direction)
    }

    @Test
    fun `switching a row between kinds keeps what was typed on both sides`() {
        // Otherwise a mis-click on the kind chip silently throws away a command line.
        val form = RunbookFormState.fromEntry(null)
        form.steps[0].command = "systemctl restart app"
        form.steps[0].kind = RunbookStepKind.TRANSFER
        form.steps[0].localPath = "app.tgz"
        form.steps[0].kind = RunbookStepKind.COMMAND

        assertEquals("systemctl restart app", form.steps[0].command)
        assertEquals("app.tgz", form.steps[0].localPath)
    }

    @Test
    fun `a saved transfer step opens as a transfer row`() {
        val form = RunbookFormState.fromEntry(
            entry(
                RunbookStep.Transfer(
                    id = "s1",
                    title = "Upload",
                    localPath = "a.tgz",
                    remotePath = "/srv/a.tgz",
                    direction = RunbookTransferDirection.DOWNLOAD,
                ),
            ),
        )

        assertEquals(RunbookStepKind.TRANSFER, form.steps[0].kind)
        assertEquals("a.tgz", form.steps[0].localPath)
        assertEquals("/srv/a.tgz", form.steps[0].remotePath)
        assertEquals(RunbookTransferDirection.DOWNLOAD, form.steps[0].direction)
    }

    @Test
    fun `run policy is seeded from the runbook and carried back into the draft`() {
        val entry = RunbookEntry(
            Runbook(
                id = "rb",
                label = "Deploy",
                steps = listOf(RunbookStep.Command(id = "s1", command = "uptime")),
                policy = RunbookPolicy(
                    stopOnFirstFailure = false,
                    watchdogMinutes = 5,
                    parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE,
                ),
            ),
        )

        val form = RunbookFormState.fromEntry(entry)
        assertFalse(form.stopOnFirstFailure)
        assertEquals(5, form.watchdogMinutes)
        assertEquals(RunbookParallelism.ALL_HOSTS_AT_ONCE, form.parallelism)

        form.watchdogMinutes = 10
        assertEquals(10, form.toDraft().policy.watchdogMinutes)
        assertEquals(RunbookParallelism.ALL_HOSTS_AT_ONCE, form.toDraft().policy.parallelism)
    }

    @Test
    fun `a new form starts on the default policy`() {
        val form = RunbookFormState.fromEntry(null)

        assertEquals(RunbookPolicy(), form.toDraft().policy)
    }
}
