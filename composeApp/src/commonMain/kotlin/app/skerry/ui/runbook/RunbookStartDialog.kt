package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_recording_note
import app.skerry.ui.generated.resources.runbook_panel_shell_note
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_title
import app.skerry.ui.generated.resources.runbook_step_n
import app.skerry.ui.generated.resources.runbook_steps
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.snippet.TemplateVariableFields
import app.skerry.ui.snippet.SecretPlaintextNotice
import app.skerry.ui.snippet.rememberTemplateVariableValues
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.ClippedNotice
import app.skerry.ui.design.CommandQuote
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.design.sanitizedFits
import app.skerry.ui.design.MAX_NOTE_CHARS

/**
 * Confirmation shown before a runbook starts: prompts for the `${{…}}` values its steps need and
 * lists every command that will run, resolved (vault secrets masked). The whole procedure is
 * previewed at once rather than step by step — the decision the user is making is "do I run *this*
 * on *this* host", and the per-step pauses come later, with the output already on screen.
 *
 * Values are captured here and handed to the run as one closure, so a clipboard that changes
 * mid-procedure can't rewrite step 5 (TOCTOU rule, coding-guidelines §3).
 */
@Composable
fun RunbookStartDialog(runner: RunbookRunner, onStarted: () -> Unit = {}) {
    val request = runner.pending ?: return
    // Keyed per request: a new run must not inherit the previous dialog's fields.
    key(request) {
        RunbookStartDialogContent(
            request = request,
            onConfirm = { values, secrets -> if (runner.confirmStart(secrets, values)) onStarted() },
            onDismiss = runner::dismissStart,
        )
    }
}

/** Lines of a step's command the confirmation shows before the quote clips it. */
private const val STEP_QUOTE_LINES = 4

@Composable
private fun RunbookStartDialogContent(
    request: RunbookStartRequest,
    onConfirm: (values: (SnippetSegment.Variable) -> String, secrets: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val variables = remember(request) { request.script.variables }
    val values = rememberTemplateVariableValues(request, variables)

    val canRun = values.canRun
    // Computed when the resolution changes, not on every keystroke: each call sanitizes the resolved
    // passwords afresh, and a dialog whose job is to contain a secret should not leave a copy of it
    // on the heap per character typed.
    val secrets = remember(values.vaultResolutions) { values.vaultSecrets() }
    val confirm = {
        // The values are read once, here, and handed over as a snapshot. The vault secrets ride
        // along so the production guard can mask them in its own confirmation.
        if (canRun) onConfirm(runbookValueSnapshot(variables) { values.value(it, masked = false) }, secrets)
    }

    // A runbook with no free-text parameter — the ordinary one — leaves nothing inside to claim
    // focus, so focus falls to the scrim. Name it, or the confirmation that lists every command
    // about to run opens in silence for anyone reading it aloud. Same rule as [SnippetRunDialog].
    val takesFocus = values.paramNames.any { it !in values.paramChoices }
    val title = stringResource(Res.string.runbook_run_title)
    val name = remember(request.runbook) { untrustedLabel(request.runbook.label) }.ifBlank { stringResource(Res.string.runbook_untitled) }
    ModalScrim(onDismiss = onDismiss, label = if (takesFocus) null else "$title: $name") {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(
                title, color = Skerry.colors.faint, size = 10.5.sp,
                weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            )
            Txt(
                name,
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            // Prose, not a label: a description of a few lines is the author's own note, and the
            // label filter would fold and cut it. The same call the host note draws through.
            val description = remember(request.runbook) {
                sanitizeServerText(request.runbook.description, MAX_NOTE_CHARS, allowNewlines = true)
            }
            if (description.isNotBlank()) {
                Txt(
                    description, color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // A description longer than the cap is cut, and this dialog is where it is read
                // before a run: cut in silence, the note the author wrote and the note on screen
                // are not the same note. Not announced — it is prose, not the line that will run.
                // Asked of the filter, not of the drawn length: the cut can land on a separator the
                // trailing trim then removes, and the note comes back under the cap reading whole.
                val whole = remember(request.runbook) {
                    sanitizedFits(request.runbook.description, MAX_NOTE_CHARS, allowNewlines = true)
                }
                ClippedNotice(clipped = !whole, fullLength = request.runbook.description.length, announce = false)
            }
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                TemplateVariableFields(values)
                FieldLabel(stringResource(Res.string.runbook_steps))
                request.runbook.steps.forEachIndexed { index, step ->
                    key(step.id) {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Txt(
                                    stringResource(Res.string.runbook_step_n, index + 1),
                                    color = Skerry.colors.faint, size = 10.5.sp,
                                )
                                val stepTitle = remember(step) { untrustedLabel(step.title) }
                                if (stepTitle.isNotBlank()) {
                                    // Stripped like the panel rows: a synced runbook must not be able
                                    // to reorder what the user reads before approving the run.
                                    Txt(stepTitle, color = Skerry.colors.text, size = 11.5.sp)
                                }
                                if (step.confirm) Sym("pause_circle", size = 13.sp, color = Skerry.colors.cyanBright)
                                if (step.continueOnError) Sym("skip_next", size = 13.sp, color = Skerry.colors.dim)
                                if ((step as? RunbookStep.Command)?.interactive == true) {
                                    Sym("touch_app", size = 13.sp, color = Skerry.colors.cyanBright)
                                }
                            }
                            // The line the user approves must read the way it will run. The
                            // reordering characters are already gone — assemble strips the literal
                            // text — but the control bytes it keeps as the author's own are not, and
                            // a step ending in a BEL draws as one that does not. The quote also
                            // bounds what it draws and says so, which a bare Txt cannot.
                            val line = request.script.resolve(index) { values.value(it, masked = true) }?.summaryLine().orEmpty()
                            // Keyed on what the block is about, not on its text: the text is rebuilt on every keystroke in
                            // a parameter field, and a state reset per character makes the notice blink and the buttons
                            // under it jump. `onFit` is what changes it, and it fires on every layout.
                            var clipped by remember(step.id) { mutableStateOf(false) }
                            CommandQuote(
                                line,
                                visibleLines = STEP_QUOTE_LINES,
                                // Named, because the dialog has one of these per step: a focus stop
                                // reached by Tab never passes through the caption beside it.
                                label = stringResource(Res.string.runbook_step_n, index + 1),
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Skerry.colors.terminalBg)
                                    .padding(horizontal = 11.dp),
                                color = Skerry.colors.textBright,
                                size = 12.sp,
                                lineHeight = 17.sp,
                                padding = 9.dp,
                                onFit = { clipped = it == false },
                            )
                            // Not announced per step: several clipped steps opening at once read as
                            // one run-on sentence. The notice is still drawn beside each block.
                            ClippedNotice(clipped, line.length, announce = false)
                        }
                    }
                }
                Txt(
                    stringResource(Res.string.runbook_panel_shell_note), color = Skerry.colors.faint,
                    size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
                SecretPlaintextNotice(secrets, topPadding = 8.dp)
                if (request.recording) {
                    Txt(
                        stringResource(Res.string.lib_snippet_vars_recording_note), color = Skerry.colors.sunset,
                        size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
                PrimaryButton(stringResource(Res.string.runbook_run), onClick = confirm, enabled = canRun)
            }
        }
    }
}
