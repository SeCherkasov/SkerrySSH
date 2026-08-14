package app.skerry.ui.snippet

import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.snippet.paramChoices
import kotlin.test.Test
import kotlin.test.assertTrue

class SnippetHelpExamplesTest {

    @Test
    fun every_example_names_itself_and_says_what_to_run() {
        SNIPPET_HELP_EXAMPLES.forEach { draft ->
            assertTrue(draft.label.isNotBlank())
            assertTrue(draft.command.isNotBlank())
        }
    }

    @Test
    fun example_placeholders_parse_as_variables_not_literal_text() {
        // A typo'd placeholder silently stays literal — the example would ship broken and look fine.
        val withVars = SNIPPET_HELP_EXAMPLES.filter { "\${{" in it.command }
        assertTrue(withVars.isNotEmpty())
        withVars.forEach { draft ->
            assertTrue(SnippetTemplate.hasVariables(draft.command), draft.label)
        }
    }

    @Test
    fun the_examples_show_the_new_syntax_in_use() {
        // The dialog documents charsets and choice lists; at least one example must exercise each.
        val all = SNIPPET_HELP_EXAMPLES.flatMap { SnippetTemplate.variables(it.command) }
        assertTrue(all.any { it.kind == SnippetVariableKind.RANDOM && it.format?.contains(',') == true })
        assertTrue(all.any { it.kind == SnippetVariableKind.PARAM && it.paramChoices().isNotEmpty() })
    }
}
