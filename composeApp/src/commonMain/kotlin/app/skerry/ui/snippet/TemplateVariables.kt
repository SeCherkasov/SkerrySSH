package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.snippet.paramChoices
import app.skerry.shared.snippet.paramDefault
import app.skerry.shared.snippet.sanitizeSnippetValue
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.design.spaceLabel
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_clipboard
import app.skerry.ui.generated.resources.lib_snippet_vars_secret_note
import app.skerry.ui.generated.resources.lib_snippet_vars_clipboard_empty
import app.skerry.ui.generated.resources.lib_snippet_vars_vault
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_ambiguous
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_missing
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_more
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_ready
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_reading
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_unusable
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_unnamed
import app.skerry.ui.terminal.fetchSystemClipboardText
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.FormField
import androidx.compose.ui.text.style.TextDirection

/** Mask shown wherever a vault secret would otherwise be printed. */
internal const val SECRET_MASK = "••••••"

/**
 * [text] with every resolved vault secret span replaced by [SECRET_MASK]. Display only: what is
 * sent or classified stays the real text. Shared by the confirmations that quote a resolved line —
 * the production guard's dialog and the connect-time snippet gate.
 *
 * A string that was CUT mid-secret — the classifier caps a candidate at 512 characters, and the
 * cut can land inside a resolved value — ends with a prefix of the secret, which the exact replace
 * cannot see. Any trailing prefix of a secret is masked as well: that can hide a legitimate
 * character that merely coincides with the secret's start, but the other direction printed the
 * secret's head in the guard's aside in clear. The mirror image — a string that BEGINS mid-secret,
 * reachable only through a soft-wrapped screen row that happens to carry a resolved value — is
 * accepted residual risk: a leading-suffix rule cannot tell a secret's tail from ordinary text.
 *
 * Longest secret first: with one resolved value a substring of another, replacing the shorter
 * first would mask only the embedded span and print the longer secret's flanks in clear.
 */
internal fun maskSecrets(text: String, secrets: List<String>): String {
    val hidden = secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }
    if (hidden.isEmpty()) return text
    var masked = hidden.fold(text) { acc, secret -> acc.replace(secret, SECRET_MASK) }
    for (secret in hidden) {
        val longest = minOf(secret.length - 1, masked.length)
        for (cut in longest downTo 1) {
            if (masked.endsWith(secret.substring(0, cut))) {
                masked = masked.dropLast(cut) + SECRET_MASK
                break
            }
        }
    }
    return masked
}

/**
 * A `${'$'}{{vault:name}}` entry name as it may be drawn. The name comes from the template, which may
 * have arrived from a team member: a bidi override in it would name one credential while another is
 * looked up, in the row whose whole job is to say which secret goes into the command. A name that
 * filters away to nothing gets a stand-in, or the row would name no entry at all.
 */
