package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.desktop.matchDesktopShortcut
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_add_tag
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import app.skerry.ui.generated.resources.lib_snippets_field_shortcut
import app.skerry.ui.generated.resources.lib_snippets_field_tags
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_ph_name
import app.skerry.ui.generated.resources.lib_snippets_ph_notes
import app.skerry.ui.generated.resources.lib_snippets_press_keys
import app.skerry.ui.generated.resources.lib_snippets_save
import app.skerry.ui.generated.resources.lib_snippets_shortcut_conflict
import app.skerry.ui.generated.resources.lib_snippets_shortcut_reserved
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.FormField
import app.skerry.ui.design.untrustedLabel
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags
import app.skerry.ui.generated.resources.shell_tip_remove
import app.skerry.ui.design.tagChipLabel
import androidx.compose.ui.text.style.TextDirection

/**
 * Snippet form: name, command, tags and hotkey. Reached from "New snippet" and from Edit in the run
 * panel — running is the panel's job, editing is this one's, and the two never share a screen.
 */
@Composable
internal fun SnippetEditor(
    entry: SnippetEntry?,
    manager: SnippetManager,
    mono: FontFamily,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Shared form state (desktop and mobile): seeded from entry, tracks canSave/tags, builds the
    // draft. No remember keys needed; the editor is recreated externally via key(entry?.id).
    val form = remember { SnippetFormState.fromEntry(entry) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym("code_blocks", size = 20.sp, color = Skerry.colors.cyanBright)
            Txt(untrustedLabel(form.label).ifBlank { stringResource(Res.string.lib_snippets_new) }, color = Skerry.colors.text, size = 17.sp, weight = FontWeight.SemiBold)
        }
        Column(Modifier.padding(top = 20.dp)) {
            // The name is human-readable text — UI font, like every other name field (command below stays mono).
            FormField(stringResource(Res.string.lib_snippets_field_name), top = 0.dp, bottom = 8.dp) {
                EditField(form.label, { form.label = it }, stringResource(Res.string.lib_snippets_ph_name), LocalFonts.current.ui)
            }
        }
        Column(Modifier.padding(top = 20.dp)) {
            FormField(stringResource(Res.string.lib_snippets_field_command), top = 0.dp, bottom = 8.dp) {
                CommandField(form.command, { form.command = it }, "df -h | sort -k5 -r", mono)
            }
        }
        Column(Modifier.padding(top = 20.dp)) {
            FormField(stringResource(Res.string.lib_snippets_field_notes), top = 0.dp, bottom = 8.dp) {
                NotesField(form.notes, { form.notes = it }, stringResource(Res.string.lib_snippets_ph_notes), LocalFonts.current.ui)
            }
        }
        Column(Modifier.padding(top = 20.dp)) {
            FieldLabel(top = 0.dp, bottom = 8.dp, text = labelUppercase(stringResource(Res.string.lib_snippets_field_tags)))
            // Suggestions from other snippets, excluding this one (else a just-removed tag
            // would reappear). Memoized so editing label/command doesn't rescan.
            val tagSugs = remember(manager.snippets, form.tags, form.tagDraft, entry?.id) {
                snippetTagSuggestions(manager.snippets.filter { it.id != entry?.id }.map { it.snippet }, form.tags, form.tagDraft)
            }
            TagsField(
                tags = form.tags,
                draft = form.tagDraft,
                onDraftChange = form::updateTagDraft,
                onCommit = { form.addTags(form.tagDraft) },
                onRemove = form::removeTag,
                suggestions = tagSugs,
                onPick = form::pickTag,
            )
        }
        Column(Modifier.padding(top = 20.dp).width(220.dp)) {
            FieldLabel(top = 0.dp, bottom = 8.dp, text = labelUppercase(stringResource(Res.string.lib_snippets_field_shortcut)))
            // Conflict is checked against other snippets (this one's own shortcut isn't a
            // conflict); the UI prevents assigning the same chord twice, which
            // [SnippetManager.forShortcut] doesn't guarantee on read. Shell hotkeys are checked
            // too: they run first and consume the event, so a snippet on one would never fire.
            val conflict = remember(manager.snippets, form.shortcut, entry?.id) {
                form.shortcut?.let { manager.shortcutConflict(it, entry?.id) }
            }
            val reserved = remember(form.shortcut) {
                form.shortcut?.let { matchDesktopShortcut(it) != null } == true
            }
            val conflictText = when {
                reserved -> stringResource(Res.string.lib_snippets_shortcut_reserved)
                conflict != null -> stringResource(Res.string.lib_snippets_shortcut_conflict, untrustedLabel(conflict.snippet.label))
                else -> null
            }
            ShortcutField(form.shortcut, mono, conflictText = conflictText) { form.shortcut = it }
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                stringResource(Res.string.lib_snippets_save),
                onClick = { if (form.canSave) onSaved(manager.save(form.toDraft())) },
                enabled = form.canSave,
                modifier = Modifier.testTag(UiTags.FORM_SAVE),
            )
            CancelButton(stringResource(Res.string.shell_cancel), onClick = onCancel, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
        }
    }
}

