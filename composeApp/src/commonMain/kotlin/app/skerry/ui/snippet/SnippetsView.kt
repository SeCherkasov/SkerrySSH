package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.snippet.SnippetDraft
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.SnippetShortcut
import app.skerry.ui.snippet.snippetTagSuggestions
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_add_tag
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_empty
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_shortcut
import app.skerry.ui.generated.resources.lib_snippets_field_tags
import app.skerry.ui.generated.resources.lib_snippets_library
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_ph_name
import app.skerry.ui.generated.resources.lib_snippets_press_keys
import app.skerry.ui.generated.resources.lib_snippets_save
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_select_or_create
import app.skerry.ui.generated.resources.lib_snippets_shortcut_conflict
import app.skerry.ui.generated.resources.lib_snippets_shortcut_reserved
import app.skerry.ui.desktop.matchDesktopShortcut
import app.skerry.ui.generated.resources.lib_snippets_untitled
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Chip
import app.skerry.ui.design.D
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine

private data class MockSnippet(val icon: String, val title: String, val cmd: String, val selected: Boolean = false)

private val MOCK_SNIPPETS = listOf(
    MockSnippet("monitoring", "Disk usage report", "df -h | sort -k5 -r", selected = true),
    MockSnippet("memory", "Top memory procs", "ps aux --sort=-%mem | head"),
    MockSnippet("restart_alt", "Restart nginx", "sudo systemctl reload nginx"),
    MockSnippet("cleaning_services", "Clear docker cache", "docker system prune -af"),
)

/**
 * Global Snippets section: library of saved commands (sidebar) plus an editor (main). A snippet
 * is a self-contained plain config, no secrets; the editor only edits it, execution happens from
 * the terminal palette, a hotkey, or "Run snippet…" in a host's context menu. Shows a live list
 * when a manager is provided ([LocalSnippets]); otherwise (offscreen render/preview) shows the
 * static [MOCK_SNIPPETS].
 */
@Composable
fun SnippetsView(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val manager = LocalSnippets.current
    if (manager == null) {
        MockSnippetsView(mono)
        return
    }
    LiveSnippetsView(manager, state.snippetLibrary, mono)
}

// Live path: snippet library plus editor on the right.

@Composable
private fun LiveSnippetsView(manager: SnippetManager, library: SnippetLibraryState, mono: FontFamily) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    val all = manager.snippets
    // Selected snippet: explicit selectedId, else the first in the list (unless adding).
    val selected = if (adding) null else (manager.find(selectedId) ?: all.firstOrNull())

    Row(Modifier.fillMaxSize()) {
        SnippetLibrarySidebar(
            all = all,
            library = library,
            selectedId = if (adding) null else selected?.id,
            mono = mono,
            onSelect = { id -> selectedId = id; adding = false },
            onNew = { adding = true; selectedId = null },
            onInstallStarterPack = { manager.installStarterPack() },
            onRenameTag = { oldTag, newTag -> manager.renameTag(oldTag, newTag)?.let { library.onTagRenamed(oldTag, it) } },
        )
        VLine(D.line)
        Box(Modifier.weight(1f).fillMaxHeight().background(D.bg)) {
            if (!adding && selected == null) {
                EmptyState(icon = "code_blocks", title = stringResource(Res.string.lib_snippets_select_or_create))
            } else {
                // Keyed by the edited snippet's identity so the editor's fields reset instead of
                // carrying over the previous values.
                key(selected?.id, adding) {
                    SnippetEditor(
                        entry = selected,
                        manager = manager,
                        mono = mono,
                        onSaved = { id -> selectedId = id; adding = false },
                        onDeleted = { selectedId = null; adding = false },
                    )
                }
            }
        }
    }
}

internal fun SnippetEntry.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    return snippet.label.lowercase().contains(q) ||
        snippet.command.lowercase().contains(q) ||
        snippet.tags.any { it.lowercase().contains(q) }
}

@Composable
private fun SnippetEditor(
    entry: SnippetEntry?,
    manager: SnippetManager,
    mono: FontFamily,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    // Shared form state (desktop and mobile): seeded from entry, tracks canSave/tags, builds the
    // draft. No remember keys needed; the editor is recreated externally via key(selected?.id, adding).
    val form = remember { SnippetFormState.fromEntry(entry) }

    // The editor is a pure form; execution happens from the terminal palette, a hotkey, or the
    // host context menu, not here.
    fun persist(): String = manager.save(form.toDraft())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("code_blocks", size = 20.sp, color = D.cyanBright)
                Txt(form.label.ifBlank { stringResource(Res.string.lib_snippets_new) }, color = D.text, size = 17.sp, weight = FontWeight.SemiBold)
                // Tags sit inline right after the name, not on a line of their own.
                if (form.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        form.tags.forEach { Chip("#$it") }
                    }
                }
            }
        }
        HLine()
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            FieldLabelSnip(stringResource(Res.string.lib_snippets_field_name))
            // The name is human-readable text — UI font, like every other name field (command below stays mono).
            SnipEditField(form.label, { form.label = it }, stringResource(Res.string.lib_snippets_ph_name), LocalFonts.current.ui)
            Column(Modifier.padding(top = 20.dp)) {
                FieldLabelSnip(stringResource(Res.string.lib_snippets_field_command))
                SnipCommandField(form.command, { form.command = it }, "df -h | sort -k5 -r", mono)
            }
            Column(Modifier.padding(top = 20.dp)) {
                FieldLabelSnip(stringResource(Res.string.lib_snippets_field_tags))
                // Suggestions from other snippets, excluding this one (else a just-removed tag
                // would reappear). Memoized so editing label/command doesn't rescan.
                val tagSugs = remember(manager.snippets, form.tags, form.tagDraft, entry?.id) {
                    snippetTagSuggestions(manager.snippets.filter { it.id != entry?.id }.map { it.snippet }, form.tags, form.tagDraft)
                }
                SnipTagsField(
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
                FieldLabelSnip(stringResource(Res.string.lib_snippets_field_shortcut))
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
                    conflict != null -> stringResource(Res.string.lib_snippets_shortcut_conflict, conflict.snippet.label)
                    else -> null
                }
                ShortcutField(form.shortcut, mono, conflictText = conflictText) { form.shortcut = it }
            }
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    stringResource(Res.string.lib_snippets_save),
                    onClick = { if (form.canSave) onSaved(persist()) },
                    enabled = form.canSave,
                )
                if (entry != null) {
                    GhostButton(stringResource(Res.string.lib_snippets_delete), onClick = { manager.delete(entry.id); onDeleted() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f))
                }
            }
        }
    }
}

