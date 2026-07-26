package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.LocalFonts
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

@Composable
private fun SnippetRunDialogContent(
    request: SnippetRunRequest,
    onConfirm: (line: String, params: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    val variables = remember(request) { request.segments.filterIsInstance<SnippetSegment.Variable>() }
    // One draw per placeholder, shared by the preview and the sent line (uuid/random stability).
    val machine = remember(request) { SnippetTemplate.machineValues(request.segments, request.environment) }
    val values = rememberTemplateVariableValues(request, variables, request.initialParams)

    fun contextValue(variable: SnippetSegment.Variable, masked: Boolean): String = values.value(variable, masked)

    val preview = SnippetTemplate.assemble(request.segments, machine) { contextValue(it, masked = true) }
    val canRun = values.canRun
    val confirm = {
        if (canRun) onConfirm(SnippetTemplate.assemble(request.segments, machine) { contextValue(it, masked = false) }, values.paramValues())
    }

    ModalScrim(onDismiss = onDismiss) {
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
            Txt(stringResource(Res.string.lib_snippets_run_title), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Txt(
                request.snippet.label.ifBlank { stringResource(Res.string.lib_snippets_untitled) },
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                TemplateVariableFields(values)
                FieldLabel(stringResource(Res.string.lib_snippet_vars_preview))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Skerry.colors.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    Txt(preview, color = Skerry.colors.textBright, size = 12.sp, font = mono, lineHeight = 17.sp)
                }
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
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
                PrimaryButton(stringResource(Res.string.lib_snippet_vars_run), onClick = confirm, enabled = canRun)
            }
        }
    }
}
