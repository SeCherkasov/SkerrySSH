package app.skerry.shared.runbook

import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.identity

/** A step with every `${{…}}` filled in — what the runner acts on. */
sealed interface ResolvedRunbookStep {

    /** The line to type into the shell. */
    data class Command(val line: String) : ResolvedRunbookStep

    /** The file to move, both ends resolved. */
    data class Transfer(
        val localPath: String,
        val remotePath: String,
        val direction: RunbookTransferDirection,
    ) : ResolvedRunbookStep
}

/**
 * A runbook's steps parsed once for one run, with every machine variable (`${{uuid}}`,
 * `${{random}}`, `${{date}}`…) drawn once for the *whole* run rather than once per occurrence.
 *
 * That is the one place a runbook must differ from a snippet: a snippet is a single line, so
 * "one draw per placeholder occurrence" is the same thing as "one draw"; a runbook creates a
 * resource in step 2 and checks it in step 5, and `${{uuid}}` has to name the same thing both
 * times. The same reasoning applies to `${{date}}` — the run gets one timestamp, not one per step.
 * A transfer step's two paths are part of the same draw as the commands around it, so an archive
 * named after `${{uuid}}` in one step is the file uploaded in the next.
 *
 * Context variables (clipboard/vault/prompted parameters) are not touched here: the caller collects
 * them once when the run is confirmed and passes them into [resolve], so what the user previewed is
 * what every step sends (TOCTOU rule, coding-guidelines §3).
 */
class RunbookScript private constructor(private val steps: List<ParsedStep>) {

    /**
     * Distinct placeholders across all steps, in first-appearance order — what the start dialog has
     * to ask for. Two occurrences of the same placeholder (same kind, name and format) appear once.
     */
    val variables: List<SnippetSegment.Variable> = steps
        .flatMap { step -> step.fields.flatMap { it.segments } }
        .filterIsInstance<SnippetSegment.Variable>()
        .distinctBy { it.identity() }

    /**
     * Step [stepIndex] with all its variables filled in: machine values from this run's draw,
     * everything else from [contextValue] (sanitized by
     * [app.skerry.shared.snippet.SnippetTemplate.assemble]). An index outside the runbook resolves
     * to `null` rather than a crash — the runner walks its own copy of the step list.
     */
    fun resolve(stepIndex: Int, contextValue: (SnippetSegment.Variable) -> String): ResolvedRunbookStep? {
        val parsed = steps.getOrNull(stepIndex) ?: return null
        val values = parsed.fields.map { SnippetTemplate.assemble(it.segments, it.machineValues, contextValue) }
        return when (val step = parsed.step) {
            is RunbookStep.Command -> ResolvedRunbookStep.Command(values[0])
            is RunbookStep.Transfer -> ResolvedRunbookStep.Transfer(values[0], values[1], step.direction)
        }
    }

    /** One parsed field of a step (a command line, a transfer path) with its share of the draw. */
    private class ParsedField(val segments: List<SnippetSegment>, val machineValues: List<String?>)

    private class ParsedStep(val step: RunbookStep, val fields: List<ParsedField>)

    companion object {
        fun of(runbook: Runbook, environment: SnippetRunEnvironment): RunbookScript {
            // Shared across every field of every step: that is what makes one draw per run.
            val drawn = mutableMapOf<String, String>()
            val steps = runbook.steps.map { step ->
                val fields = step.templateFields().map { text ->
                    val segments = SnippetTemplate.parse(text)
                    val machineValues = segments.map { segment ->
                        val variable = segment as? SnippetSegment.Variable ?: return@map null
                        val key = variable.identity()
                        drawn[key] ?: SnippetTemplate.resolveMachine(variable, environment)?.also { drawn[key] = it }
                    }
                    ParsedField(segments, machineValues)
                }
                ParsedStep(step, fields)
            }
            return RunbookScript(steps)
        }
    }
}

/**
 * The step's texts that carry `${{…}}` variables, in the order [RunbookScript.resolve] reads them
 * back — the one place the field layout of each step kind is written down.
 */
private fun RunbookStep.templateFields(): List<String> = when (this) {
    is RunbookStep.Command -> listOf(command)
    is RunbookStep.Transfer -> listOf(localPath, remotePath)
}
