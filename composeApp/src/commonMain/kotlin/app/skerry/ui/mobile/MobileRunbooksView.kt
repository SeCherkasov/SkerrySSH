package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalRunbookHistory
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.modalBody
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.runbook_delete
import app.skerry.ui.generated.resources.runbook_delete_message
import app.skerry.ui.generated.resources.runbook_delete_title
import app.skerry.ui.generated.resources.runbook_empty_mobile
import app.skerry.ui.generated.resources.runbook_new
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_busy
import app.skerry.ui.generated.resources.runbook_run_needs_session
import app.skerry.ui.generated.resources.runbook_run_no_steps
import app.skerry.ui.generated.resources.runbook_save
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.generated.resources.runbook_step_count
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.runbook.RunbookEditorFields
import app.skerry.ui.runbook.RunbookEntry
import app.skerry.ui.runbook.RunbookHelpDialog
import app.skerry.ui.runbook.RunbookFormState
import app.skerry.ui.runbook.RunbookManager
import app.skerry.ui.runbook.runbookTarget
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.design.NOTE_PEEK_LINES
import app.skerry.ui.design.NoteBlock
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags
import app.skerry.ui.generated.resources.runbook_run_needs_save
import androidx.compose.runtime.derivedStateOf
import app.skerry.ui.design.FolderSections
import app.skerry.ui.design.mobileFolderHeaderPadding
import app.skerry.ui.runbook.RUNBOOK_FOLDER_SCOPE
import app.skerry.ui.runbook.runbookFolders

/**
 * Runbooks screen (More → Runbooks): the saved procedures plus an add FAB. Tapping a card opens the
 * edit sheet; Run starts the procedure in the active session and jumps to the terminal, where the
 * start confirmation and the run panel take over. Parity with the desktop section — same form
 * state, same runner.
 */
@Composable
fun MobileRunbooksScreen(state: MobileDesignState) {
    val manager = LocalRunbooks.current
    if (manager == null) {
        // Mock/preview path (no vault behind the library): still a real push screen, so the back
        // arrow exists and the user isn't trapped on a blank one.
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
            MobilePushHeader(stringResource(Res.string.runbook_section), onBack = state::pop)
            Txt(
                stringResource(Res.string.runbook_empty_mobile), color = Skerry.colors.faint, size = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 30.dp),
            )
        }
        return
    }
    val mono = LocalFonts.current.mono
    val runner = LocalRunbookRunner.current
    val sessions = LocalSessions.current
    val session = sessions?.activeTerminal?.focusedPane
    val terminal = (session?.controller?.uiState as? ConnectionUiState.Connected)?.terminal

    var editing by remember { mutableStateOf<RunbookEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    val sheetOpen = adding || editing != null
    val overlayOpen = sheetOpen || helpOpen

    // An open sheet hides the tab bar, which would otherwise float over the fields above the keyboard.
    LaunchedEffect(overlayOpen) { state.modalOverlay(overlayOpen) }
    DisposableEffect(Unit) { onDispose { state.modalOverlay(false) } }

    val runbooks = manager.runbooks

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
            MobilePushHeader(
                stringResource(Res.string.runbook_section), onBack = state::pop,
                actions = {
                    GhostButton(stringResource(Res.string.help_button), onClick = { helpOpen = true }, icon = "help")
                },
            )
            if (runbooks.isEmpty()) {
                Txt(
                    stringResource(Res.string.runbook_empty_mobile), color = Skerry.colors.faint, size = 13.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 30.dp),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Same folders as the desktop list, under the same scope key; the fold is
                    // this device's own view preference and does not travel with the vault.
                    FolderSections(
                        items = runbooks,
                        scope = RUNBOOK_FOLDER_SCOPE,
                        collapse = state,
                        group = { it.runbook.group },
                        itemKey = { it.id },
                        headerPadding = mobileFolderHeaderPadding(),
                        longPress = true,
                        onMoveItems = { ids, targetGroup, targetIndex ->
                            manager.moveRunbooks(ids, targetGroup, targetIndex)
                        },
                        onMoveGroup = { group, targetIndex ->
                            manager.moveGroup(group, targetIndex)
                        },
                    ) { entry ->
                        RunbookCard(entry, mono) { editing = entry; adding = false }
                    }
                }
            }
            // Clears the tab bar and the FAB above it, so the last card can scroll out from under "+".
            Spacer(Modifier.height(176.dp))
        }

        if (!overlayOpen) {
            MobileFabButton(
                onClick = { adding = true; editing = null },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp).testTag(UiTags.NEW_RUNBOOK),
            )
        }

        if (sheetOpen) {
            val target = editing
            MobileRunbookEditSheet(
                entry = target,
                manager = manager,
                mono = mono,
                runHint = when {
                    target == null -> null
                    // Every reason the runner would refuse the start, so the button is never live
                    // when the tap would do nothing. A runbook with no steps only arrives by sync:
                    // the editor's own Save will not produce one.
                    target.runbook.steps.isEmpty() -> stringResource(Res.string.runbook_run_no_steps)
                    terminal == null -> stringResource(Res.string.runbook_run_needs_session)
                    runner == null || runner.active || runner.pending != null ->
                        stringResource(Res.string.runbook_run_busy)
                    else -> null
                },
                onDismiss = { adding = false; editing = null },
                onSaved = { adding = false; editing = null },
                onDeleted = { adding = false; editing = null },
                onRun = run@{
                    val entry = target ?: return@run
                    if (runner == null || session == null || terminal == null) return@run
                    val started = runner.requestStart(
                        entry.runbook,
                        runbookTarget(session.id, terminal, session.controller),
                        recording = terminal.recording,
                    )
                    // The hint covers every reason the runner refuses, but it is computed a frame
                    // before the tap: a run started elsewhere in that window still lands here, and
                    // closing the sheet would look exactly like a run that vanished.
                    if (!started) return@run
                    adding = false; editing = null
                    // The confirmation dialog and the progress panel both live over the terminal.
                    state.push(MobileRoute.Terminal)
                },
            )
        }

        if (helpOpen) RunbookHelpDialog(manager, onDismiss = { helpOpen = false })
    }
}

