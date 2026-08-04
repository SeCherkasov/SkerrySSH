package app.skerry.ui.runbook

import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.identity

/**
 * Freezes what the start dialog collected: every `${'$'}{{…}}` value is read once, here, and the run
 * reads its steps out of that snapshot. A clipboard that changes mid-procedure — or a vault entry
 * rewritten between step 2 and step 5 — can't rewrite a line the user already approved
 * (TOCTOU rule, coding-guidelines §3).
 *
 * Keyed by [identity] rather than by the segment itself: [app.skerry.shared.runbook.RunbookScript]
 * asks for one value per kind/name/format, so a step writing the same variable differently
 * (`${'$'}{{svc}}` vs `${'$'}{{svc:}}`) has to find the answer the dialog already has. A variable nobody was
 * asked about resolves to an empty string, the way an unanswered placeholder always has.
 */
internal fun runbookValueSnapshot(
    variables: List<SnippetSegment.Variable>,
    value: (SnippetSegment.Variable) -> String,
): (SnippetSegment.Variable) -> String {
    val byIdentity = variables.associate { it.identity() to value(it) }
    return { variable -> byIdentity[variable.identity()].orEmpty() }
}
