package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.Chip
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_close
import app.skerry.ui.generated.resources.runbook_panel_complete_step
import app.skerry.ui.generated.resources.runbook_panel_run_step
import app.skerry.ui.generated.resources.runbook_panel_skip_step
import app.skerry.ui.generated.resources.runbook_panel_stalled
import app.skerry.ui.generated.resources.runbook_panel_stop
import app.skerry.ui.generated.resources.runbook_policy_stop
import app.skerry.ui.generated.resources.runbook_policy_watchdog
import app.skerry.ui.generated.resources.runbook_policy_watchdog_value
import app.skerry.ui.generated.resources.runbook_run_back
import app.skerry.ui.generated.resources.runbook_run_kind
import app.skerry.ui.generated.resources.runbook_run_step_of
import app.skerry.ui.generated.resources.runbook_steps_total
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.SessionView
import app.skerry.ui.terminal.WorkBar
import app.skerry.ui.terminal.WorkBarLabel
import app.skerry.ui.terminal.WorkBarLeading
import app.skerry.ui.design.CommandLine
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The run screen: a runbook in flight, filling the work area of every tab it touches. The step list
 * on the left is what the run is doing, the output panel on the right is what the current step has
 * printed, and the cards underneath are the run's own shape — which hosts it covers, under what
 * policy.
 *
 * The terminal underneath stays live and a chevron away: the run types into it, and it remains the
 * full record of what happened. This screen is the readable summary of that, not a replacement.
 */
@Composable
fun RunbookRunView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    val runner = LocalRunbookRunner.current
    val back: () -> Unit = { sessions?.setActiveView(SessionView.Terminal) }
    // Closing a finished run leaves this screen with nothing to show, so it hands the tab back to
    // the terminal in the same gesture rather than leaving an empty frame in front of the user.
    val closeAndLeave: () -> Unit = { runner?.close(); back() }
    val runbook = runner?.runbook
    // The run of this tab, never whichever run the app happens to hold: a finished run left open in
    // one tab must not put another tab's live steps — and a live Stop button — on this screen.
    val run = runner?.runInActiveTab(sessions)
    if (runner == null || runbook == null || run == null) {
        // Nothing to show: the run was closed while this screen was up (vault lock, Close elsewhere),
        // or it belongs to another tab. The tab goes back to its terminal instead of standing on an
        // empty frame the user has to leave by hand.
        LaunchedEffect(Unit) { back() }
        RunbookRunFrame(state, label = null, onBack = back, actions = {}) {}
        return
    }
    val mono = LocalFonts.current.mono
    // Which step's output the panel shows; null follows the run, so a step that is still going is
    // read live and a finished run rests on its last step until another one is clicked.
    var shownStep by remember(run) { mutableStateOf<Int?>(null) }

    RunbookRunFrame(
        state = state,
        label = WorkBarLabel.Solo(
            title = remember(runbook) { untrustedLabel(runbook.label) }.ifBlank { stringResource(Res.string.runbook_untitled) },
            subtitle = stringResource(Res.string.runbook_run_kind) + " · " +
                pluralStringResource(Res.plurals.runbook_steps_total, runbook.steps.size, runbook.steps.size) +
                " · " + run.label,
            status = SessionStatus.Live,
        ),
        onBack = back,
        actions = { RunbookRunActions(runner, run, closeAndLeave) },
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RunbookStepList(run, shownStep) { index -> shownStep = index }
                RunbookPolicyRow(runner)
                RunbookHistoryRow(runner, mono)
            }
            VLine(Skerry.colors.line)
            RunbookOutputPanel(run, mono, shownStep)
        }
    }
}

/**
 * The chrome the run screen shares with the terminal — the same bar, so the two read as one window.
 * No hosts sidebar: this view fills the work area (as SFTP and the monitor do), and the chevron
 * leaves for the terminal rather than toggling a panel that isn't there.
 */