// --- Tag chips ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnipTagsField(
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag -> key(tag) { SnipTagPill(tag) { onRemove(tag) } } }
                val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }),
                    modifier = Modifier.widthIn(min = 72.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Txt(stringResource(Res.string.lib_snippets_add_tag), color = D.faint, size = 12.5.sp, font = mono)
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
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
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
                            Txt("#$tag", color = D.cyanBright, size = 12.5.sp, font = mono)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SnipTagPill(tag: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 9.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt("#$tag", color = D.cyanBright, size = 11.sp)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp), contentAlignment = Alignment.Center) {
            Sym("close", size = 12.sp, color = D.cyanBright)
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
                .background(D.bg)
                .border(1.dp, if (hasConflict) D.storm else if (focused) D.cyan else D.cyan14, RoundedCornerShape(7.dp))
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
            Txt(text, color = if (value != null) D.text else D.faint, size = 13.sp, font = mono)
        }
        // The chord may already be taken (by another snippet or by the shell); the assignment still
        // takes effect, we just warn — saving is never blocked.
        if (conflictText != null) {
            Txt(
                conflictText,
                color = D.storm, size = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// --- Static mock (offscreen render/preview without a manager) ---

@Composable
private fun MockSnippetsView(mono: FontFamily) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(D.surface2)) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0x08FFFFFF)).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("search", size = 16.sp, color = D.faint)
                    Txt(stringResource(Res.string.lib_snippets_search), color = D.faint, size = 12.5.sp)
                }
            }
            HLine()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
                SidebarSectionTitle(stringResource(Res.string.lib_snippets_library), modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp))
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MOCK_SNIPPETS.forEach { MockSnippetRow(it, mono) }
                }
            }
            HLine()
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                PrimaryButton(stringResource(Res.string.lib_snippets_new), onClick = {}, icon = "add", modifier = Modifier.fillMaxWidth())
            }
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sym("monitoring", size = 20.sp, color = D.cyanBright)
                    Txt("Disk usage report", color = D.text, size = 17.sp, weight = FontWeight.SemiBold)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("#monitoring")
                    Chip("#disk")
                    Chip("+ tag")
                }
            }
            HLine()
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                FieldLabelSnip(stringResource(Res.string.lib_snippets_field_command))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(D.terminalBg).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Txt("df", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -h | ", color = D.textBright, size = 13.sp, font = mono)
                    Txt("sort", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -k5 -r | ", color = D.textBright, size = 13.sp, font = mono)
                    Txt("head", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -n 10", color = D.textBright, size = 13.sp, font = mono)
                }
                Column(Modifier.padding(top = 20.dp).width(220.dp)) {
                    FieldLabelSnip(stringResource(Res.string.lib_snippets_field_shortcut))
                    SnipInput("⌘⇧D", mono)
                }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(Res.string.lib_snippets_save), onClick = {})
                }
            }
        }
    }
}

@Composable
private fun MockSnippetRow(snippet: MockSnippet, mono: FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (snippet.selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (snippet.selected) D.cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym(snippet.icon, size = 15.sp, color = if (snippet.selected) D.cyanBright else D.dim)
            Txt(snippet.title, color = if (snippet.selected) D.cyanBright else D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
        }
        Txt(snippet.cmd, color = if (snippet.selected) D.dim else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
    }
}

// --- Editor fields ---

@Composable
private fun FieldLabelSnip(text: String) {
    Txt(labelUppercase(text), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 8.dp))
}

/** Single-line editable field ([SnipInput] style plus placeholder and input). */
@Composable
private fun SnipEditField(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 13.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp, font = mono)
                inner()
            }
        },
    )
}

/** Multiline command field (monospace, terminal-dark background). */
@Composable
private fun SnipCommandField(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily) {
    val textStyle = remember(mono) { TextStyle(color = D.textBright, fontSize = 13.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(D.terminalBg).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp, font = mono)
                inner()
            }
        },
    )
}

@Composable
private fun SnipInput(value: String, mono: FontFamily) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
        Txt(value, color = D.text, size = 13.sp, font = mono)
    }
}