@Composable
internal fun vaultEntryLabel(name: String): String =
    spaceLabel(name, fallback = stringResource(Res.string.lib_snippet_vars_vault_unnamed))

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
    internal val params: SnapshotStateMap<String, String>,
) {
    /** What each parameter was seeded with — the template's default, or the previous run's value. */
    private val seeded: Map<String, String> = params.toMap()

    /** True while [name] still holds what the form put there, so its field may select on focus. */
    internal fun isSeeded(name: String): Boolean = params[name] == seeded[name]

    /**
     * Resolved vault references; a name still missing from the map is one the look-up hasn't answered
     * for yet. Deriving a key's public half parses a PEM, which is too slow for the composition
     * thread, so this fills in off it — like [clipboard] — and [canRun] holds the dialog until it has.
     */
    internal var vaultResolutions: Map<String, VaultRef> by mutableStateOf(emptyMap())

    /** Clipboard contents; `null` while still being read. */
    internal var clipboard: String? by mutableStateOf(null)

    /** Current parameter values, to remember for this template's next run. */
    fun paramValues(): Map<String, String> = params.toMap()

    /**
     * The resolved vault secrets of this run — the spans a later confirmation (the production
     * guard's) must mask rather than print, exactly as this dialog's own preview masked them. Public
     * material is left out: it is printed here, and masking it downstream would hide from the guard's
     * quote the one thing that says which key the command carries.
     */
    fun vaultSecrets(): List<String> =
        vaultResolutions.values.filterIsInstance<VaultRef.Ok>().filter { it.secret }
            // The span to hide is the one that ends up in the line, and [SnippetTemplate.assemble]
            // flattens every context value on the way in: a password holding a tab or a zero-width
            // character reaches the guard's quote in a form an exact replace of the raw string would
            // walk straight past, printing it in clear.
            .map { sanitizeSnippetValue(it.value) }

    /**
     * Whether every reference resolved: a missing or unusable vault entry has no value to send, and
     * one still being looked up has none *yet*.
     */
    val canRun: Boolean
        get() = vaultRefs.all { vaultResolutions[it] is VaultRef.Ok } && (!needsClipboard || clipboard != null)

    /**
     * Value for [variable]. [masked] replaces vault secrets with [SECRET_MASK] — the preview path;
     * the unmasked path is only ever called to build the line actually sent. Public material reads
     * the same either way.
     */
    fun value(variable: SnippetSegment.Variable, masked: Boolean): String = when (variable.kind) {
        SnippetVariableKind.CLIPBOARD -> clipboard.orEmpty()
        SnippetVariableKind.VAULT -> when (val ref = vaultResolutions[variable.format.orEmpty()]) {
            is VaultRef.Ok -> if (masked && ref.secret) SECRET_MASK else ref.value
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
    val generator = LocalSshKeyGenerator.current
    val clipboard = LocalClipboard.current
    // The keychain as it stands when the confirmation opens. What freezes it is the `remember(request)`
    // around the resolution below — this list, the seeded map and the effect all live in slots keyed on
    // the request — so a background sync landing a secret mid-dialog cannot change what the previewed
    // line means.
    val entries = remember(request) { credentials?.credentials.orEmpty() }
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
            params = mutableStateMapOf<String, String>().apply {
                paramNames.forEach { name -> put(name, paramSeed(firstByName.getValue(name), initialParams[name])) }
            },
        ).apply {
            // Everything that needs no key parse is resolved here, on the spot: a password reference
            // would otherwise spend the first frame drawing its span empty, and the dialog's whole
            // claim is that the preview is the line that runs.
            vaultResolutions = vaultRefs.filterNot { vaultRefNeedsKeyParse(it, entries) }
                .associateWith { resolveVaultRef(it, entries, generator = null) }
        }
    }
    val parsedRefs = remember(request) { values.vaultRefs.filter { vaultRefNeedsKeyParse(it, entries) } }
    if (parsedRefs.isNotEmpty()) {
        LaunchedEffect(request) {
            val derived = withContext(Dispatchers.Default) {
                parsedRefs.associateWith { resolveVaultRef(it, entries, generator) }
            }
            values.vaultResolutions = values.vaultResolutions + derived
        }
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
            // The caption is also the field's accessible name. The parser bounds what a name may
            // contain but not how long it is: unfiltered, a shared template could push the preview
            // and the Run button out of the dialog with one very long parameter.
            FormField(untrustedLabel(name), uppercase = false) {
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
        // A key reference lands after the dialog is already open and focus has moved on to the first
        // parameter field, so the outcome has to be spoken rather than waited on to be visited. The
        // announcer carries the text itself and outlives the change — a live region on the block
        // whose children hold the text announces nothing (see StatusAnnouncer).
        StatusAnnouncer(vaultRefsAnnouncement(values))
        Column {
            values.vaultRefs.forEach { name ->
                key(name) {
                    val shown = vaultEntryLabel(name)
                    when (val ref = values.vaultResolutions[name]) {
                        is VaultRef.Ok -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Txt(shown, color = Skerry.colors.text, size = 11.5.sp, font = mono)
                            // A password is masked; a public key or certificate is spelled out — the
                            // row exists to say which material the command will carry, so it draws
                            // the value in the form the line will hold, not the stored one (a
                            // certificate's trailing comment is its author's text).
                            Txt(
                                if (ref.secret) SECRET_MASK else sanitizeSnippetValue(ref.value),
                                color = Skerry.colors.faint, size = 11.5.sp, font = mono,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        VaultRef.Unusable ->
                            Txt(stringResource(Res.string.lib_snippet_vars_vault_unusable, shown), color = Skerry.colors.sunset, size = 11.5.sp)
                        VaultRef.Ambiguous ->
                            Txt(stringResource(Res.string.lib_snippet_vars_vault_ambiguous, shown), color = Skerry.colors.sunset, size = 11.5.sp)
                        VaultRef.Missing ->
                            Txt(stringResource(Res.string.lib_snippet_vars_vault_missing, shown), color = Skerry.colors.sunset, size = 11.5.sp)
                        // Still being looked up: named, not an ellipsis, so the dialog doesn't claim
                        // the entry is missing while the answer is on its way — and so a screen
                        // reader has something to say when it reaches the row.
                        null -> Txt(stringResource(Res.string.lib_snippet_vars_vault_reading, shown), color = Skerry.colors.dim, size = 11.5.sp)
                    }
                }
            }
        }
    }
}

/**
 * The line a confirmation adds when the run really does put a secret on the wire — and only then: a
 * run whose only vault reference is a public key carries nothing to warn about, and a warning that
 * cries wolf is the one nobody reads before the run that does carry a password.
 *
 * [secrets] is what the caller will hand to the production guard, so the notice and the masking can
 * never disagree about what the line holds. Shared by the snippet confirmation and the runbook one;
 * [topPadding] is the only thing that differs between them.
 */
@Composable
fun SecretPlaintextNotice(secrets: List<String>, topPadding: Dp) {
    if (secrets.isEmpty()) return
    Txt(
        stringResource(Res.string.lib_snippet_vars_secret_note),
        color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp,
        modifier = Modifier.padding(top = topPadding),
    )
}

/** How many references the announcement spells out before it starts counting the rest. */
private const val MAX_ANNOUNCED_REFS = 5

/**
 * The one line the vault block is worth saying out loud, or the empty string while it has nothing to
 * report yet — an outcome per reference, in the order they are drawn.
 *
 * Silent until every reference has answered: a partial state would announce twice for one dialog,
 * and the string is the state, so an unchanged one stays silent anyway ([StatusAnnouncer]). The
 * resolved case is named, not read out — a resolved password is a mask on screen, and a resolved key
 * is several hundred characters nobody wants spoken.
 */
@Composable
private fun vaultRefsAnnouncement(values: TemplateVariableValues): String {
    if (values.vaultRefs.any { values.vaultResolutions[it] == null }) return ""
    // Bounded: the reference count comes from a template that may have been shared, and a live
    // region cannot be interrupted the way a list can be scrolled past. The tail is counted, not
    // dropped in silence — the same shape a row of host names uses when it runs out of room.
    val spoken = values.vaultRefs.take(MAX_ANNOUNCED_REFS).map { name ->
        val shown = vaultEntryLabel(name)
        when (values.vaultResolutions[name]) {
            is VaultRef.Ok -> stringResource(Res.string.lib_snippet_vars_vault_ready, shown)
            VaultRef.Unusable -> stringResource(Res.string.lib_snippet_vars_vault_unusable, shown)
            VaultRef.Ambiguous -> stringResource(Res.string.lib_snippet_vars_vault_ambiguous, shown)
            VaultRef.Missing -> stringResource(Res.string.lib_snippet_vars_vault_missing, shown)
            null -> ""
        }
    }.joinToString(". ")
    val rest = values.vaultRefs.size - MAX_ANNOUNCED_REFS
    if (rest <= 0) return spoken
    // Worded, not the "+N" the rows would draw: this string is only ever heard, and a screen reader
    // set to skip punctuation reads a bare "+3" as a number with nothing to attach it to.
    return spoken + ". " + pluralStringResource(Res.plurals.lib_snippet_vars_vault_more, rest, rest)
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
    // The default is the template's own text, and a template can be shared: sanitized here the way
    // the option list already is, so the field draws the string that will be spliced rather than one
    // the run then quietly rewrites.
    val default = variable.paramDefault()?.let { sanitizeSnippetValue(it) }?.ifEmpty { null }
    if (choices.isEmpty()) return previous ?: default ?: ""
    return previous?.takeIf { it in choices } ?: default ?: choices.first()
}

@Composable
private fun ParamField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, selectAllOnFocus: Boolean = false) {
    val mono = LocalFonts.current.mono
    val textColor = Skerry.colors.text
    // The value is spliced into a shell line, and its seed is the template's own text: pinned, so
    // the field reads in the order the line will.
    val style = remember(mono, textColor) {
        TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono, textDirection = TextDirection.Ltr)
    }
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
