package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.text.capNotes
import app.skerry.shared.snippet.Snippet
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.snippet.SnippetHelpDialog
import app.skerry.ui.snippet.SnippetDraft
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetFormState
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.installStarterPack
import app.skerry.ui.snippet.matches
import app.skerry.ui.snippet.snippetFolders
import app.skerry.ui.snippet.snippetTagSuggestions
import app.skerry.ui.design.tagChipLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.lib_snippets_add_tag
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_edit
import app.skerry.ui.generated.resources.lib_snippets_empty_mobile
import app.skerry.ui.generated.resources.lib_snippets_starter_pack
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import app.skerry.ui.generated.resources.lib_snippets_field_tags
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_ph_name
import app.skerry.ui.generated.resources.lib_snippets_ph_notes
import app.skerry.ui.design.NOTE_PEEK_LINES
import app.skerry.ui.design.NoteBlock
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_placeholder
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_subtitle
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_title
import app.skerry.ui.generated.resources.lib_snippets_run_empty
import app.skerry.ui.generated.resources.lib_snippets_run_in_terminal
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_save_snippet
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.shell_save
import app.skerry.ui.generated.resources.shtail_group_label
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_untitled
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.modalBody
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.CommandLine
import app.skerry.ui.design.untrustedLabel
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.skerry.ui.generated.resources.lib_snippets_run_needs_save
import androidx.compose.ui.semantics.clearAndSetSemantics

private data class MockMobileSnippet(val icon: String, val title: String, val cmd: String)

private val MOCK_MOBILE_SNIPPETS = listOf(
    MockMobileSnippet("monitoring", "Disk usage report", "df -h | sort -k5 -r | head"),
    MockMobileSnippet("memory", "Top memory procs", "ps aux --sort=-%mem | head"),
    MockMobileSnippet("restart_alt", "Restart nginx", "sudo systemctl reload nginx"),
    MockMobileSnippet("cleaning_services", "Clear docker cache", "docker system prune -af"),
)

/**
 * Snippets tab: library of saved commands + add FAB. Tapping a card opens the edit sheet
 * (Name/Command/Tags with type-ahead, Run/Save/Delete). A snippet is self-contained plain config,
 * no secrets.
 *
 * Live path ([LocalSnippets] != null, behind the vault gate) uses the real library from
 * [SnippetManager]; preview/offscreen without a manager renders static mock cards.
 */
@Composable
fun MobileSnippetsScreen(state: MobileDesignState) {
    when (val manager = LocalSnippets.current) {
        null -> MobileSnippetsMock()
        else -> MobileSnippetsLive(state, manager)
    }
}

@Composable
private fun MobileSnippetsLive(state: MobileDesignState, manager: SnippetManager) {
    val sessions = LocalSessions.current
    // Snippet run targets the active connected session; no live terminal means no Run button in the editor.
    val activeTerminal = (sessions?.activeSession?.controller?.uiState as? ConnectionUiState.Connected)?.terminal

    var editing by remember { mutableStateOf<SnippetEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var renamingTag by remember { mutableStateOf<String?>(null) }
    val editSheetOpen = adding || editing != null
    // Any sheet (edit or rename) hides the tab bar and the add FAB.
    val sheetOpen = editSheetOpen || renamingTag != null || helpOpen

    // Open edit sheet hides the tab bar (otherwise it floats above the input fields over the keyboard).
    LaunchedEffect(sheetOpen) { state.modalOverlay(sheetOpen) }
    DisposableEffect(Unit) { onDispose { state.modalOverlay(false) } }

    val snippets = manager.snippets

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
            // A push screen since the library left the tab bar (More → Snippets), so it carries a
            // back arrow instead of a bare title.
            MobilePushHeader(
                stringResource(Res.string.lib_snippets_screen_title), onBack = state::pop,
                actions = {
                    GhostButton(stringResource(Res.string.help_button), onClick = { helpOpen = true }, icon = "help")
                },
            )
            if (snippets.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Txt(stringResource(Res.string.lib_snippets_empty_mobile), color = Skerry.colors.faint, size = 13.sp)
                    ChipButton(
                        label = stringResource(Res.string.lib_snippets_starter_pack),
                        color = Skerry.colors.cyan,
                        onClick = { manager.installStarterPack() },
                    )
                }
            } else {
                MobileSnippetLibrary(
                    all = snippets,
                    library = state.snippetLibrary,
                    collapse = state,
                    onEdit = { entry -> editing = entry; adding = false },
                    onRenameCategory = { tag -> renamingTag = tag },
                    onMoveItems = { ids, targetGroup, targetIndex ->
                        manager.moveSnippets(ids, targetGroup, targetIndex)
                    },
                    onMoveGroup = { group, targetIndex ->
                        manager.moveGroup(group, targetIndex)
                    },
                )
            }
            // Clears the tab bar and the FAB above it (bottom 104dp + 56dp size + 16dp margin), so the last
            // snippet card can scroll out from under the "+" button.
            Spacer(Modifier.height(176.dp))
        }

        // Add FAB (bottom-right, above the tab bar). Hidden while any sheet (edit/rename) is open.
        if (!sheetOpen) {
            MobileFabButton(
                onClick = { adding = true; editing = null },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp).testTag(UiTags.NEW_SNIPPET),
            )
        }

        renamingTag?.let { tag ->
            MobileRenameTagSheet(
                oldTag = tag,
                onDismiss = { renamingTag = null },
                onSave = { newTag ->
                    manager.renameTag(tag, newTag)?.let { state.snippetLibrary.onTagRenamed(tag, it) }
                    renamingTag = null
                },
            )
        }

        if (editSheetOpen) {
            val target = editing
            MobileSnippetEditSheet(
                entry = target,
                allSnippets = snippets.map { it.snippet },
                folders = remember(snippets) { snippetFolders(snippets) },
                canRun = target != null && activeTerminal != null,
                onDismiss = { adding = false; editing = null },
                onSave = { draft ->
                    manager.save(draft)
                    adding = false; editing = null
                },
                onDelete = target?.let { e -> { manager.delete(e.id); adding = false; editing = null } },
                onRun = run@{
                    val e = target ?: return@run
                    // One tap from a sheet whose command field is an editor, not a quote: anything
                    // it cannot show whole goes through the confirmation, like every other row that
                    // runs on a tap.
                    manager.run(e.id, recording = activeTerminal?.recording == true, oneTap = true) { text, secrets ->
                        activeTerminal?.sendUserInputGuarded(text, secrets)
                    }
                    adding = false; editing = null
                    sessions?.active?.let { state.push(MobileRoute.Terminal) }
                },
            )
        }

        if (helpOpen) SnippetHelpDialog(manager, onDismiss = { helpOpen = false })
    }
}

