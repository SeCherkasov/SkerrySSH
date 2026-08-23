package app.skerry.ui.snippet

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.snippet.captureSnippetRunEnvironment
import app.skerry.shared.snippet.sanitizeSnippetValue
import app.skerry.ui.design.Chip
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.KeyValueRow
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_clipboard_ref
import app.skerry.ui.generated.resources.lib_snippets_copied
import app.skerry.ui.generated.resources.lib_snippets_copy
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_edit_action
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_no_session
import app.skerry.ui.generated.resources.lib_snippets_preview_runs
import app.skerry.ui.generated.resources.lib_snippets_run
import app.skerry.ui.generated.resources.lib_snippets_run_failed
import app.skerry.ui.generated.resources.lib_snippets_runs_on
import app.skerry.ui.generated.resources.lib_snippets_field_shortcut
import app.skerry.ui.generated.resources.lib_snippets_shortcut_insert
import app.skerry.ui.generated.resources.lib_snippets_shortcut_unset
import app.skerry.ui.generated.resources.lib_snippets_untitled
import app.skerry.ui.generated.resources.lib_snippets_variables
import app.skerry.ui.generated.resources.lib_snippets_vault_ref
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.ClippedNotice
import app.skerry.ui.design.CommandQuote
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.design.tagChipLabel
import app.skerry.ui.terminal.MAX_NOTE_CHARS
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Width of the run panel — wide enough for a wrapped command line next to its labels. */
internal val SNIPPET_PANEL_WIDTH = 400.dp

/** How long the copy button reports success before going back to its normal label. */
private const val COPIED_LABEL_MILLIS = 1500L

/**
 * Right-hand panel of the snippets section: what the selected snippet is, what it will run, and
 * where. Parameters typed here seed the run — a snippet with `${{…}}` still goes through
 * [SnippetRunDialog], which is the only place that reads the clipboard and vault and previews the
 * exact line it sends (coding-guidelines §3). Everything shown here is a draft of that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SnippetRunPanel(
    entry: SnippetEntry,
    targets: List<SnippetRunTarget>,
    activeTargetId: String?,
    mono: FontFamily,
    onRun: (target: SnippetRunTarget, params: Map<String, String>) -> Boolean,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val snippet = entry.snippet
    val segments = remember(snippet.command) { SnippetTemplate.parse(snippet.command) }
    val variables = remember(segments) { snippetPanelVariables(segments) }
    // Machine placeholders (date/uuid/random) are drawn once per snippet so the preview doesn't
    // reshuffle on every keystroke. The line actually sent draws its own values in the dialog.
    val machineValues = remember(segments) { SnippetTemplate.machineValues(segments, captureSnippetRunEnvironment()) }
    val params = remember(entry.id) { mutableStateMapOf<String, String>() }
    var chosenTargetId by remember(entry.id) { mutableStateOf<String?>(null) }
    var copied by remember(entry.id) { mutableStateOf(false) }
    // The session can go away between the frame that enabled Run and the click; the panel has to
    // say so, because a dropped run looks exactly like a command that produced no output.
    var runFailed by remember(entry.id) { mutableStateOf(false) }

    val target = defaultSnippetRunTarget(targets, activeTargetId, chosenTargetId)
    val clipboardRef = stringResource(Res.string.lib_snippets_clipboard_ref)
    val preview = snippetPanelPreview(segments, machineValues, params.toMap(), clipboardRef)

    if (copied) {
        LaunchedEffect(entry.id, copied) {
            delay(COPIED_LABEL_MILLIS)
            copied = false
        }
    }

    Column(
        Modifier.width(SNIPPET_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        // The panel names itself as a heading, not as a field label: in small caps it read as an
        // echo of the list row it belongs to.
        Txt(
            remember(snippet) { untrustedLabel(snippet.label) }.ifBlank { stringResource(Res.string.lib_snippets_untitled) },
            color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        val note = snippet.notes
        val shownNote = remember(note) { note?.let { sanitizeServerText(it, MAX_NOTE_CHARS, allowNewlines = true) } }
        if (!shownNote.isNullOrBlank()) {
            Txt(
                shownNote,
                color = Skerry.colors.dim,
                size = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (snippet.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                snippet.tags.forEach { tag -> key(tag) { Chip(remember(tag) { tagChipLabel(tag) }) } }
            }
        }
        CommandBlock(snippet.command, stringResource(Res.string.lib_snippets_field_command))

        if (variables.isNotEmpty()) {
            FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_variables)), top = 0.dp, bottom = 7.dp)
            variables.forEach { variable ->
                key(variable.kind, variable.name) {
                    VariableRow(
                        variable = variable,
                        value = params[variable.name].orEmpty(),
                        onValueChange = { params[variable.name] = sanitizeSnippetValue(it) },
                        mono = mono,
                    )
                }
            }
            FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_preview_runs)), top = 14.dp, bottom = 7.dp)
            CommandBlock(preview, stringResource(Res.string.lib_snippets_preview_runs), announce = false)
        }

        FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_runs_on)), top = 14.dp, bottom = 7.dp)
        val noSession = stringResource(Res.string.lib_snippets_no_session)
        if (targets.isEmpty()) {
            // Drawn for the eye only: the Run button below carries the same string as its state, and
            // a second node would read it out again a swipe later.
            Txt(
                noSession, color = Skerry.colors.faint, size = 11.5.sp,
                modifier = Modifier.padding(bottom = 14.dp).clearAndSetSemantics {},
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                targets.forEach { candidate ->
                    key(candidate.id) {
                        Chip(
                            candidate.label,
                            active = candidate.id == target?.id,
                            onClick = { chosenTargetId = candidate.id; runFailed = false },
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                stringResource(Res.string.lib_snippets_run),
                onClick = { target?.let { runFailed = !onRun(it, params.toMap()) } },
                icon = "play_arrow",
                enabled = target != null,
                // The reason it cannot fire rides on the button, not only on the line above it: a
                // dimmed control with no word on it says nothing to a reader.
                modifier = Modifier.weight(1f).semantics { if (target == null) stateDescription = noSession },
            )
            GhostButton(
                if (copied) stringResource(Res.string.lib_snippets_copied) else stringResource(Res.string.lib_snippets_copy),
                onClick = { onCopy(preview); copied = true },
                modifier = Modifier.weight(1f),
            )
        }

        if (runFailed) {
            Txt(
                stringResource(Res.string.lib_snippets_run_failed),
                color = Skerry.colors.sunset, size = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_field_shortcut)), top = 20.dp, bottom = 7.dp)
        KeyValueRow(
            stringResource(Res.string.lib_snippets_shortcut_insert),
            // The filter drops rather than escapes, so a chord of nothing but invisible characters
            // comes back empty: an unnamed value reads as a broken field, not as an unset one.
            remember(snippet) { snippet.shortcut?.let { untrustedLabel(it) }?.ifBlank { null } }
                ?: stringResource(Res.string.lib_snippets_shortcut_unset),
        )

        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(Res.string.lib_snippets_edit_action), onClick = onEdit, modifier = Modifier.weight(1f))
            GhostButton(
                stringResource(Res.string.lib_snippets_delete),
                onClick = onDelete,
                fg = Skerry.colors.sunset,
                border = Skerry.colors.sunset.copy(alpha = 0.3f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CommandBlock(text: String, label: String, announce: Boolean = true) {
    // Quoted, not drawn: a snippet can arrive from a team member, and what runs is stripped of the
    // characters that reorder a line — raw, this block would read in an order the shell will not
    // use. The quote also bounds what it draws and says so, which a bare Txt cannot.
    // Keyed on what the block is about, not on its text: the text is rebuilt on every keystroke in
    // a parameter field, and a state reset per character makes the notice blink and the buttons
    // under it jump. `onFit` is what changes it, and it fires on every layout.
    var clipped by remember(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        CommandQuote(
            text,
            visibleLines = COMMAND_QUOTE_LINES,
            // The panel draws two of these: named, or a focus stop says nothing about which one it
            // is — the saved command, or the line a run would send.
            label = label,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.terminalBg)
                .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp))
                .padding(horizontal = 12.dp),
            color = Skerry.colors.textBright,
            size = 11.5.sp,
            lineHeight = 17.sp,
            padding = 10.dp,
            onFit = { clipped = it == false },
        )
        ClippedNotice(clipped, text.length, announce = announce)
    }
}

/** Lines of a quoted command the panel shows before it scrolls its own box. The quote brings its
 * own mono font, so the caller does not pass one. */
