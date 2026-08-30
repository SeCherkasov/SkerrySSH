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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SkerryVerticalScrollbar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_close
import app.skerry.ui.generated.resources.runbook_panel_collapse
import app.skerry.ui.generated.resources.runbook_panel_complete_step
import app.skerry.ui.generated.resources.runbook_panel_done
import app.skerry.ui.generated.resources.runbook_panel_done_with_failures
import app.skerry.ui.generated.resources.runbook_panel_expand
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
import app.skerry.ui.design.CommandLine
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.remember

/**
 * Live progress of the running runbook, docked over the terminal. Deliberately *not* modal: the
 * whole point is that the user reads the command's real output while deciding whether to go on, so
 * the panel sits beside it and the terminal underneath stays usable (scroll, select, type).
 *
 * On a phone the panel is nearly as wide as the screen and sits right over the live output — the
 * opposite of its purpose — so it collapses to its header line. It reopens by itself whenever the
 * run needs the user: a confirmation pause and a finished run have their only buttons here, a
 * stalled step its warning, and a failed step its red row. An interactive step deliberately does
 * not reopen it — a collapsed panel over a full-screen TUI is exactly what the user collapsed it
 * for. The collapse flag lives on [RunbookSessionRun], so it survives the panel leaving
 * composition (a tab switch) and dies with the run.
 *
 * Renders nothing when no run is in flight, so a caller can place it unconditionally.
 */
@Composable
fun RunbookRunPanel(runner: RunbookRunner, run: RunbookSessionRun, modifier: Modifier = Modifier) {
    val phase = runner.phase ?: return
    val runbook = runner.runbook ?: return
    val mono = LocalFonts.current.mono
    val collapsed = run.panelCollapsed
    val stalled = run.steps.getOrNull(run.currentIndex)?.stalled == true
    // Reopens on a signal the run has not shown yet — another pause, the end, a step going quiet.
    // The comparison is against the run's own memory, not the effect's keys: the effect re-runs on
    // every re-entry into composition (a tab switch back), and acting on the bare condition there
    // would undo a deliberate re-collapse of the very signal the user already saw. A signal that
    // fired while the panel was off-screen is still unseen and still reopens it on return. The one
    // string that can repeat is the same step stalling again after output resumed: that warning was
    // dismissed once and stays dismissed — a step printing slightly slower than the watchdog must
    // not yank the panel open every cycle (the announcer may still voice it; a spoken sentence
    // costs less than a panel over the output).
    val signal = when {
        phase != RunbookPhase.RUNNING -> "${phase.name}:${run.currentIndex}"
        stalled -> "stalled:${run.currentIndex}"
        else -> null
    }
    LaunchedEffect(signal) {
        if (signal != null && signal != run.panelSeenSignal) {
            run.panelCollapsed = false
            run.panelSeenSignal = signal
        }
    }
    // A tolerated failure never leaves RUNNING (continueOnError, stopOnFirstFailure=false), so the
    // phase key above never fires for it — each failure beyond the count already shown is its own
    // reopen signal, or a phone-sized run could fail step after step behind a collapsed header.
    val failedSteps = run.steps.count { it.status == RunbookStepStatus.FAILED }
    LaunchedEffect(failedSteps) {
        if (failedSteps > run.panelSeenFailures) run.panelCollapsed = false
        run.panelSeenFailures = failedSteps
    }

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
                    // Stripped like every other surface showing this label: a runbook can arrive
                    // over sync, and while collapsed this line is the whole panel.
                    remember(runbook) { untrustedLabel(runbook.label) }.ifBlank { stringResource(Res.string.runbook_untitled) },
                    color = Skerry.colors.textBright, size = 13.sp, weight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Txt(
                    runPhaseLabel(phase, runner.hadFailures) + " · " +
                        stringResource(Res.string.runbook_panel_progress, run.finishedCount, run.steps.size),
                    color = runPhaseColor(phase, runner.hadFailures), size = 11.sp,
                )
            }
            IconBtn(
                name = if (collapsed) "expand_less" else "expand_more",
                onClick = { run.panelCollapsed = !collapsed },
                box = 24, icon = 16.sp,
                label = stringResource(
                    if (collapsed) Res.string.runbook_panel_expand else Res.string.runbook_panel_collapse,
                ),
            )
        }

        // Held outside the collapse branch, or every expand would land the list back at the top.
        val stepScroll = rememberScrollState()
        if (!collapsed) {
            Box(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(stepScroll).padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    run.steps.forEach { state -> key(state) { StepRow(state, mono) } }
                }
                SkerryVerticalScrollbar(
                    scrollState = stepScroll,
                    modifier = Modifier.align(Alignment.CenterEnd).matchParentSize().padding(top = 2.dp, bottom = 2.dp, end = 1.dp),
                )
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
                    RunbookPhase.RUNNING -> {
                        // An interactive step has no probe to report it done — the user says so here.
                        if (run.steps.getOrNull(run.currentIndex)?.status == RunbookStepStatus.AWAITING_COMPLETE) {
                            PrimaryButton(stringResource(Res.string.runbook_panel_complete_step), onClick = runner::completeStep)
                            GhostButton(stringResource(Res.string.runbook_panel_skip_step), onClick = runner::skipStep)
                        }
                        GhostButton(
                            stringResource(Res.string.runbook_panel_stop), onClick = runner::stop,
                            fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f),
                        )
                    }
                    else -> GhostButton(stringResource(Res.string.runbook_panel_close), onClick = runner::close)
                }
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
            .background(
                if (state.status == RunbookStepStatus.AWAITING_CONFIRM || state.status == RunbookStepStatus.AWAITING_COMPLETE) {
                    Skerry.colors.cyan10
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(statusIcon(state.status), size = 14.sp, color = color)
        Column(Modifier.weight(1f)) {
            // Filtered and spelled out: this row is the last thing read before "Run this step"
            // is clicked, and a runbook can arrive over sync — it must not be able to render one
            // command and run another (Trojan Source), nor to hide a character it will send.
            val title = remember(state.step) { untrustedLabel(state.step.title) }
            if (title.isNotBlank()) {
                Txt(title, color = Skerry.colors.text, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // The step as written, not as resolved: a `${{vault}}` value has no business on screen.
            CommandLine(
                state.step.summaryLine(),
                color = if (title.isNotBlank()) Skerry.colors.faint else Skerry.colors.text,
                size = if (title.isNotBlank()) 10.5.sp else 12.sp,
            )
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
        val code = state.exitCode
        if (code != null) {
            Box(Modifier.padding(top = 1.dp)) {
                Txt(exitCodeText(code), color = color, size = 10.5.sp, font = mono)
            }
        } else {
            // The row's state in words, not only in icon and colour: the docked panel is read by
            // screen readers too, and "waiting for you" is exactly what they must not miss.
            Box(Modifier.padding(top = 1.dp)) {
                Txt(stepStatusText(state), color = color, size = 10.5.sp, font = mono, maxLines = 1)
            }
        }
    }
}

private fun statusIcon(status: RunbookStepStatus): String = when (status) {
    RunbookStepStatus.PENDING -> "radio_button_unchecked"
    RunbookStepStatus.AWAITING_CONFIRM -> "pause_circle"
    RunbookStepStatus.RUNNING -> "play_circle"
    RunbookStepStatus.AWAITING_COMPLETE -> "touch_app"
    RunbookStepStatus.SUCCEEDED -> "check_circle"
    RunbookStepStatus.FAILED -> "error"
    RunbookStepStatus.SKIPPED -> "skip_next"
    RunbookStepStatus.STOPPED -> "stop_circle"
}

