package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetFactsTest {

    private fun snippet(command: String) = Snippet(id = command, label = command, command = command)

    @Test
    fun counts_the_whole_library() {
        val facts = snippetLibraryFacts(listOf(snippet("df -h"), snippet("ss -ltnp")))

        assertEquals(2, facts.total)
        assertEquals(0, facts.withVariables)
    }

    @Test
    fun counts_a_snippet_with_variables_once_however_many_it_carries() {
        val facts = snippetLibraryFacts(
            listOf(
                snippet("journalctl -u \${{service}} -n \${{lines}}"),
                snippet("df -h"),
            ),
        )

        assertEquals(2, facts.total)
        assertEquals(1, facts.withVariables)
    }

    @Test
    fun plain_shell_syntax_is_not_a_variable() {
        // $VAR / ${VAR} / $(cmd) are shell, not snippet placeholders — counting them would report
        // a prompt that never appears.
        val facts = snippetLibraryFacts(listOf(snippet("echo \$HOME \${PATH} \$(date)")))

        assertEquals(1, facts.total)
        assertEquals(0, facts.withVariables)
    }

    @Test
    fun an_empty_library_has_no_facts_to_report() {
        val facts = snippetLibraryFacts(emptyList())

        assertEquals(0, facts.total)
        assertEquals(0, facts.withVariables)
    }
}
