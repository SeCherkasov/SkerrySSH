package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            entry(RunbookStep(id = "s1", title = "Drain", command = "drain", confirm = false, continueOnError = true)),
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
        val form = RunbookFormState.fromEntry(entry(RunbookStep(id = "s1", command = "a")))
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
        val form = RunbookFormState.fromEntry(entry(RunbookStep(id = "s1", command = "a"), RunbookStep(id = "s2", command = "b")))
        form.moveStep(0, -1)
        form.moveStep(1, 2)
        assertEquals(listOf("a", "b"), form.steps.map { it.command })
    }

    @Test
    fun `removing the last step leaves an empty one rather than nothing to type into`() {
        val form = RunbookFormState.fromEntry(entry(RunbookStep(id = "s1", command = "a")))
        form.removeStep(form.steps[0])
        assertEquals(1, form.steps.size)
        assertEquals("", form.steps[0].command)
    }

    @Test
    fun `the draft keeps step ids, flags and an uncommitted tag`() {
        val form = RunbookFormState.fromEntry(
            entry(RunbookStep(id = "s1", title = "Drain", command = "drain", confirm = true, continueOnError = true)),
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
}
