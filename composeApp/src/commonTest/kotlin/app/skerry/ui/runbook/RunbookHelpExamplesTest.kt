package app.skerry.ui.runbook

import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.isRunnable
import app.skerry.shared.snippet.SnippetTemplate
import kotlin.test.Test
import kotlin.test.assertTrue

class RunbookHelpExamplesTest {

    @Test
    fun every_example_names_itself_and_every_step_is_runnable() {
        RUNBOOK_HELP_EXAMPLES.forEach { draft ->
            assertTrue(draft.label.isNotBlank())
            assertTrue(draft.steps.isNotEmpty(), draft.label)
            draft.steps.forEach { step -> assertTrue(step.isRunnable, "${draft.label}: ${step.title}") }
        }
    }

    @Test
    fun example_placeholders_parse_as_variables_not_literal_text() {
        val commands = RUNBOOK_HELP_EXAMPLES.flatMap { it.steps }.filterIsInstance<RunbookStep.Command>()
        commands.filter { "\${{" in it.command }.forEach { step ->
            assertTrue(SnippetTemplate.hasVariables(step.command), step.command)
        }
    }

    @Test
    fun one_example_shows_an_interactive_step() {
        // The dialog explains the interactive flag; an example must let the user try it in a click.
        assertTrue(
            RUNBOOK_HELP_EXAMPLES.flatMap { it.steps }
                .filterIsInstance<RunbookStep.Command>()
                .any { it.interactive },
        )
    }

    @Test
    fun example_step_ids_are_left_for_the_manager_to_assign() {
        // The same contract the editor uses: a blank id is replaced on save, a non-blank one is
        // kept — and a hardcoded id here would collide when the example is added twice.
        RUNBOOK_HELP_EXAMPLES.flatMap { it.steps }.forEach { step ->
            assertTrue(step.id.isBlank())
        }
    }
}