private const val COMMAND_QUOTE_LINES = 6

/**
 * One variable row: a prompted parameter gets a field, a vault or clipboard reference states where
 * its value will come from. Neither is read here — see the panel's own note.
 */
@Composable
private fun VariableRow(
    variable: SnippetPanelVariable,
    value: String,
    onValueChange: (String) -> Unit,
    mono: FontFamily,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A vault entry name is the whole text after `vault:` — unconstrained, and from a template
        // that may have been shared; a parameter name is grammar-constrained but unbounded in
        // length. Both are drawn through the filter the confirmation uses.
        val label = if (variable.kind == SnippetVariableKind.VAULT) {
            vaultEntryLabel(variable.name)
        } else {
            untrustedLabel(variable.name)
        }
        Txt(label, color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.weight(1f))
        if (variable.editable) {
            // The field sits outside a FormField, so it takes the caption beside it as its name
            // explicitly — otherwise it is an unnamed input in a form that feeds a remote command.
            ParamInput(value, onValueChange, mono, label, Modifier.width(190.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Sym(if (variable.kind == SnippetVariableKind.VAULT) "lock" else "content_paste", size = 13.sp, color = Skerry.colors.faint)
                Txt(
                    if (variable.kind == SnippetVariableKind.VAULT) stringResource(Res.string.lib_snippets_vault_ref)
                    else stringResource(Res.string.lib_snippets_clipboard_ref),
                    color = Skerry.colors.faint, size = 11.sp, font = mono,
                )
            }
        }
    }
}

@Composable
private fun ParamInput(
    value: String,
    onValueChange: (String) -> Unit,
    mono: FontFamily,
    name: String,
    modifier: Modifier = Modifier,
) {
    val textColor = Skerry.colors.text
    // Pinned: the value goes into a shell line, and its seed is the template's own text.
    val style = remember(mono, textColor) {
        TextStyle(color = textColor, fontSize = 11.5.sp, fontFamily = mono, textDirection = TextDirection.Ltr)
    }
    // The caller sanitizes what it stores, so the caret has to survive a value coming back
    // rewritten — see FieldDraft.
    val draft = rememberFieldDraft(value)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = modifier.fieldFocus(draft).fieldName(fallback = name),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Skerry.colors.bg)
                    .border(1.dp, Skerry.colors.line, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                inner()
            }
        },
    )
}

