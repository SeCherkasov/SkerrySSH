package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.shared.terminal.UNREADABLE_STATUS
import app.skerry.ui.app.LocalRunbookHistory
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_dur_minutes
import app.skerry.ui.generated.resources.runbook_dur_seconds
import app.skerry.ui.generated.resources.runbook_panel_done
import app.skerry.ui.generated.resources.runbook_panel_done_with_failures
import app.skerry.ui.generated.resources.runbook_panel_exit_code
import app.skerry.ui.generated.resources.runbook_panel_failed
import app.skerry.ui.generated.resources.runbook_panel_no_sftp
import app.skerry.ui.generated.resources.runbook_panel_running
import app.skerry.ui.generated.resources.runbook_panel_status_unreadable
import app.skerry.ui.generated.resources.runbook_panel_stopped
import app.skerry.ui.generated.resources.runbook_panel_transfer_failed
import app.skerry.ui.generated.resources.runbook_panel_waiting
import app.skerry.ui.generated.resources.runbook_run_no_output
import app.skerry.ui.generated.resources.runbook_run_output
import app.skerry.ui.generated.resources.runbook_run_output_lost
import app.skerry.ui.generated.resources.runbook_status_confirm
import app.skerry.ui.generated.resources.runbook_status_running
import app.skerry.ui.generated.resources.runbook_status_skipped
import app.skerry.ui.generated.resources.runbook_status_stopped
import app.skerry.ui.generated.resources.runbook_status_waiting
import app.skerry.ui.generated.resources.runbook_transfer_of
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Width of the output panel — a shell's 80 columns plus the gutter, on the mockup's proportions. */
private val OUTPUT_WIDTH = 420.dp

/**
 * What a step printed, cut out of the terminal between the step's opening and closing marks
 * ([RunbookStepState.output]). [shownStep] is the step clicked in the list; with none the
 * panel follows the run, so a finished run rests on its last step. A transfer prints nothing at all,
 * so it reports its bytes instead.
 *
 * The terminal itself is a chevron away and holds everything, including what scrolled out of this
 * panel — the panel is the readable part, not the record.
 */
