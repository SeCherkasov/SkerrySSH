package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_close
import app.skerry.ui.generated.resources.runbook_panel_done
import app.skerry.ui.generated.resources.runbook_panel_done_with_failures
import app.skerry.ui.generated.resources.runbook_panel_failed
import app.skerry.ui.generated.resources.runbook_panel_progress
import app.skerry.ui.generated.resources.runbook_panel_run_step
import app.skerry.ui.generated.resources.runbook_panel_running
import app.skerry.ui.generated.resources.runbook_panel_stalled
import app.skerry.ui.generated.resources.runbook_panel_skip_step
import app.skerry.ui.generated.resources.runbook_panel_stop
import app.skerry.ui.generated.resources.runbook_panel_stopped
import app.skerry.ui.generated.resources.runbook_panel_waiting
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Live progress of the running runbook, docked over the terminal. Deliberately *not* modal: the
 * whole point is that the user reads the command's real output while deciding whether to go on, so
 * the panel sits beside it and the terminal underneath stays usable (scroll, select, type).
 *
 * Renders nothing when no run is in flight, so a caller can place it unconditionally.
 */
@Composable
fun RunbookRunPanel(runner: RunbookRunner, run: RunbookSessionRun, modifier: Modifier = Modifier) {
    val phase = runner.phase ?: return
    val runbook = runner.runbook ?: return
    val mono = LocalFonts.current.mono

    Column(
        modifier
            .width(320.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(10.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("checklist", size = 16.sp, color = runPhaseColor(phase, runner.hadFailures))
            Column(Modifier.weight(1f)) {
                Txt(
                    runbook.label.ifBlank { stringResource(Res.string.runbook_untitled) },
                    color = Skerry.colors.textBright, size = 13.sp, weight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Txt(
                    runPhaseLabel(phase, runner.hadFailures) + " · " +
                        stringResource(Res.string.runbook_panel_progress, run.finishedCount, run.steps.size),
                    color = runPhaseColor(phase, runner.hadFailures), size = 11.sp,
                )
            }
        }

        Column(
            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            run.steps.forEach { state -> key(state) { StepRow(state, mono) } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (phase) {
                RunbookPhase.AWAITING_CONFIRM -> {
                    PrimaryButton(stringResource(Res.string.runbook_panel_run_step), onClick = runner::confirmStep)
                    GhostButton(stringResource(Res.string.runbook_panel_skip_step), onClick = runner::skipStep)
                    GhostButton(
                        stringResource(Res.string.runbook_panel_stop), onClick = runner::stop,
                        fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f),
                    )
                }
                RunbookPhase.RUNNING -> GhostButton(
                    stringResource(Res.string.runbook_panel_stop), onClick = runner::stop,
                    fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f),
                )
                else -> GhostButton(stringResource(Res.string.runbook_panel_close), onClick = runner::close)
            }
        }
    }
}

@Composable
private fun StepRow(state: RunbookStepState, mono: androidx.compose.ui.text.font.FontFamily) {
    val color = runStatusColor(state.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (state.status == RunbookStepStatus.AWAITING_CONFIRM) Skerry.colors.cyan10 else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(statusIcon(state.status), size = 14.sp, color = color)
        Column(Modifier.weight(1f)) {
            // Bidi/format characters are stripped: this row is the last thing read before "Run this
            // step" is clicked, and a runbook can arrive over sync — it must not be able to render
            // one command and run another (Trojan Source).
            val title = stripUnsafeFormatChars(state.step.title.ifBlank { state.step.summaryLine() })
            Txt(title, color = Skerry.colors.text, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The step as written, not as resolved: a `${{vault}}` value has no business on screen.
            if (state.step.title.isNotBlank()) {
                Txt(
                    stripUnsafeFormatChars(state.step.summaryLine()), color = Skerry.colors.faint, size = 10.5.sp,
                    font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // The step is not ended on this — it may be a legitimate `sleep` — but a run that will
            // never finish looks exactly like one still working, and only the terminal can tell.
            if (state.stalled) {
                Txt(
                    stringResource(Res.string.runbook_panel_stalled),
                    color = Skerry.colors.amber, size = 10.5.sp,
                )
            }
            // A transfer step has no exit code to fail with, so without this the row would go red
            // and say nothing at all about why.
            state.failure?.let { failure ->
                Txt(failureText(failure), color = Skerry.colors.sunset, size = 10.5.sp, lineHeight = 14.sp)
            }
        }
        state.exitCode?.let { code ->
            Box(Modifier.padding(top = 1.dp)) {
                Txt(exitCodeText(code), color = color, size = 10.5.sp, font = mono)
            }
        }
    }
}

private fun statusIcon(status: RunbookStepStatus): String = when (status) {
    RunbookStepStatus.PENDING -> "radio_button_unchecked"
    RunbookStepStatus.AWAITING_CONFIRM -> "pause_circle"
    RunbookStepStatus.RUNNING -> "play_circle"
    RunbookStepStatus.SUCCEEDED -> "check_circle"
    RunbookStepStatus.FAILED -> "error"
    RunbookStepStatus.SKIPPED -> "skip_next"
    RunbookStepStatus.STOPPED -> "stop_circle"
}

