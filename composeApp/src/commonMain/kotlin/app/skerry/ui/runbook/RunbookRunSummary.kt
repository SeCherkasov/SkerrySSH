package app.skerry.ui.runbook

import app.skerry.shared.runbook.RunbookHostOutcome
import app.skerry.shared.runbook.RunbookRunOutcome
import app.skerry.shared.runbook.RunbookRunRecord

/**
 * What a finished run leaves behind in the log: how it ended, how far it got, and where it broke.
 *
 * Deliberately nothing else — no command lines, no output, no resolved variables. The log is synced
 * and a step can carry a `${'$'}{{vault}}` secret; the honest record of what ran is the terminal the user
 * was watching (see [RunbookRunRecord]).
 */
internal fun RunbookSessionRun.runRecord(
    id: String,
    runbookId: String,
    startedAt: Long,
    durationMillis: Long,
    phase: RunbookPhase,
): RunbookRunRecord = RunbookRunRecord(
    id = id,
    runbookId = runbookId,
    startedAt = startedAt,
    durationMillis = durationMillis,
    outcome = when {
        phase == RunbookPhase.FAILED -> RunbookRunOutcome.FAILED
        phase == RunbookPhase.STOPPED -> RunbookRunOutcome.STOPPED
        hadFailures -> RunbookRunOutcome.DONE_WITH_FAILURES
        else -> RunbookRunOutcome.DONE
    },
    host = RunbookHostOutcome(
        label = label,
        stepsDone = finishedCount,
        stepsTotal = steps.size,
        // 1-based, the way the run screen numbers steps.
        failedStep = steps.firstOrNull { it.status == RunbookStepStatus.FAILED }?.let { it.index + 1 },
    ),
)
