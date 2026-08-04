package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.runbook.RunbookParallelism
import app.skerry.shared.snippet.stripUnsafeFormatChars
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
import app.skerry.ui.generated.resources.runbook_panel_stopped
import app.skerry.ui.generated.resources.runbook_panel_transfer_failed
import app.skerry.ui.generated.resources.runbook_panel_waiting
import app.skerry.ui.generated.resources.runbook_panel_waiting_host
import app.skerry.ui.generated.resources.runbook_policy_parallel_all
import app.skerry.ui.generated.resources.runbook_policy_parallel_one
import app.skerry.ui.generated.resources.runbook_run_hosts
import app.skerry.ui.generated.resources.runbook_run_no_output
import app.skerry.ui.generated.resources.runbook_run_output
import app.skerry.ui.generated.resources.runbook_run_parallelism
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
 * What the step in flight has printed on this host, read out of the terminal between the echo of
 * the typed line and the marker that ended it ([runbookStepOutput]). A transfer prints nothing at
 * all, so it reports its bytes instead.
 *
 * The terminal itself is a chevron away and holds everything, including what scrolled out of this
 * panel — the panel is the readable part, not the record.
 */
@Composable
internal fun RunbookOutputPanel(host: RunbookHostRun, mono: FontFamily) {
    val step = host.steps.getOrNull(host.currentIndex) ?: host.steps.lastOrNull()
    Column(
        Modifier.width(OUTPUT_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(
            labelUppercase(stringResource(Res.string.runbook_run_output)) + " · " + host.label.uppercase(),
            color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        )
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(Skerry.colors.terminalBg)
                .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(9.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            val text = step?.let { outputText(it) }
            if (text.isNullOrEmpty()) {
                Txt(stringResource(Res.string.runbook_run_no_output), color = Skerry.colors.faint, size = 11.5.sp, font = mono)
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

@Composable
private fun failureText(failure: RunbookStepFailure?): String = when (failure) {
    RunbookStepFailure.NoSftpChannel -> stringResource(Res.string.runbook_panel_no_sftp)
    is RunbookStepFailure.Transfer -> stringResource(Res.string.runbook_panel_transfer_failed, failure.message)
    null -> ""
}

/** The cards under the step list: which hosts the run covers and how it is spread across them. */
@Composable
internal fun RunbookRunCards(
    runner: RunbookRunner,
    selected: RunbookHostRun,
    mono: FontFamily,
    onPickHost: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(Skerry.colors.card)
                .border(1.dp, Skerry.colors.line, RoundedCornerShape(11.dp)).padding(14.dp),
        ) {
            Txt(
                stringResource(Res.string.runbook_run_hosts), color = Skerry.colors.text, size = 12.5.sp,
                weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp),
            )
            runner.hosts.forEach { host ->
                key(host.sessionId) {
                    RunbookHostRow(host, host === selected, mono) { onPickHost(host.sessionId) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(stringResource(Res.string.runbook_run_parallelism), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.weight(1f))
                Txt(
                    when (runner.runbook?.policy?.parallelism) {
                        RunbookParallelism.ALL_HOSTS_AT_ONCE -> stringResource(Res.string.runbook_policy_parallel_all)
                        else -> stringResource(Res.string.runbook_policy_parallel_one)
                    },
                    color = Skerry.colors.text, size = 11.5.sp,
                )
            }
        }
    }
}

/** Status text at the right-hand end of a step row: a duration once there is one, a state until then. */
@Composable
internal fun stepStatusText(state: RunbookStepState): String {
    val duration = state.durationMillis
    if (duration != null && (state.status == RunbookStepStatus.SUCCEEDED || state.status == RunbookStepStatus.FAILED)) {
        val code = state.exitCode
        val text = durationText(duration)
        return if (state.status == RunbookStepStatus.FAILED && code != null && code != 0) {
            stringResource(Res.string.runbook_panel_exit_code, code) + " · " + text
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
        RunbookStepStatus.SUCCEEDED, RunbookStepStatus.FAILED -> stringResource(Res.string.runbook_status_running)
    }
}

/** [RunbookDuration] in words. */
@Composable
private fun durationText(millis: Long): String = when (val duration = runbookDuration(millis)) {
    is RunbookDuration.Seconds -> stringResource(Res.string.runbook_dur_seconds, duration.text)
    is RunbookDuration.Minutes -> stringResource(Res.string.runbook_dur_minutes, duration.minutes, duration.seconds)
}

@Composable
internal fun runPhaseLabel(phase: RunbookPhase, hadFailures: Boolean): String = when (phase) {
    RunbookPhase.WAITING -> stringResource(Res.string.runbook_panel_waiting_host)
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
    RunbookPhase.WAITING -> Skerry.colors.dim
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
    RunbookPhase.WAITING, RunbookPhase.AWAITING_CONFIRM -> "pause_circle"
    RunbookPhase.RUNNING -> "timer"
    RunbookPhase.DONE -> "check_circle"
    RunbookPhase.FAILED -> "error"
    RunbookPhase.STOPPED -> "stop_circle"
}