@Composable
internal fun RunbookCard(entry: RunbookEntry, mono: FontFamily, onClick: () -> Unit) {
    val runbook = entry.runbook
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("checklist", size = 17.sp, color = Skerry.colors.cyanBright)
            Txt(
                remember(runbook) { untrustedLabel(runbook.label) }.ifBlank { stringResource(Res.string.runbook_untitled) },
                color = Skerry.colors.textBright, size = 14.sp, weight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Txt(
            stringResource(Res.string.runbook_step_count, runbook.steps.size),
            color = Skerry.colors.faint, size = 11.sp, font = mono,
            modifier = Modifier.padding(top = 6.dp),
        )
        NoteBlock(
            runbook.description, stringResource(Res.string.runbook_field_description),
            Modifier.padding(top = 6.dp), maxLines = NOTE_PEEK_LINES,
        )
    }
}

/** Runbook create/edit sheet. [entry] == null means create; a non-null [runHint] disables Run. */
@Composable
private fun MobileRunbookEditSheet(
    entry: RunbookEntry?,
    manager: RunbookManager,
    mono: FontFamily,
    runHint: String?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onRun: () -> Unit,
) {
    // Kept at the sheet level so the create overlay renders at the root rather than inside the
    // form's scroll — the same reason the connection sheet holds its own.
    var createGroupOpen by remember { mutableStateOf(false) }
    // Shared form state (desktop <-> mobile): same fields, same validation, same draft assembly.
    val form = remember(entry) { RunbookFormState.fromEntry(entry) }
    // Run starts the saved procedure while the fields above show a draft: edited here, the button
    // would run steps — or a failure policy — the sheet is not showing. Derived rather than read in
    // this scope: the sheet holds every field of the form, and reading them here would recompose
    // the whole editor on each keystroke to answer one boolean.
    val editedHere by remember(entry) {
        derivedStateOf {
            entry != null &&
                (form.steps.map { it.toStep() } != entry.runbook.steps || form.policy() != entry.runbook.policy)
        }
    }
    // The caller's reasons come first — they are the more specific ones (nothing to run, a run
    // already going); this one only covers a procedure that is not the saved one.
    val needsSave = stringResource(Res.string.runbook_run_needs_save)
    val runReason = runHint ?: needsSave.takeIf { editedHere }
    val history = LocalRunbookHistory.current
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && entry != null) {
        ConfirmActionDialog(
            title = stringResource(Res.string.runbook_delete_title),
            message = stringResource(
                Res.string.runbook_delete_message,
                untrustedLabel(entry.runbook.label).ifBlank { stringResource(Res.string.runbook_untitled) },
            ),
            confirmLabel = stringResource(Res.string.runbook_delete),
            onConfirm = {
                confirmDelete = false
                manager.delete(entry.id)
                // The runbook is gone; its run log has nothing left to belong to.
                history?.forget(entry.id)
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.92f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(
                if (entry == null) stringResource(Res.string.runbook_new) else stringResource(Res.string.runbook_section),
                color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            // The fields scroll, the actions stay: a runbook with a description and two steps is
            // taller than the sheet's ceiling, and a clipped Run button is a procedure the phone
            // cannot start at all. weight(fill = false) keeps a short form short.
            Column(modalBody()) {
                RunbookEditorFields(form, mono, horizontalPadding = 18.dp) {
                    val folders = remember(manager.runbooks) { runbookFolders(manager.runbooks) }
                    MobileGroupSelectField(
                        value = form.group,
                        groups = folders,
                        onChange = { form.group = it },
                        onCreateGroup = { createGroupOpen = true },
                    )
                }
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entry != null) {
                    MobileSheetButton(
                        stringResource(Res.string.runbook_run),
                        // Guarded twice: `enabled` stops the finger, an accessibility click action
                        // fires on a disabled control regardless.
                        onClick = { if (runReason == null) onRun() },
                        icon = "play_arrow", filled = false, enabled = runReason == null,
                        // The reason sits in its own line below, which a screen reader would read a
                        // swipe later and unattached: the button carries it as its state.
                        modifier = Modifier.fillMaxWidth().semantics { runReason?.let { stateDescription = it } },
                    )
                    // Drawn for the eye only: the button announces the same string as its state, and
                    // a second node would read it out again a swipe later.
                    if (runReason != null) {
                        Txt(runReason, color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
                MobileSheetButton(
                    stringResource(Res.string.runbook_save),
                    // Guarded twice on purpose: `enabled` stops the finger, and an accessibility
                    // click action fires even on a disabled control.
                    onClick = { if (form.canSave) { manager.save(form.toDraft()); onSaved() } },
                    enabled = form.canSave,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.FORM_SAVE),
                )
                if (entry != null) {
                    // Held for confirmation: deleting a whole procedure silently is one misclick
                    // away from losing it and its run history (desktop parity).
                    MobileSheetButton(
                        stringResource(Res.string.runbook_delete),
                        onClick = { confirmDelete = true },
                        filled = false, danger = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
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
