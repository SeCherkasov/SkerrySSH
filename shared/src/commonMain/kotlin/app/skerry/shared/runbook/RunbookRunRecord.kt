package app.skerry.shared.runbook

import kotlinx.serialization.Serializable

/** How a run ended, as the history lists it. */
enum class RunbookRunOutcome {
    /** Every step of every host succeeded. */
    DONE,

    /** It reached the end, but something failed on the way (a step the runbook tolerates). */
    DONE_WITH_FAILURES,

    /** A failure ended it. */
    FAILED,

    /** The user stopped it, or the session went away. */
    STOPPED,
}

/** One host's share of a past run. [failedStep] is 1-based, as the run screen numbers steps. */
@Serializable
data class RunbookHostOutcome(
    val label: String,
    val stepsDone: Int,
    val stepsTotal: Int,
    val failedStep: Int? = null,
)

/**
 * A past run of a runbook: when it started, how long it took, how it ended, and how each host fared.
 *
 * What the command lines were and what they printed is deliberately *not* here. A step's line can
 * carry a `${{vault}}` secret and its output can carry anything at all; the terminal that ran it is
 * the record of those, and it is the user's to keep or close. The history answers a narrower
 * question — did this procedure work last time, and how long did it take.
 */
@Serializable
data class RunbookRunRecord(
    val id: String,
    val runbookId: String,
    val startedAt: Long,
    val durationMillis: Long,
    val outcome: RunbookRunOutcome,
    val hosts: List<RunbookHostOutcome> = emptyList(),
)

/** Steps finished across every host of the run — the "7 of 7" of a history row. */
val RunbookRunRecord.stepsDone: Int get() = hosts.sumOf { it.stepsDone }

/** Steps the run had to get through in total. */
val RunbookRunRecord.stepsTotal: Int get() = hosts.sumOf { it.stepsTotal }

/** The first step that failed anywhere in the run, or `null` if none did. */
val RunbookRunRecord.failedStep: Int? get() = hosts.mapNotNull { it.failedStep }.minOrNull()
