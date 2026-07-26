package app.skerry.shared.runbook

import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate

/**
 * A runbook's steps parsed once for one run, with every machine variable (`${{uuid}}`,
 * `${{random}}`, `${{date}}`…) drawn once for the *whole* run rather than once per occurrence.
 *
 * That is the one place a runbook must differ from a snippet: a snippet is a single line, so
 * "one draw per placeholder occurrence" is the same thing as "one draw"; a runbook creates a
 * resource in step 2 and checks it in step 5, and `${{uuid}}` has to name the same thing both
 * times. The same reasoning applies to `${{date}}` — the run gets one timestamp, not one per step.
 *
 * Context variables (clipboard/vault/prompted parameters) are not touched here: the caller collects
 * them once when the run is confirmed and passes them into [line], so what the user previewed is
 * what every step sends (TOCTOU rule, coding-guidelines §3).
 */
class RunbookScript private constructor(
    private val segments: List<List<SnippetSegment>>,
    private val machineValues: List<List<String?>>,
) {

    /**
     * Distinct placeholders across all steps, in first-appearance order — what the start dialog has
     * to ask for. Two occurrences of the same placeholder (same kind, name and format) appear once.
     */
    val variables: List<SnippetSegment.Variable> =
        segments.flatten().filterIsInstance<SnippetSegment.Variable>().distinctBy { it.identity() }

    /**
     * The command line of step [stepIndex] with all variables filled in: machine values from this
     * run's draw, everything else from [contextValue] (sanitized by
     * [app.skerry.shared.snippet.SnippetTemplate.assemble]). An index outside the runbook is an
     * empty line rather than a crash — the runner walks its own copy of the step list.
     */
    fun line(stepIndex: Int, contextValue: (SnippetSegment.Variable) -> String): String {
        val stepSegments = segments.getOrNull(stepIndex) ?: return ""
        return SnippetTemplate.assemble(stepSegments, machineValues[stepIndex], contextValue)
    }

    companion object {
        fun of(runbook: Runbook, environment: SnippetRunEnvironment): RunbookScript {
            val segments = runbook.steps.map { SnippetTemplate.parse(it.command) }
            val drawn = mutableMapOf<String, String>()
            val machineValues = segments.map { stepSegments ->
                stepSegments.map { segment ->
                    val variable = segment as? SnippetSegment.Variable ?: return@map null
                    val key = variable.identity()
                    drawn[key] ?: SnippetTemplate.resolveMachine(variable, environment)?.also { drawn[key] = it }
                }
            }
            return RunbookScript(segments, machineValues)
        }
    }
}

/** What makes two placeholders "the same variable" within one run: kind, name and format together. */
private fun SnippetSegment.Variable.identity(): String = kind.name + "/" + name + "/" + format
