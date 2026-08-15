package app.skerry.ui.runbook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_stalled
import app.skerry.ui.generated.resources.runbook_status_confirm
import app.skerry.ui.generated.resources.runbook_status_failed
import app.skerry.ui.generated.resources.runbook_status_failed_count
import app.skerry.ui.generated.resources.runbook_status_interactive
import app.skerry.ui.generated.resources.runbook_step_n
import org.jetbrains.compose.resources.stringResource

/**
 * Announces the run's attention signals — a confirmation gate, an interactive step waiting to be
 * completed, the run ending, a step going quiet, a tolerated failure — to a screen reader whose
 * focus is elsewhere (WCAG 4.1.3; see [StatusAnnouncer]). The same signals that reopen a collapsed
 * [RunbookRunPanel]: Compose does not announce node insertion, so without this a non-sighted user
 * gets none of them.
 *
 * Composed from the chrome, not from the run panel: the announcer only fires on a *change* of its
 * message, so it must exist before the run does — a panel that appears already saying "waiting for
 * you" is an insertion, not a change, and stays silent. The message carries the step number for
 * the same reason: two consecutive interactive steps would otherwise produce the same string
 * twice, and the second would be deduplicated into silence.
 */
@Composable
fun RunbookPauseAnnouncer(runner: RunbookRunner) {
    val run = runner.run
    val phase = runner.phase
    val state = run?.steps?.getOrNull(run.currentIndex)
    val message = when {
        state?.status == RunbookStepStatus.AWAITING_CONFIRM ->
            stringResource(Res.string.runbook_step_n, state.index + 1) + " · " +
                stringResource(Res.string.runbook_status_confirm)
        state?.status == RunbookStepStatus.AWAITING_COMPLETE ->
            stringResource(Res.string.runbook_step_n, state.index + 1) + " · " +
                stringResource(Res.string.runbook_status_interactive)
        // The run's endings, in the panel's own words ("Finished", "Stopped on a failure", …).
        phase != null && phase != RunbookPhase.RUNNING && phase != RunbookPhase.AWAITING_CONFIRM ->
            runPhaseLabel(phase, runner.hadFailures)
        state?.stalled == true ->
            stringResource(Res.string.runbook_step_n, state.index + 1) + " · " +
                stringResource(Res.string.runbook_panel_stalled)
        else -> ""
    }
    // A tolerated failure keeps the run in RUNNING and moves past the failed step, so none of the
    // branches above can see it. Voiced from the failure count — and *held*, not flashed: the live
    // region reads changes, and a message reverted a frame later can be cut mid-utterance.
    val failedCount = run?.steps?.count { it.status == RunbookStepStatus.FAILED } ?: 0
    val lastFailed = run?.steps?.lastOrNull { it.status == RunbookStepStatus.FAILED }
    val unheardCount = failedCount - (run?.announcedFailures ?: 0)
    val failureMessage =
        if (unheardCount > 0 && lastFailed != null && message.isEmpty()) {
            // Recomposition can coalesce back-to-back dispatch-time failures (two transfer steps in
            // a session with no SFTP channel) into one frame; a single-step line would voice only
            // the newest and silently drop the rest, so several unheard failures are said as a count.
            if (unheardCount > 1) {
                stringResource(Res.string.runbook_status_failed_count, unheardCount)
            } else {
                stringResource(Res.string.runbook_step_n, lastFailed.index + 1) + " · " +
                    stringResource(Res.string.runbook_status_failed)
            }
        } else {
            null
        }
    // Any other message taking the region counts the failures as heard — the failure line must not
    // come back over something more current. (A pause landing in the same frame as the failure wins
    // the region, and a tolerated failure of the LAST step always lands together with the run's own
    // ending — deterministically, failStep -> advance -> finish is one call stack; either way the
    // reopened panel's red row still carries the failure itself.) A failure line on display marks
    // nothing: its own text changes with the step index and count, and that is what dedups it.
    LaunchedEffect(message, failedCount) {
        if (run != null && message.isNotEmpty()) run.announcedFailures = failedCount
    }
    StatusAnnouncer(failureMessage ?: message)
}