// --- Tag chips ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(
    tags: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (String) -> Unit,
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    val mono = LocalFonts.current.mono
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    AnchoredDropdown(
        expanded = focused && suggestions.isNotEmpty(),
        onDismiss = { focused = false },
        focusable = false, // don't steal focus from the tag input field
        trigger = {
            FlowRow(
                // Tapping anywhere in the capsule (padding, gaps between pills) focuses the input.
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag -> key(tag) { TagPill(tag) { onRemove(tag) } } }
                val textColor = Skerry.colors.text
                val textStyle = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(Skerry.colors.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }),
                    // The chips row has no caption of its own, so the placeholder is the name.
                    modifier = Modifier.widthIn(min = 72.dp).focusRequester(focus)
                        .fieldName(fallback = stringResource(Res.string.lib_snippets_add_tag))
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Txt(stringResource(Res.string.lib_snippets_add_tag), color = Skerry.colors.faint, size = 12.5.sp, font = mono)
                            inner()
                        }
                    },
                )
            }
        },
        menu = { width ->
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                // Tap adds the tag; focus stays on the field, so the menu recomputes without the
                // just-added tag.
                suggestions.forEach { tag ->
                    key(tag) {
                        Box(
                            Modifier.fillMaxWidth().clickable { onPick(tag) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Txt(remember(tag) { tagChipLabel(tag) }, color = Skerry.colors.cyanBright, size = 12.5.sp, font = mono)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun TagPill(tag: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Skerry.colors.cyan.copy(alpha = 0.12f)).padding(start = 9.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt(remember(tag) { tagChipLabel(tag) }, color = Skerry.colors.cyanBright, size = 11.sp)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp), contentAlignment = Alignment.Center) {
            Sym("close", contentDescription = stringResource(Res.string.shell_tip_remove), size = 12.sp, color = Skerry.colors.cyanBright)
        }
    }
}

// --- Snippet hotkey capture ---

@Composable
private fun ShortcutField(value: String?, mono: FontFamily, conflictText: String?, onCapture: (String?) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val hasConflict = conflictText != null
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(Skerry.colors.bg)
                .border(1.dp, if (hasConflict) Skerry.colors.storm else if (focused) Skerry.colors.cyan else Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // Esc/Backspace/Delete clear the assigned shortcut.
                        Key.Escape, Key.Backspace, Key.Delete -> { onCapture(null); true }
                        else -> {
                            val s = SnippetShortcut.format(
                                event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
                            )
                            if (s != null) { onCapture(s); true } else false
                        }
                    }
                }
                .clickable { requester.requestFocus() }
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            val text = value ?: if (focused) stringResource(Res.string.lib_snippets_press_keys) else "—"
            Txt(text, color = if (value != null) Skerry.colors.text else Skerry.colors.faint, size = 13.sp, font = mono)
        }
        // The chord may already be taken (by another snippet or by the shell); the assignment still
        // takes effect, we just warn — saving is never blocked.
        if (conflictText != null) {
            Txt(
                conflictText,
                color = Skerry.colors.storm, size = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// --- Editor fields ---

/** Single-line editable field. */
@Composable
private fun EditField(value: String, onValueChange: (String) -> Unit, placeholder: String, font: FontFamily) {
    val textColor = Skerry.colors.text
    val textStyle = remember(font, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = font) }
    val draft = rememberFieldDraft(value)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft).fieldName(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp, font = font)
                inner()
            }
        },
    )
}

/** Multiline command field (monospace, terminal-dark background). */
@Composable
private fun CommandField(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily) {
    val textColor = Skerry.colors.textBright
    // Direction pinned: the field holds a command, and a snippet can arrive from a peer — a
    // first-strong RTL character would draw the line in an order the shell will not use.
    val textStyle = remember(mono, textColor) {
        TextStyle(color = textColor, fontSize = 13.sp, fontFamily = mono, textDirection = TextDirection.Ltr)
    }
    val draft = rememberFieldDraft(value, singleLine = false)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft).fieldName(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.terminalBg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp, font = mono)
                inner()
            }
        },
    )
}

/** Multiline notes field (UI font, standard card background). */
@Composable
private fun NotesField(value: String, onValueChange: (String) -> Unit, placeholder: String, font: FontFamily) {
    val textColor = Skerry.colors.text
    val textStyle = remember(font, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = font) }
    val draft = rememberFieldDraft(value, singleLine = false)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft).fieldName(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp, font = font)
                inner()
            }
        },
    )
}