@Composable
private fun RunbookRunFrame(
    state: DesktopDesignState,
    label: WorkBarLabel?,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        WorkBar(
            label = label,
            tabKey = state.section,
            leading = WorkBarLeading.back(Res.string.runbook_run_back, onBack),
            onPickHost = null,
            actions = actions,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** Where the run stands, and what can be done about it — the right-hand end of the bar. */
@Composable
private fun RunbookRunActions(runner: RunbookRunner, run: RunbookSessionRun, onClose: () -> Unit) {
    val phase = runner.phase ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Skerry.colors.surface2)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Sym(phaseIcon(phase), size = 13.sp, color = runPhaseColor(phase, runner.hadFailures))
            Txt(
                runPhaseLabel(phase, runner.hadFailures) + " · " +
                    stringResource(Res.string.runbook_run_step_of, run.currentIndex + 1, run.steps.size),
                color = runPhaseColor(phase, runner.hadFailures), size = 11.5.sp,
            )
        }
        when (phase) {
            RunbookPhase.AWAITING_CONFIRM -> {
                PrimaryButton(stringResource(Res.string.runbook_panel_run_step), onClick = runner::confirmStep)
                GhostButton(stringResource(Res.string.runbook_panel_skip_step), onClick = runner::skipStep)
                GhostButton(
                    stringResource(Res.string.runbook_panel_stop), onClick = runner::stop, icon = "stop_circle",
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
                    stringResource(Res.string.runbook_panel_stop), onClick = runner::stop, icon = "stop_circle",
                    fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f),
                )
            }
            else -> GhostButton(stringResource(Res.string.runbook_panel_close), onClick = onClose)
        }
    }
}

/** The steps of [host], as the mockup lists them: state, name, line, and how long it took. */
@Composable
private fun RunbookStepList(run: RunbookSessionRun, shown: Int?, onShow: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(11.dp)),
    ) {
        run.steps.forEachIndexed { index, step ->
            key(step) {
                if (index > 0) HLine()
                RunbookStepRow(step, shown == index || (shown == null && index == run.currentIndex)) {
                    onShow(index)
                }
            }
        }
    }
}

@Composable
private fun RunbookStepRow(state: RunbookStepState, shown: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (shown) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepMarker(state)
        Column(Modifier.weight(1f)) {
            // Filtered and spelled out: this row is the last thing read before a step is approved,
            // and a runbook can arrive over sync — it must not be able to render one command and run
            // another (Trojan Source), nor to hide a character it will send.
            val title = remember(state.step) { untrustedLabel(state.step.title) }
            if (title.isNotBlank()) {
                Txt(
                    title, color = Skerry.colors.textBright, size = 12.5.sp, weight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // The step as written, never as resolved: a `${{vault}}` value has no business on screen.
            CommandLine(
                state.step.summaryLine(),
                color = if (title.isNotBlank()) Skerry.colors.faint else Skerry.colors.textBright,
                size = if (title.isNotBlank()) 11.sp else 12.5.sp,
                maxLines = 2,
                modifier = if (title.isNotBlank()) Modifier.padding(top = 3.dp) else Modifier,
            )
            if (state.stalled) {
                Txt(
                    stringResource(Res.string.runbook_panel_stalled), color = Skerry.colors.amber,
                    size = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        StepStatusChip(state)
    }
}

/** The step's place in the list: a tick once it is done, its number until then. */
@Composable
private fun StepMarker(state: RunbookStepState) {
    val color = runStatusColor(state.status)
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.12f)).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val icon = runStatusIcon(state.status)
        if (icon != null) {
            Sym(icon, size = 14.sp, color = color)
        } else {
            Txt("${state.index + 1}", color = color, size = 11.sp, weight = FontWeight.SemiBold)
        }
    }
}

/** Right-hand end of a step row: its duration once it has one, its state until then. */
@Composable
private fun StepStatusChip(state: RunbookStepState) {
    val color = runStatusColor(state.status)
    val text = stepStatusText(state)
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Txt(text, color = color, size = 11.sp)
    }
}

/** The run's policy, as the chips the mockup puts under the step list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunbookPolicyRow(runner: RunbookRunner) {
    val policy = runner.runbook?.policy ?: return
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (policy.stopOnFirstFailure) Chip(stringResource(Res.string.runbook_policy_stop))
        if (policy.watchdogMinutes > 0) {
            Chip(
                stringResource(Res.string.runbook_policy_watchdog) + " " +
                    stringResource(Res.string.runbook_policy_watchdog_value, policy.watchdogMinutes),
            )
        }
    }
}
