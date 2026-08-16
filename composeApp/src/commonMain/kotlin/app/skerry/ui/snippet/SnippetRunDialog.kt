package app.skerry.ui.snippet

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
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_preview
import app.skerry.ui.generated.resources.lib_snippet_vars_recording_note
import app.skerry.ui.generated.resources.lib_snippet_vars_run
import app.skerry.ui.generated.resources.lib_snippet_vars_secret_note
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_untitled
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.ClippedNotice
import app.skerry.ui.design.CommandQuote
import app.skerry.ui.design.untrustedLabel

/**
 * Confirmation dialog for a snippet with `${{…}}` variables ([SnippetManager.pendingRun]): prompts
 * for user parameters, resolves clipboard/vault references, and shows the exact command line to be
 * sent (vault secrets masked) before anything reaches the terminal. Mandatory for every variable
 * snippet — a Teams-shared template with `${{clipboard}}` reads *this* user's clipboard, so the
 * run must be previewed, never implicit. Shared by desktop and mobile (same modal language as
 * [app.skerry.ui.design.ConfirmActionDialog]).
 *
 * Everything is captured when the dialog opens (machine values, clipboard, vault lookups) — what
 * is previewed is exactly what runs (TOCTOU rule, coding-guidelines §3).
 */
@Composable
internal fun SnippetRunDialog(manager: SnippetManager) {
    val request = manager.pendingRun ?: return
    // Keyed per request: a new run must not inherit the previous dialog's fields.
    key(request) {
        SnippetRunDialogContent(
            request = request,
            onConfirm = manager::confirmRun,
            onDismiss = manager::dismissRun,
        )
    }
}

/** Lines of the previewed command the confirmation shows before the quote clips it. */
private const val PREVIEW_QUOTE_LINES = 6

@Composable
private fun SnippetRunDialogContent(
    request: SnippetRunRequest,
    onConfirm: (line: String, params: Map<String, String>, secrets: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val variables = remember(request) { request.segments.filterIsInstance<SnippetSegment.Variable>() }
    // One draw per placeholder, shared by the preview and the sent line (uuid/random stability).
    val machine = remember(request) { SnippetTemplate.machineValues(request.segments, request.environment) }
    val values = rememberTemplateVariableValues(request, variables, request.initialParams)

    fun contextValue(variable: SnippetSegment.Variable, masked: Boolean): String = values.value(variable, masked)

    val preview = SnippetTemplate.assemble(request.segments, machine) { contextValue(it, masked = true) }
    val canRun = values.canRun
    val confirm = {
        // The vault secrets ride along so the production guard's confirmation — one dialog later on
        // a #prod host — can mask the same spans this dialog's preview masked.
        if (canRun) {
            onConfirm(
                SnippetTemplate.assemble(request.segments, machine) { contextValue(it, masked = false) },
                values.paramValues(),
                values.vaultSecrets(),
            )
        }
    }

    // A command with no text parameter — which is every command the one-tap gate diverts here —
    // leaves nothing inside the dialog to claim focus, so focus falls to the scrim. Name it, or the
    // dialog opens in silence for anyone reading it aloud.
    val takesFocus = values.paramNames.any { it !in values.paramChoices }
    val title = stringResource(Res.string.lib_snippets_run_title)
    val name = remember(request.snippet) { untrustedLabel(request.snippet.label) }.ifBlank { stringResource(Res.string.lib_snippets_untitled) }
    ModalScrim(onDismiss = onDismiss, label = if (takesFocus) null else "$title: $name") {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(title, color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Txt(
                name,
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                TemplateVariableFields(values)
                FieldLabel(stringResource(Res.string.lib_snippet_vars_preview))
                // The exact line this dialog will send. Quoted rather than drawn: assemble has
                // already dropped what reorders it, but the control bytes it keeps as the author's
                // own reach the shell's line editor, and one of them draws as nothing at all.
                // Keyed on what the block is about, not on its text: the text is rebuilt on every keystroke in
                // a parameter field, and a state reset per character makes the notice blink and the buttons
                // under it jump. `onFit` is what changes it, and it fires on every layout.
                var clipped by remember(request) { mutableStateOf(false) }
                CommandQuote(
                    preview,
                    visibleLines = PREVIEW_QUOTE_LINES,
                    label = stringResource(Res.string.lib_snippet_vars_preview),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Skerry.colors.terminalBg).padding(horizontal = 11.dp),
                    color = Skerry.colors.textBright,
                    size = 12.sp,
                    lineHeight = 17.sp,
                    padding = 9.dp,
                    onFit = { clipped = it == false },
                )
                // Announced only where the preview cannot change under it: with a parameter field
                // the notice counts characters that change on every keystroke, and a live region
                // would read a new line out per character typed.
                ClippedNotice(clipped, preview.length, announce = !takesFocus)
                if (values.vaultRefs.isNotEmpty()) {
                    Txt(stringResource(Res.string.lib_snippet_vars_secret_note), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
                }
                if (request.recording) {
                    Txt(stringResource(Res.string.lib_snippet_vars_recording_note), color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
                PrimaryButton(
                    stringResource(Res.string.lib_snippet_vars_run),
                    onClick = confirm,
                    enabled = canRun,
                    modifier = Modifier.testTag(UiTags.FORM_SAVE),
                )
            }
        }
    }
}