@Composable
internal fun RunbookOutputPanel(run: RunbookSessionRun, mono: FontFamily, shownStep: Int? = null) {
    val step = run.steps.getOrNull(shownStep ?: run.currentIndex) ?: run.steps.lastOrNull()
    Column(
        Modifier.width(OUTPUT_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Stripped like every other rendering of a step's title: a runbook can arrive over sync, and
        // a bidi override in this header would reorder what the operator reads mid-run.
        val title = stripUnsafeFormatChars(step?.step?.title?.takeIf { it.isNotBlank() } ?: run.label)
        Txt(
            labelUppercase(stringResource(Res.string.runbook_run_output)) + " · " + title.uppercase(),
            color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        )
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(Skerry.colors.terminalBg)
                .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(9.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            val text = step?.let { outputText(it) }
            if (text.isNullOrEmpty()) {
                // "Nothing printed" is a claim about the command; a lost capture is a fact about the
                // terminal, and an operator reading an empty panel has to be able to tell them apart.
                val empty =
                    if (step?.outputLost == true) Res.string.runbook_run_output_lost
                    else Res.string.runbook_run_no_output
                Txt(stringResource(empty), color = Skerry.colors.faint, size = 11.5.sp, font = mono)
            } else {
                // Output comes from the remote host: bidi/format characters are stripped here too,
                // or a crafted log line could reorder what the operator reads before the next step.
                Txt(stripUnsafeFormatChars(text), color = Skerry.colors.textBright, size = 11.5.sp, font = mono, lineHeight = 17.sp)
            }
        }
    }
}

/** What the panel shows for a step: its output, a transfer's progress, or why it failed. */
@Composable
private fun outputText(state: RunbookStepState): String? = when {
    state.failure != null -> failureText(state.failure)
    state.transferredBytes != null -> stringResource(
        Res.string.runbook_transfer_of,
        humanSize(state.transferredBytes ?: 0),
        humanSize(state.totalBytes ?: 0),
    )
    else -> state.output
}

/**
 * What a step's status reads as. [UNREADABLE_STATUS] is not an exit code and must not be printed as
 * one: a shell's `$?` is 0..255, so "exit -1" would send the operator debugging their command when
 * what actually happened is that the terminal could not read what the host sent.
 */
@Composable
internal fun exitCodeText(code: Int): String =
    if (code == UNREADABLE_STATUS) stringResource(Res.string.runbook_panel_status_unreadable)
    else stringResource(Res.string.runbook_panel_exit_code, code)

/** Why a step failed, in words — a transfer has no exit code to show instead. */
@Composable
internal fun failureText(failure: RunbookStepFailure?): String = when (failure) {
    RunbookStepFailure.NoSftpChannel -> stringResource(Res.string.runbook_panel_no_sftp)
    is RunbookStepFailure.Transfer -> stringResource(Res.string.runbook_panel_transfer_failed, failure.message)
    null -> ""
}

/** The card under the step list: what this runbook did the last few times it ran. */
@Composable
internal fun RunbookHistoryRow(runner: RunbookRunner, mono: FontFamily) {
    // Re-read when the run ends: a finished run writes its own row, and the card is right there.
    // Keyed on `active`, not on `phase`: every confirmation pause flips the phase, and each flip
    // would decrypt and parse the whole run log again on the UI thread.
    val history = LocalRunbookHistory.current
    val runbookId = runner.runbook?.id
    val records = remember(runbookId, runner.active) {
        if (history == null || runbookId == null) emptyList() else history.forRunbook(runbookId)
    }
    RunbookHistoryCard(records, mono, Modifier.fillMaxWidth())
}

/** Status text at the right-hand end of a step row: a duration once there is one, a state until then. */
@Composable
internal fun stepStatusText(state: RunbookStepState): String {
    val duration = state.durationMillis
    if (duration != null && (state.status == RunbookStepStatus.SUCCEEDED || state.status == RunbookStepStatus.FAILED)) {
        val code = state.exitCode
        val text = runbookDurationText(duration)
        return if (state.status == RunbookStepStatus.FAILED && code != null && code != 0) {
            exitCodeText(code) + " · " + text
        } else {
            text
        }
    }
    return when (state.status) {
        RunbookStepStatus.PENDING -> stringResource(Res.string.runbook_status_waiting)
        RunbookStepStatus.AWAITING_CONFIRM -> stringResource(Res.string.runbook_status_confirm)
        RunbookStepStatus.RUNNING -> stringResource(Res.string.runbook_status_running)
        RunbookStepStatus.SKIPPED -> stringResource(Res.string.runbook_status_skipped)
        RunbookStepStatus.STOPPED -> stringResource(Res.string.runbook_status_stopped)
        // A settled step always has a duration (it is written with the status), so this is the
        // no-clock fallback — it must not claim the step is still going.
        RunbookStepStatus.SUCCEEDED -> stringResource(Res.string.runbook_panel_done)
        RunbookStepStatus.FAILED -> stringResource(Res.string.runbook_panel_failed)
    }
}

/** [RunbookDuration] in words — shared by the step rows and the history card. */
@Composable
internal fun runbookDurationText(millis: Long): String = when (val duration = runbookDuration(millis)) {
    is RunbookDuration.Seconds -> stringResource(Res.string.runbook_dur_seconds, duration.text)
    is RunbookDuration.Minutes -> stringResource(Res.string.runbook_dur_minutes, duration.minutes, duration.seconds)
}

@Composable
internal fun runPhaseLabel(phase: RunbookPhase, hadFailures: Boolean): String = when (phase) {
    RunbookPhase.AWAITING_CONFIRM -> stringResource(Res.string.runbook_panel_waiting)
    RunbookPhase.RUNNING -> stringResource(Res.string.runbook_panel_running)
    RunbookPhase.DONE ->
        if (hadFailures) stringResource(Res.string.runbook_panel_done_with_failures)
        else stringResource(Res.string.runbook_panel_done)
    RunbookPhase.FAILED -> stringResource(Res.string.runbook_panel_failed)
    RunbookPhase.STOPPED -> stringResource(Res.string.runbook_panel_stopped)
}

@Composable
internal fun runPhaseColor(phase: RunbookPhase, hadFailures: Boolean): Color = when (phase) {
    RunbookPhase.AWAITING_CONFIRM -> Skerry.colors.cyanBright
    RunbookPhase.RUNNING -> Skerry.colors.cyan
    RunbookPhase.DONE -> if (hadFailures) Skerry.colors.storm else Skerry.colors.moss
    RunbookPhase.FAILED -> Skerry.colors.sunset
    RunbookPhase.STOPPED -> Skerry.colors.dim
}

@Composable
internal fun runStatusColor(status: RunbookStepStatus): Color = when (status) {
    RunbookStepStatus.PENDING -> Skerry.colors.faint
    RunbookStepStatus.AWAITING_CONFIRM -> Skerry.colors.cyanBright
    RunbookStepStatus.RUNNING -> Skerry.colors.cyan
    RunbookStepStatus.SUCCEEDED -> Skerry.colors.moss
    RunbookStepStatus.FAILED -> Skerry.colors.sunset
    RunbookStepStatus.SKIPPED -> Skerry.colors.dim
    RunbookStepStatus.STOPPED -> Skerry.colors.dim
}

/** Icon for a settled step; `null` where the row shows the step's number instead. */
internal fun runStatusIcon(status: RunbookStepStatus): String? = when (status) {
    RunbookStepStatus.SUCCEEDED -> "check"
    RunbookStepStatus.FAILED -> "error"
    RunbookStepStatus.SKIPPED -> "skip_next"
    RunbookStepStatus.STOPPED -> "stop_circle"
    RunbookStepStatus.AWAITING_CONFIRM -> "pause_circle"
    RunbookStepStatus.PENDING, RunbookStepStatus.RUNNING -> null
}

internal fun phaseIcon(phase: RunbookPhase): String = when (phase) {
    RunbookPhase.AWAITING_CONFIRM -> "pause_circle"
    RunbookPhase.RUNNING -> "timer"
    RunbookPhase.DONE -> "check_circle"
    RunbookPhase.FAILED -> "error"
    RunbookPhase.STOPPED -> "stop_circle"
}
