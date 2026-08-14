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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.skerry.ui.generated.resources.runbook_save
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.runbook_step_count
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.runbook.RunbookEditorFields
import app.skerry.ui.runbook.RunbookEntry
import app.skerry.ui.runbook.RunbookHelpDialog
import app.skerry.ui.runbook.RunbookFormState
import app.skerry.ui.runbook.RunbookManager
import app.skerry.ui.runbook.runbookTarget
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

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
                    runbooks.forEach { entry ->
                        key(entry.id) { RunbookCard(entry, mono) { editing = entry; adding = false } }
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
                    adding = false; editing = null
                    // The confirmation dialog and the progress panel both live over the terminal.
                    if (started) state.push(MobileRoute.Terminal)
                },
            )
        }

        if (helpOpen) RunbookHelpDialog(manager, onDismiss = { helpOpen = false })
    }
}

@Composable
private fun RunbookCard(entry: RunbookEntry, mono: FontFamily, onClick: () -> Unit) {
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
                runbook.label.ifBlank { stringResource(Res.string.runbook_untitled) },
                color = Skerry.colors.textBright, size = 14.sp, weight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Txt(
            stringResource(Res.string.runbook_step_count, runbook.steps.size),
            color = Skerry.colors.faint, size = 11.sp, font = mono,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (runbook.description.isNotBlank()) {
            Txt(
                runbook.description, color = Skerry.colors.dim, size = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
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
    // Shared form state (desktop <-> mobile): same fields, same validation, same draft assembly.
    val form = remember(entry) { RunbookFormState.fromEntry(entry) }
    val history = LocalRunbookHistory.current
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && entry != null) {
        ConfirmActionDialog(
            title = stringResource(Res.string.runbook_delete_title),
            message = stringResource(
                Res.string.runbook_delete_message,
                entry.runbook.label.ifBlank { stringResource(Res.string.runbook_untitled) },
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
            RunbookEditorFields(form, mono, horizontalPadding = 18.dp)
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entry != null) {
                    MobileSheetButton(
                        stringResource(Res.string.runbook_run), onClick = { if (runHint == null) onRun() },
                        icon = "play_arrow", filled = false, modifier = Modifier.fillMaxWidth(),
                    )
                    if (runHint != null) Txt(runHint, color = Skerry.colors.faint, size = 11.sp)
                }
                MobileSheetButton(
                    stringResource(Res.string.runbook_save),
                    onClick = { if (form.canSave) { manager.save(form.toDraft()); onSaved() } },
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
}
