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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.snippet.sanitizeSnippetValue
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_clipboard
import app.skerry.ui.generated.resources.lib_snippet_vars_clipboard_empty
import app.skerry.ui.generated.resources.lib_snippet_vars_preview
import app.skerry.ui.generated.resources.lib_snippet_vars_recording_note
import app.skerry.ui.generated.resources.lib_snippet_vars_run
import app.skerry.ui.generated.resources.lib_snippet_vars_secret_note
import app.skerry.ui.generated.resources.lib_snippet_vars_vault
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_missing
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_not_password
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_untitled
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.terminal.fetchSystemClipboardText
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

/** Vault reference resolution, done once at dialog open. */
private sealed interface VaultRef {
    data class Ok(val secret: String) : VaultRef
    data object Missing : VaultRef
    data object NotAPassword : VaultRef
}

private fun resolveVaultRef(name: String, credentials: CredentialManagerController?): VaultRef {
    val entry = credentials?.credentials?.firstOrNull { it.label == name } ?: return VaultRef.Missing
    val password = entry.secret as? CredentialSecret.Password ?: return VaultRef.NotAPassword
    return VaultRef.Ok(password.password)
}

@Composable
private fun SnippetRunDialogContent(
    request: SnippetRunRequest,
    onConfirm: (line: String, params: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    val clipboard = LocalClipboard.current
    val credentials = LocalCredentials.current

    val variables = remember(request) { request.segments.filterIsInstance<SnippetSegment.Variable>() }
    // One draw per placeholder, shared by the preview and the sent line (uuid/random stability).
    val machine = remember(request) { SnippetTemplate.machineValues(request.segments, request.environment) }

    // Prompted parameters in first-appearance order; prefilled with the previous run's value, else
    // the inline default (`${{name:default}}`).
    val paramNames = remember(request) {
        variables.filter { it.kind == SnippetVariableKind.PARAM }.map { it.name }.distinct()
    }
    val paramValues = remember(request) {
        mutableStateMapOf<String, String>().apply {
            paramNames.forEach { name ->
                val default = variables.firstOrNull { it.kind == SnippetVariableKind.PARAM && it.name == name }?.format
                put(name, request.initialParams[name] ?: default ?: "")
            }
        }
    }

    // Vault references resolved once at open, against the credentials available right now.
    val vaultRefs = remember(request) {
        variables.filter { it.kind == SnippetVariableKind.VAULT }.map { it.format.orEmpty() }.distinct()
    }
    val vaultResolutions = remember(request) { vaultRefs.associateWith { resolveVaultRef(it, credentials) } }

    // Clipboard is read only when the template actually references it; `null` = still loading.
    val needsClipboard = remember(request) { variables.any { it.kind == SnippetVariableKind.CLIPBOARD } }
    var clipboardValue by remember(request) { mutableStateOf<String?>(null) }
    if (needsClipboard) {
        LaunchedEffect(request) { clipboardValue = fetchSystemClipboardText(clipboard).orEmpty() }
    }

    fun contextValue(variable: SnippetSegment.Variable, masked: Boolean): String = when (variable.kind) {
        SnippetVariableKind.CLIPBOARD -> clipboardValue.orEmpty()
        SnippetVariableKind.VAULT -> when (val ref = vaultResolutions[variable.format.orEmpty()]) {
            is VaultRef.Ok -> if (masked) SECRET_MASK else ref.secret
            else -> ""
        }
        SnippetVariableKind.PARAM -> paramValues[variable.name].orEmpty()
        else -> "" // machine kinds come from [machine]
    }

    val preview = SnippetTemplate.assemble(request.segments, machine) { contextValue(it, masked = true) }
    val vaultOk = vaultResolutions.values.all { it is VaultRef.Ok }
    val canRun = vaultOk && (!needsClipboard || clipboardValue != null)
    val confirm = {
        if (canRun) onConfirm(SnippetTemplate.assemble(request.segments, machine) { contextValue(it, masked = false) }, paramValues.toMap())
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
                val firstFieldFocus = remember { FocusRequester() }
                paramNames.forEachIndexed { index, name ->
                    key(name) {
                        FieldLabel(name)
                        ParamField(
                            value = paramValues[name].orEmpty(),
                            onChange = { paramValues[name] = sanitizeSnippetValue(it) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFieldFocus) else Modifier,
                        )
                    }
                }
                if (paramNames.isNotEmpty()) {
                    LaunchedEffect(Unit) { firstFieldFocus.requestFocus() }
                }
                if (needsClipboard) {
                    FieldLabel(stringResource(Res.string.lib_snippet_vars_clipboard))
                    val shown = clipboardValue?.let { sanitizeSnippetValue(it) }
                    Txt(
                        when {
                            shown == null -> "…"
                            shown.isEmpty() -> stringResource(Res.string.lib_snippet_vars_clipboard_empty)
                            else -> shown
                        },
                        color = Skerry.colors.dim, size = 11.5.sp, font = mono, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (vaultRefs.isNotEmpty()) {
                    FieldLabel(stringResource(Res.string.lib_snippet_vars_vault))
                    vaultRefs.forEach { name ->
                        key(name) {
                            when (vaultResolutions[name]) {
                                is VaultRef.Ok -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Txt(name, color = Skerry.colors.text, size = 11.5.sp, font = mono)
                                    Txt(SECRET_MASK, color = Skerry.colors.faint, size = 11.5.sp, font = mono)
                                }
                                VaultRef.NotAPassword ->
                                    Txt(stringResource(Res.string.lib_snippet_vars_vault_not_password, name), color = Skerry.colors.sunset, size = 11.5.sp)
                                else ->
                                    Txt(stringResource(Res.string.lib_snippet_vars_vault_missing, name), color = Skerry.colors.sunset, size = 11.5.sp)
                            }
                        }
                    }
                }
                FieldLabel(stringResource(Res.string.lib_snippet_vars_preview))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Skerry.colors.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    Txt(preview, color = Skerry.colors.textBright, size = 12.sp, font = mono, lineHeight = 17.sp)
                }
                if (vaultRefs.isNotEmpty()) {
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

@Composable
private fun ParamField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    val textColor = Skerry.colors.text
    val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        BasicTextField(
            value, onChange, singleLine = true, textStyle = style,
            cursorBrush = SolidColor(Skerry.colors.cyan),
            modifier = modifier.fillMaxWidth(),
        )
    }
}

private const val SECRET_MASK = "••••••"
