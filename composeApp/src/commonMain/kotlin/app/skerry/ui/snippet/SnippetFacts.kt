package app.skerry.ui.snippet

import androidx.compose.runtime.Immutable
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate

/**
 * What the snippet library holds, for the section header: how many commands are saved and how many
 * of them prompt for something before running ([SnippetSegment.Variable]). The second number is the
 * one that changes how the library is used — a snippet with variables never runs on one keystroke.
 */
@Immutable
data class SnippetLibraryFacts(val total: Int, val withVariables: Int)

/**
 * Count [snippets] and the subset carrying `${{…}}` placeholders. Parsing is the same one the run
 * path uses, so ordinary shell syntax (`$VAR`, `${VAR}`, `$(cmd)`) is not mistaken for a prompt; a
 * snippet with several placeholders still counts once.
 */
fun snippetLibraryFacts(snippets: List<Snippet>): SnippetLibraryFacts = SnippetLibraryFacts(
    total = snippets.size,
    withVariables = snippets.count { snippetHasVariables(it.command) },
)

/** Whether [command] carries at least one `${{…}}` placeholder (i.e. running it opens the dialog). */
fun snippetHasVariables(command: String): Boolean =
    SnippetTemplate.parse(command).any { it is SnippetSegment.Variable }
