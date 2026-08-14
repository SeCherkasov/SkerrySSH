package app.skerry.ui.runbook

import androidx.compose.runtime.Composable
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_status_confirm
import app.skerry.ui.generated.resources.runbook_status_interactive
import app.skerry.ui.generated.resources.runbook_step_n
import org.jetbrains.compose.resources.stringResource

/**
 * Announces the run's own pauses — a confirmation gate, an interactive step waiting to be
 * completed — to a screen reader whose focus is elsewhere (WCAG 4.1.3; see [StatusAnnouncer]).
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
    val state = run?.steps?.getOrNull(run.currentIndex)
    val message = when (state?.status) {
        RunbookStepStatus.AWAITING_CONFIRM ->
            stringResource(Res.string.runbook_step_n, state.index + 1) + " · " +
                stringResource(Res.string.runbook_status_confirm)
        RunbookStepStatus.AWAITING_COMPLETE ->
            stringResource(Res.string.runbook_step_n, state.index + 1) + " · " +
                stringResource(Res.string.runbook_status_interactive)
        else -> ""
    }
    StatusAnnouncer(message)
}