/** Lines of a command the phone's card previews before it ellipsizes. */
private const val CARD_PREVIEW_LINES = 3

@Composable
internal fun MobileSnippetCard(snippet: Snippet, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(13.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Sym("code_blocks", size = 18.sp, color = Skerry.colors.cyanBright)
            Txt(remember(snippet) { untrustedLabel(snippet.label) }.ifBlank { stringResource(Res.string.lib_snippets_untitled) }, color = Skerry.colors.text, size = 14.5.sp, weight = FontWeight.SemiBold)
        }
        // A peek at the note, not the whole of it: the card is a row in a list, and the full text is
        // in the editor.
        NoteBlock(
            snippet.notes, stringResource(Res.string.lib_snippets_field_notes),
            Modifier.padding(top = 4.dp), size = 11.5.sp, maxLines = NOTE_PEEK_LINES,
        )
        if (snippet.command.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                CommandLine(snippet.command, color = Skerry.colors.dim, size = 11.5.sp, maxLines = CARD_PREVIEW_LINES)
            }
        }
        if (snippet.tags.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snippet.tags.forEach { tag -> key(tag) { SnippetTagChip(tag) } }
            }
        }
    }
}

@Composable
private fun SnippetTagChip(tag: String) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Skerry.colors.cyan.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Txt(remember(tag) { tagChipLabel(tag) }, color = Skerry.colors.cyanBright, size = 11.sp)
    }
}

// --- Edit sheet ---

/**
 * Snippet create/edit sheet. [entry] == null means create.
 * [canRun]/[onRun] apply only when editing an existing snippet with a live terminal.
 */
