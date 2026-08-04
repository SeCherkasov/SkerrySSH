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

/** How the host of a past run fared. [failedStep] is 1-based, as the run screen numbers steps. */
@Serializable
data class RunbookHostOutcome(
    val label: String,
    val stepsDone: Int,
    val stepsTotal: Int,
    val failedStep: Int? = null,
)

/**
 * A past run of a runbook: when it started, how long it took, how it ended, and how far it got.
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
    val host: RunbookHostOutcome,
)
