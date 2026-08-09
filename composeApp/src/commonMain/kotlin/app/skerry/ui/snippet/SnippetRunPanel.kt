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
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_clipboard_ref
import app.skerry.ui.generated.resources.lib_snippets_copied
import app.skerry.ui.generated.resources.lib_snippets_copy
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_edit_action
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
            snippet.label.ifBlank { stringResource(Res.string.lib_snippets_untitled) },
            color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (snippet.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                snippet.tags.forEach { tag -> key(tag) { Chip(snippetTagLabel(tag)) } }
            }
        }
        CommandBlock(snippet.command, mono)

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
            CommandBlock(preview, mono)
        }

        FieldLabel(labelUppercase(stringResource(Res.string.lib_snippets_runs_on)), top = 14.dp, bottom = 7.dp)
        if (targets.isEmpty()) {
            Txt(stringResource(Res.string.lib_snippets_no_session), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(bottom = 14.dp))
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
                modifier = Modifier.weight(1f),
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
            snippet.shortcut ?: stringResource(Res.string.lib_snippets_shortcut_unset),
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
private fun CommandBlock(text: String, mono: FontFamily) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.terminalBg)
            .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Txt(text, color = Skerry.colors.textBright, size = 11.5.sp, font = mono, lineHeight = 17.sp)
    }
}

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
        Txt(variable.name, color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.weight(1f))
        if (variable.editable) {
            ParamInput(value, onValueChange, mono, Modifier.width(190.dp))
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
private fun ParamInput(value: String, onValueChange: (String) -> Unit, mono: FontFamily, modifier: Modifier = Modifier) {
    val textColor = Skerry.colors.text
    val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 11.5.sp, fontFamily = mono) }
    // The caller sanitizes what it stores, so the caret has to survive a value coming back
    // rewritten — see FieldDraft.
    val draft = rememberFieldDraft(value)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = modifier.fieldFocus(draft).fieldName(),
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