@Composable
private fun MobileSnippetEditSheet(
    entry: SnippetEntry?,
    allSnippets: List<Snippet>,
    folders: List<String>,
    canRun: Boolean,
    onDismiss: () -> Unit,
    onSave: (SnippetDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onRun: () -> Unit,
) {
    // Shared form state (desktop <-> mobile): seeds from entry (including shortcut, so Save
    // doesn't drop a hotkey assigned on desktop), canSave, tags, draft assembly.
    val form = remember { SnippetFormState.fromEntry(entry) }
    // Kept at the sheet level so the create overlay renders at the root rather than inside the
    // form's scroll — the same reason the connection sheet holds its own.
    var createGroupOpen by remember { mutableStateOf(false) }

    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.9f) {
        Txt(
            if (entry == null) stringResource(Res.string.lib_snippets_new) else stringResource(Res.string.lib_snippets_edit),
            color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp),
        )
        // Fields scroll, actions stay: a long command and a wrapped tag row used to push Save and
        // Delete past the sheet's ceiling, where nothing could reach them.
        Column(modalBody().fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MobileFormField(stringResource(Res.string.lib_snippets_field_name)) {
                MobileFormInput(form.label, { form.label = it }, stringResource(Res.string.lib_snippets_ph_name))
            }
            MobileFormField(stringResource(Res.string.lib_snippets_field_command)) {
                MobileFormInput(form.command, { form.command = it }, "df -h | sort -k5 -r", mono = true, background = Skerry.colors.terminalBg, singleLine = false, minHeightDp = 88)
            }
            MobileFormField(stringResource(Res.string.lib_snippets_field_notes)) {
                MobileFormInput(form.notes, { form.notes = capNotes(it) }, stringResource(Res.string.lib_snippets_ph_notes), singleLine = false, minHeightDp = 64)
            }
            MobileFormField(stringResource(Res.string.shtail_group_label)) {
                MobileGroupSelectField(
                    value = form.group,
                    groups = folders,
                    onChange = { form.group = it },
                    onCreateGroup = { createGroupOpen = true },
                )
            }
            MobileFormField(stringResource(Res.string.lib_snippets_field_tags)) {
                // Own tags are excluded via selected; suggestions come from all snippets (including the
                // one being edited - its own tags are already selected, so won't be suggested again).
                val others = remember(allSnippets, entry?.id) { allSnippets.filter { it.id != entry?.id } }
                val suggestions = remember(others, form.tags, form.tagDraft) { snippetTagSuggestions(others, form.tags, form.tagDraft) }
                MobileTagsEditor(
                    tags = form.tags,
                    onRemove = form::removeTag,
                    draft = form.tagDraft,
                    onDraftChange = form::updateTagDraft,
                    onCommit = { form.addTags(form.tagDraft) },
                    suggestions = suggestions,
                    placeholder = stringResource(Res.string.lib_snippets_add_tag),
                    onPick = form::pickTag,
                    menuBackground = Skerry.colors.surface2,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canRun) {
                // Run sends the saved record, and the field above it shows a draft: while the two
                // differ, the button would run a command the sheet is not showing.
                val editedHere = entry != null && form.command != entry.snippet.command
                val needsSave = stringResource(Res.string.lib_snippets_run_needs_save)
                MobileSheetButton(
                    stringResource(Res.string.lib_snippets_run_in_terminal),
                    onClick = { if (!editedHere) onRun() },
                    icon = "bolt",
                    filled = false,
                    enabled = !editedHere,
                    // The reason sits in its own line below, which a screen reader would read a
                    // swipe later and unattached: the button carries it as its state.
                    modifier = Modifier.fillMaxWidth()
                        .semantics { if (editedHere) stateDescription = needsSave },
                )
                // Drawn for the eye only: the button announces the same string as its state.
                if (editedHere) {
                    Txt(needsSave, color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            MobileSheetButton(
                stringResource(Res.string.lib_snippets_save_snippet),
                // Guarded twice: an accessibility click action fires even on a disabled control.
                onClick = { if (form.canSave) onSave(form.toDraft()) },
                enabled = form.canSave,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.FORM_SAVE),
            )
            if (onDelete != null) {
                MobileSheetButton(stringResource(Res.string.lib_snippets_delete), onClick = onDelete, filled = false, danger = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    // A sibling above the sheet, with its own full-screen scrim, so it rises above the keyboard.
    if (createGroupOpen) {
        MobileGroupCreateDialog(
            onDismiss = { createGroupOpen = false },
            onCreate = { name -> form.group = name.trim(); createGroupOpen = false },
        )
    }
}


/**
 * Rename a snippet tag (which doubles as a library category) across every snippet that carries it.
 * Mobile counterpart of the desktop the desktop rename dialog in [app.skerry.ui.snippet.SnippetTagFilterRow]:
 * bottom sheet, prefilled name, Save is a no-op while blank. The merge/normalize semantics live in
 * [SnippetManager.renameTag].
 */
@Composable
private fun MobileRenameTagSheet(oldTag: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(oldTag) }
    val save = { if (name.trim().isNotEmpty()) onSave(name) }
    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.7f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(stringResource(Res.string.lib_snippets_rename_tag_title), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold)
            Txt(stringResource(Res.string.lib_snippets_rename_tag_subtitle), color = Skerry.colors.dim, size = 13.sp, lineHeight = 18.sp)
            MobileFormInput(
                name,
                { name = it },
                stringResource(Res.string.lib_snippets_rename_tag_placeholder),
                imeAction = ImeAction.Done,
                // The old tag arrives prefilled: select it so typing replaces the name.
                selectAllOnFocus = name == oldTag,
                onSubmit = save,
            )
            MobileSheetButton(stringResource(Res.string.shell_save), onClick = save, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
    }
}

// --- Static mock (preview/offscreen without a manager) ---

@Composable
private fun MobileSnippetsMock() {
    val mono = LocalFonts.current.mono
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                MobileScreenTitle(stringResource(Res.string.lib_snippets_screen_title))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MOCK_MOBILE_SNIPPETS.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Skerry.colors.card).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(13.dp)).padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Sym(s.icon, size = 18.sp, color = Skerry.colors.cyanBright)
                            Txt(s.title, color = Skerry.colors.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
                        ) {
                            Txt(s.cmd, color = Skerry.colors.dim, size = 11.5.sp, font = mono)
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
        MobileFabButton(
            onClick = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
        )
    }
}
