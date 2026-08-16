package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.skerry.shared.runbook.RunbookScript
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.captureSnippetRunEnvironment
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalRunbookHistory
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.Chip
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_runs_on
import app.skerry.ui.generated.resources.lib_snippets_variables
import app.skerry.ui.generated.resources.runbook_delete
import app.skerry.ui.generated.resources.runbook_edit
import app.skerry.ui.generated.resources.runbook_history_last
import app.skerry.ui.generated.resources.runbook_policy
import app.skerry.ui.generated.resources.runbook_policy_stop
import app.skerry.ui.generated.resources.runbook_policy_watchdog
import app.skerry.ui.generated.resources.runbook_policy_watchdog_off
import app.skerry.ui.generated.resources.runbook_policy_watchdog_value
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_busy
import app.skerry.ui.generated.resources.runbook_run_needs_session
import app.skerry.ui.generated.resources.runbook_run_no_steps
import app.skerry.ui.generated.resources.runbook_step_confirm
import app.skerry.ui.generated.resources.runbook_step_continue_on_error
import app.skerry.ui.generated.resources.runbook_step_n
import app.skerry.ui.generated.resources.runbook_steps
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.host.HostSection
import app.skerry.ui.sftp.fileDateText
import app.skerry.ui.snippet.snippetTagLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Width of the runbook panel — wider than the snippet one: its rows are whole step lines. */
internal val RUNBOOK_PANEL_WIDTH = 460.dp

/**
 * Right-hand panel of the runbooks section: what the selected runbook does, under what policy, and
 * where it will run. Values for its `${{…}}` placeholders are not collected here — the start dialog
 * is the only place that reads the clipboard and the vault and previews the exact lines it sends
 * (coding-guidelines §3); this panel only names what will be asked for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunbookRunCard(
    entry: RunbookEntry,
    state: DesktopDesignState,
    mono: FontFamily,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val runbook = entry.runbook
    val runner = LocalRunbookRunner.current
    val sessions = LocalSessions.current
    // A runbook types into the pane in focus — the terminal the user is looking at.
    val session = sessions?.activeTerminal?.focusedPane
    val terminal = (session?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    val busy = runner?.active == true || runner?.pending != null
    // The variables the start dialog will ask for; drawn from the runbook only, no values involved.
    val variables = remember(runbook) {
        RunbookScript.of(runbook, captureSnippetRunEnvironment()).variables.map { it.name }.distinct()
    }
    val history = LocalRunbookHistory.current
    // Re-read when a run ends (the runner's phase changes) — the newest row is this runbook's own.
    val records = remember(runbook.id, runner?.phase) { history?.forRunbook(runbook.id).orEmpty() }
    // A run needs both halves of the session; keeping them in one value keeps the click handler flat.
    val target = if (session != null && terminal != null) runbookTarget(session.id, terminal, session.controller) else null
    val recording = terminal?.recording == true
    // A runbook with no steps is one the runner refuses; it only arrives by sync, and the phone's
    // sheet says so too — the two must not disagree about why Run is inert.
    val hasSteps = runbook.steps.isNotEmpty()
    val canRun = runner != null && target != null && !busy && hasSteps

    Column(
        Modifier.width(RUNBOOK_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Txt(
            stripUnsafeFormatChars(runbook.label).ifBlank { stringResource(Res.string.runbook_untitled) },
            color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (runbook.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                runbook.tags.forEach { tag -> key(tag) { Chip(snippetTagLabel(tag)) } }
            }
        }
        if (runbook.description.isNotBlank()) {
            Txt(
                stripUnsafeFormatChars(runbook.description), color = Skerry.colors.dim, size = 12.sp,
                lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        FieldLabel(labelUppercase(stringResource(Res.string.runbook_steps)), top = 0.dp, bottom = 7.dp)
        runbook.steps.forEachIndexed { index, step ->
            key(step.id) { StepPreviewRow(index, step, mono) }
        }

        if (variables.isNotEmpty()) {
            FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_variables)), top = 14.dp, bottom = 7.dp)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                variables.forEach { name -> key(name) { Chip(name) } }
            }
        }

        FieldLabel(labelUppercase(stringResource(Res.string.runbook_policy)), top = 14.dp, bottom = 7.dp)
        PolicyChips(entry)

        records.firstOrNull()?.let { last ->
            Txt(
                stringResource(Res.string.runbook_history_last, fileDateText(last.startedAt / 1_000)),
                color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }

        FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_runs_on)), top = 14.dp, bottom = 7.dp)
        // The section is app-level and can be open with no session at all; say why Run is inert
        // instead of leaving a button that quietly does nothing.
        val hint = when {
            !hasSteps -> stringResource(Res.string.runbook_run_no_steps)
            session == null || terminal == null -> stringResource(Res.string.runbook_run_needs_session)
            busy -> stringResource(Res.string.runbook_run_busy)
            else -> null
        }
        when {
            // Drawn for the eye; the button below announces the same string as its state, and a
            // second node would read it out again to a screen reader.
            hint != null -> Txt(
                hint, color = Skerry.colors.faint, size = 11.5.sp, lineHeight = 16.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
            session != null -> Chip(session.displayTitle, active = true)
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                stringResource(Res.string.runbook_run),
                onClick = {
                    // Leave the section once the run is accepted: the confirmation and the progress
                    // panel are read against the terminal's own output, which this pane covers.
                    if (target != null && runner?.requestStart(runbook, target, recording = recording) == true) {
                        state.showSection(HostSection.Terminal)
                    }
                },
                icon = "play_arrow",
                enabled = canRun,
                modifier = Modifier.weight(1f).semantics { hint?.let { stateDescription = it } },
            )
            GhostButton(stringResource(Res.string.runbook_edit), onClick = onEdit, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            GhostButton(
                stringResource(Res.string.runbook_delete),
                onClick = onDelete,
                fg = Skerry.colors.sunset,
                border = Skerry.colors.sunset.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One step as the panel lists it: number, name, what it runs, and the flags that change the run. */
@Composable
private fun StepPreviewRow(index: Int, step: RunbookStep, mono: FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.terminalBg)
            .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Txt(stringResource(Res.string.runbook_step_n, index + 1), color = Skerry.colors.faint, size = 10.5.sp)
            if (step.title.isNotBlank()) {
                Txt(stripUnsafeFormatChars(step.title), color = Skerry.colors.text, size = 11.5.sp)
            }
            Box(Modifier.weight(1f))
            // The two flags that change how the run behaves, in the same icons the progress list uses.
            if (step.confirm) Sym("pause_circle", size = 13.sp, color = Skerry.colors.cyanBright)
            if (step.continueOnError) Sym("skip_next", size = 13.sp, color = Skerry.colors.dim)
        }
        // As written, never as resolved: a `${{vault}}` value has no business on screen.
        Txt(
            stripUnsafeFormatChars(step.summaryLine()), color = Skerry.colors.textBright, size = 11.5.sp,
            font = mono, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The run policy as the chips the mockup shows above a run: what stops it, what watches it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyChips(entry: RunbookEntry) {
    val policy = entry.runbook.policy
    val watchdog = if (policy.watchdogMinutes > 0) {
        stringResource(Res.string.runbook_policy_watchdog) + " " +
            stringResource(Res.string.runbook_policy_watchdog_value, policy.watchdogMinutes)
    } else {
        stringResource(Res.string.runbook_policy_watchdog) + " " + stringResource(Res.string.runbook_policy_watchdog_off)
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (policy.stopOnFirstFailure) Chip(stringResource(Res.string.runbook_policy_stop))
        Chip(watchdog)
    }
}
