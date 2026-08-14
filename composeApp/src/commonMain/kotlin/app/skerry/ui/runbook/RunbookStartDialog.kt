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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_recording_note
import app.skerry.ui.generated.resources.lib_snippet_vars_secret_note
import app.skerry.ui.generated.resources.runbook_panel_shell_note
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_title
import app.skerry.ui.generated.resources.runbook_step_n
import app.skerry.ui.generated.resources.runbook_steps
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.snippet.TemplateVariableFields
import app.skerry.ui.snippet.rememberTemplateVariableValues
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

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

@Composable
private fun RunbookStartDialogContent(
    request: RunbookStartRequest,
    onConfirm: (values: (SnippetSegment.Variable) -> String, secrets: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    val variables = remember(request) { request.script.variables }
    val values = rememberTemplateVariableValues(request, variables)

    val canRun = values.canRun
    val confirm = {
        // The values are read once, here, and handed over as a snapshot. The vault secrets ride
        // along so the production guard can mask them in its own confirmation.
        if (canRun) onConfirm(runbookValueSnapshot(variables) { values.value(it, masked = false) }, values.vaultSecrets())
    }

    ModalScrim(onDismiss = onDismiss) {
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
                stringResource(Res.string.runbook_run_title), color = Skerry.colors.faint, size = 10.5.sp,
                weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            )
            Txt(
                request.runbook.label.ifBlank { stringResource(Res.string.runbook_untitled) },
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (request.runbook.description.isNotBlank()) {
                Txt(
                    request.runbook.description, color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
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
                                if (step.title.isNotBlank()) {
                                    // Stripped like the panel rows: a synced runbook must not be able
                                    // to reorder what the user reads before approving the run.
                                    Txt(stripUnsafeFormatChars(step.title), color = Skerry.colors.text, size = 11.5.sp)
                                }
                                if (step.confirm) Sym("pause_circle", size = 13.sp, color = Skerry.colors.cyanBright)
                                if (step.continueOnError) Sym("skip_next", size = 13.sp, color = Skerry.colors.dim)
                                if ((step as? RunbookStep.Command)?.interactive == true) {
                                    Sym("touch_app", size = 13.sp, color = Skerry.colors.cyanBright)
                                }
                            }
                            Txt(
                                request.script.resolve(index) { values.value(it, masked = true) }?.summaryLine().orEmpty(),
                                color = Skerry.colors.textBright, size = 12.sp, font = mono, lineHeight = 17.sp,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Skerry.colors.terminalBg)
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                Txt(
                    stringResource(Res.string.runbook_panel_shell_note), color = Skerry.colors.faint,
                    size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
                if (values.vaultRefs.isNotEmpty()) {
                    Txt(
                        stringResource(Res.string.lib_snippet_vars_secret_note), color = Skerry.colors.faint,
                        size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
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
