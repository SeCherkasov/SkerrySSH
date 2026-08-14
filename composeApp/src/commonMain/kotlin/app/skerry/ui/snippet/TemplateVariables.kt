package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.snippet.paramChoices
import app.skerry.shared.snippet.paramDefault
import app.skerry.shared.snippet.sanitizeSnippetValue
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_clipboard
import app.skerry.ui.generated.resources.lib_snippet_vars_clipboard_empty
import app.skerry.ui.generated.resources.lib_snippet_vars_vault
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_missing
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_not_password
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.terminal.fetchSystemClipboardText
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.FormField

/** Mask shown wherever a vault secret would otherwise be printed. */
internal const val SECRET_MASK = "••••••"

/** Vault reference resolution, done once when the confirmation opens. */
internal sealed interface VaultRef {
    data class Ok(val secret: String) : VaultRef
    data object Missing : VaultRef
    data object NotAPassword : VaultRef
}

private fun resolveVaultRef(name: String, credentials: CredentialManagerController?): VaultRef {
    val entry = credentials?.credentials?.firstOrNull { it.label == name } ?: return VaultRef.Missing
    val password = entry.secret as? CredentialSecret.Password ?: return VaultRef.NotAPassword
    return VaultRef.Ok(password.password)
}

/**
 * The context side of `${{…}}` resolution — everything the machine can't produce on its own:
 * prompted parameters, the system clipboard and vault look-ups. Shared by the snippet confirmation
 * ([SnippetRunDialog]) and the runbook start dialog, because both have the same obligation: capture
 * once when the confirmation opens, show a masked preview, and hand the real values over only when
 * the user says go (TOCTOU rule, coding-guidelines §3).
 */
@Stable
class TemplateVariableValues internal constructor(
    /** Prompted parameters in first-appearance order. */
    val paramNames: List<String>,
    /** Options per parameter (`${{name:default|opt1|opt2}}`); absent for free-text parameters. */
    val paramChoices: Map<String, List<String>>,
    /** Distinct `${{vault:name}}` entry names referenced. */
    val vaultRefs: List<String>,
    /** Whether anything references `${{clipboard}}` (it is only read if so). */
    val needsClipboard: Boolean,
    internal val vaultResolutions: Map<String, VaultRef>,
    internal val params: SnapshotStateMap<String, String>,
) {
    /** What each parameter was seeded with — the template's default, or the previous run's value. */
    private val seeded: Map<String, String> = params.toMap()

    /** True while [name] still holds what the form put there, so its field may select on focus. */
    internal fun isSeeded(name: String): Boolean = params[name] == seeded[name]

    /** Clipboard contents; `null` while still being read. */
    internal var clipboard: String? by mutableStateOf(null)

    /** Current parameter values, to remember for this template's next run. */
    fun paramValues(): Map<String, String> = params.toMap()

    /** Whether every reference resolved: a missing vault entry has no value to send. */
    val canRun: Boolean
        get() = vaultResolutions.values.all { it is VaultRef.Ok } && (!needsClipboard || clipboard != null)

    /**
     * Value for [variable]. [masked] replaces vault secrets with [SECRET_MASK] — the preview path;
     * the unmasked path is only ever called to build the line actually sent.
     */
    fun value(variable: SnippetSegment.Variable, masked: Boolean): String = when (variable.kind) {
        SnippetVariableKind.CLIPBOARD -> clipboard.orEmpty()
        SnippetVariableKind.VAULT -> when (val ref = vaultResolutions[variable.format.orEmpty()]) {
            is VaultRef.Ok -> if (masked) SECRET_MASK else ref.secret
            else -> ""
        }
        SnippetVariableKind.PARAM -> params[variable.name].orEmpty()
        else -> "" // machine kinds are resolved from the run's own draw
    }
}

/**
 * Collects the context values for [variables], keyed on [request] so a new confirmation never
 * inherits the previous one's fields. Parameters are prefilled from [initialParams] (the previous
 * run) and otherwise from the placeholder's inline default (`${{name:default}}`).
 */
@Composable
fun rememberTemplateVariableValues(
    request: Any,
    variables: List<SnippetSegment.Variable>,
    initialParams: Map<String, String> = emptyMap(),
): TemplateVariableValues {
    val credentials = LocalCredentials.current
    val clipboard = LocalClipboard.current
    val values = remember(request) {
        val params = variables.filter { it.kind == SnippetVariableKind.PARAM }
        val paramNames = params.map { it.name }.distinct()
        val vaultRefs = variables.filter { it.kind == SnippetVariableKind.VAULT }.map { it.format.orEmpty() }.distinct()
        val firstByName = paramNames.associateWith { name -> params.first { it.name == name } }
        TemplateVariableValues(
            paramNames = paramNames,
            paramChoices = firstByName.mapValues { (_, v) -> v.paramChoices() }.filterValues { it.isNotEmpty() },
            vaultRefs = vaultRefs,
            needsClipboard = variables.any { it.kind == SnippetVariableKind.CLIPBOARD },
            vaultResolutions = vaultRefs.associateWith { resolveVaultRef(it, credentials) },
            params = mutableStateMapOf<String, String>().apply {
                paramNames.forEach { name -> put(name, paramSeed(firstByName.getValue(name), initialParams[name])) }
            },
        )
    }
    if (values.needsClipboard) {
        LaunchedEffect(request) { values.clipboard = fetchSystemClipboardText(clipboard).orEmpty() }
    }
    return values
}

/**
 * The input block of a confirmation: one field per prompted parameter, then read-only rows for the
 * clipboard and vault references so the user sees what will be spliced in (and what failed to
 * resolve) before anything is sent.
 */
@Composable
fun TemplateVariableFields(values: TemplateVariableValues, autoFocus: Boolean = true) {
    val mono = LocalFonts.current.mono
    val firstFieldFocus = remember { FocusRequester() }
    val firstTextParam = values.paramNames.firstOrNull { it !in values.paramChoices }
    values.paramNames.forEach { name ->
        key(name) {
            // The caption is the variable's own name, not chrome: `${{token}}` and `${{TOKEN}}` are
            // two different keys, and uppercasing the caption would draw and announce them alike.
            FormField(name, uppercase = false) {
                val choices = values.paramChoices[name]
                if (choices != null) {
                    DropdownField(
                        value = values.params[name].orEmpty(),
                        options = choices,
                        // Options come pre-sanitized from paramChoices(), so the picked string, the
                        // selected highlight and the sent value are already one string. The label
                        // filter adds the cap and space-folding every untrusted label carries — a
                        // menu row may draw shortened, but the confirmed line shows the true value.
                        label = { untrustedLabel(it) },
                        onPick = { values.params[name] = it },
                    )
                } else {
                    ParamField(
                        value = values.params[name].orEmpty(),
                        onChange = { values.params[name] = sanitizeSnippetValue(it) },
                        modifier = if (name == firstTextParam) Modifier.focusRequester(firstFieldFocus) else Modifier,
                        // The default from the template (or the previous run) is a suggestion: select it,
                        // so the autofocused first field takes a replacement rather than a prefix.
                        selectAllOnFocus = values.isSeeded(name),
                    )
                }
            }
        }
    }
    if (firstTextParam != null && autoFocus) {
        LaunchedEffect(Unit) { firstFieldFocus.requestFocus() }
    }
    if (values.needsClipboard) {
        FieldLabel(stringResource(Res.string.lib_snippet_vars_clipboard))
        val shown = values.clipboard?.let { sanitizeSnippetValue(it) }
        Txt(
            when {
                shown == null -> "…"
                shown.isEmpty() -> stringResource(Res.string.lib_snippet_vars_clipboard_empty)
                else -> shown
            },
            color = Skerry.colors.dim, size = 11.5.sp, font = mono, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    if (values.vaultRefs.isNotEmpty()) {
        FieldLabel(stringResource(Res.string.lib_snippet_vars_vault))
        values.vaultRefs.forEach { name ->
            key(name) {
                when (values.vaultResolutions[name]) {
                    is VaultRef.Ok -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
}

/**
 * What a parameter's field starts out holding. A free-text parameter keeps the previous run's
 * value, else the inline default. A choice parameter must start on one of its options: the
 * previous run's value only if it is still offered, else the default (always the first option
 * when present), else the first option — a stale remembered value must not resurrect a choice
 * the template no longer contains.
 */
internal fun paramSeed(variable: SnippetSegment.Variable, previous: String?): String {
    val choices = variable.paramChoices()
    if (choices.isEmpty()) return previous ?: variable.paramDefault() ?: ""
    return previous?.takeIf { it in choices } ?: variable.paramDefault() ?: choices.first()
}

@Composable
private fun ParamField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, selectAllOnFocus: Boolean = false) {
    val mono = LocalFonts.current.mono
    val textColor = Skerry.colors.text
    val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
    val draft = rememberFieldDraft(value, selectAllOnFocus)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        BasicTextField(
            draft.textFieldValue(value), { draft.accept(it, value, onChange) }, singleLine = true, textStyle = style,
            cursorBrush = SolidColor(Skerry.colors.cyan),
            modifier = modifier.fillMaxWidth().fieldFocus(draft).fieldName(),
        )
    }
}
