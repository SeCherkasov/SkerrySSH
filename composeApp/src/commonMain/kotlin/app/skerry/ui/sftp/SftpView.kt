package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.files.FileEditController
import app.skerry.ui.files.FKeyBar
import app.skerry.ui.files.FileEditorScreen
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.files.transferFailureText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_file_fallback
import app.skerry.ui.generated.resources.ftail_open_failed
import app.skerry.ui.generated.resources.ftail_transfer_counter
import app.skerry.ui.generated.resources.ftail_transfer_progress
import app.skerry.ui.generated.resources.sftp_create
import app.skerry.ui.generated.resources.sftp_overwrite
import app.skerry.ui.generated.resources.sftp_overwrite_many
import app.skerry.ui.generated.resources.sftp_overwrite_one
import app.skerry.ui.generated.resources.sftp_overwrite_q
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_opening
import app.skerry.ui.generated.resources.sftp_pane_local
import app.skerry.ui.generated.resources.sftp_pane_remote
import app.skerry.ui.generated.resources.sftp_rename
import app.skerry.ui.generated.resources.sftp_title
import app.skerry.ui.generated.resources.sftp_transfer_error
import app.skerry.ui.generated.resources.sftp_unavailable
import app.skerry.ui.session.SessionView
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sftp.pickUploadSource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry

/**
 * SFTP view (two-pane, Total Commander style): header + Local pane (local FS) + Remote pane (host) +
 * transfer action bar + progress bar. With a live session ([LocalSessions]) both panes render over the
 * active session's [TransferCoordinator] — listing/navigation/CRUD/transfer are real. Without a session
 * (offscreen design render without a backend) a static mock is shown.
 */
@Composable
fun SftpView() {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.activeTerminal?.focusedPane

    when {
        sessions == null -> MockSftpView(mono)
        active != null && active.controller.uiState is ConnectionUiState.Connected ->
            LiveSftpView(
                active.controller,
                active.subtitle,
                mono,
                onQuit = { sessions.setActiveView(SessionView.Terminal) },
            )
        else -> NoSessionSftpView(mono)
    }
}

/** View top bar: icon + "File transfer" + session subtitle. */
@Composable
internal fun SftpTopBar(subtitle: String, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("drive_file_move", size = 18.sp, color = Skerry.colors.cyanBright)
        Txt(stringResource(Res.string.sftp_title), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
        Txt(subtitle, color = Skerry.colors.faint, size = 11.5.sp, font = mono)
    }
}

// Live path.

/**
 * Live two-pane SFTP over the session's cached [TransferCoordinator]. The coordinator is opened once
 * ([ConnectionController.openTransferCoordinator]) and lives on the session scope — switching views
 * doesn't reset the panes' path/selection, `disconnect()` closes the channel. [subtitle] is the remote
 * pane's label.
 */
@Composable
private fun LiveSftpView(
    controller: ConnectionController,
    subtitle: String,
    mono: FontFamily,
    onQuit: () -> Unit,
) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(controller) { mutableStateOf(false) }
    var active by remember(controller) { mutableStateOf(ActivePane.Local) }
    // F8 Delete / F6 Move targets — the active pane at call time (the dialog reads its operands() for
    // text/execution). null — dialog closed.
    var deleteTarget by remember(controller) { mutableStateOf<FilePaneController?>(null) }
    var moveTarget by remember(controller) { mutableStateOf<FilePaneController?>(null) }
    // F5 Copy target — the active pane at call time (source; destination is the opposite pane).
    var copyTarget by remember(controller) { mutableStateOf<FilePaneController?>(null) }
    // F2 Rename target — a (pane, cursored row) pair at press time. null — dialog closed.
    var renameTarget by remember(controller) { mutableStateOf<Pair<FilePaneController, FileItem>?>(null) }
    // F3 View / F4 Edit — the open file editor. null — no editor. The controller comes from the
    // coordinator (session scope), so closing the modal never cancels a save in flight.
    var editor by remember(controller) { mutableStateOf<FileEditController?>(null) }
    // A pane's path bar is being edited (type-to-jump). While true the Column's key handler steps aside
    // (preview events fire parent-first) so arrows/Enter reach the focused path field, not the listing.
    var editingPath by remember(controller) { mutableStateOf(false) }
    // Same standdown while a pane's quick filter field is focused (Ctrl+F).
    var editingFilter by remember(controller) { mutableStateOf(false) }
    // Per-pane quick-filter focus ticks: 0 — the filter row is closed; each Ctrl+F increments the
    // active pane's tick, which both shows the row and (re)focuses its field.
    var localFilterTick by remember(controller) { mutableStateOf(0) }
    var remoteFilterTick by remember(controller) { mutableStateOf(0) }
    val localList = rememberLazyListState()
    val remoteList = rememberLazyListState()
    // Persistent show-hidden setting (Ctrl+H) — single source of truth for both panes.
    val sftpPrefs = LocalSftpPrefs.current
    val focus = remember(controller) { FocusRequester() }
    // UI scope only for showing the native file picker (Upload fallback); the transfer itself lives on
    // the session scope inside the coordinator and survives the view leaving composition.
    val uiScope = rememberCoroutineScope()
    // stringResource can't be called inside LaunchedEffect — hoist the value beforehand.
    val openFailedMsg = stringResource(Res.string.ftail_open_failed)
    LaunchedEffect(controller) {
        openError = null
        try {
            coord = controller.openTransferCoordinator(platformLocalBrowser(), subtitle)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // sshj/transport text carries addresses and internals — only the localized reason is shown.
            openError = openFailedMsg
        }
    }

    val c = coord
    // Once the coordinator is open — give the panes focus so arrows/Tab work without a click.
    LaunchedEffect(c) { if (c != null) focus.requestFocus() }
    // A path clicked in terminal output: reveal it in the remote pane once the coordinator is open.
    // Keyed on the request itself, so a second click while this view is already up is honoured too.
    LaunchedEffect(c, controller.pendingRevealPath) {
        if (c != null) controller.takeRevealRequest()?.let { c.remote.revealPath(it) }
    }
    // Apply the saved show-hidden setting to both panes: on coordinator open and on every Ctrl+H toggle
    // (sftpPrefs.showHidden is the effect key).
    LaunchedEffect(c, sftpPrefs.showHidden) {
        if (c != null) {
            c.local.setShowHidden(sftpPrefs.showHidden)
            c.remote.setShowHidden(sftpPrefs.showHidden)
        }
    }

    // Single point for F-keys: both a key press and a click on a bottom-pane row come here. Operations
    // act on the active pane, the target is its operands() (selection or the cursored row, mc-style).
    val fKey: (Int) -> Unit = remember(c) { fKey@{ n ->
        val coord = c ?: return@fKey
        val pane = if (active == ActivePane.Local) coord.local else coord.remote
        // F3 View / F4 Edit: the cursored row only (never the selection — one editor, one file);
        // directories/symlinks have nothing to show.
        val openEditor = { readOnly: Boolean ->
            pane.cursoredItem()?.takeIf { it.type == FileItemType.File }?.let { item ->
                editor = coord.openEditor(fromLocal = pane === coord.local, item = item, readOnly = readOnly)
            }
            Unit
        }
        when (n) {
            2 -> pane.cursoredItem()?.let { renameTarget = pane to it } // Rename the cursored row
            3 -> openEditor(true) // View: read-only
            4 -> openEditor(false) // Edit
            5 -> { // Copy: active pane's selection/cursor to the other (upload/download), with confirmation
                ensureOperandSelection(pane)
                if (pane.operands().isNotEmpty()) copyTarget = pane
            }
            6 -> { // Move: copy + delete the source, with confirmation
                ensureOperandSelection(pane)
                if (pane.operands().isNotEmpty()) moveTarget = pane
            }
            7 -> creatingFolder = true // MkDir
            8 -> { // Delete on the active pane, with confirmation
                ensureOperandSelection(pane)
                if (pane.operands().isNotEmpty()) deleteTarget = pane
            }
            9 -> { coord.local.refresh(); coord.remote.refresh() } // Refresh both panes
            10 -> onQuit() // Quit: back to this tab's terminal
            else -> {}
        }
    } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Skerry.colors.bg)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (c == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Path bar or filter field has focus: let its own handler take arrows/Enter/Esc.
                if (editingPath || editingFilter) return@onPreviewKeyEvent false
                // The editor is open: it owns every key, including the F-keys it redefines.
                if (editor != null) return@onPreviewKeyEvent false
                // Ctrl+H — show/hide hidden entries (dotfiles); toggle the persistent setting, and the
                // LaunchedEffect below applies it to both panes (single source of truth).
                if (event.isCtrlPressed && event.key == Key.H) {
                    sftpPrefs.setShowHidden(!sftpPrefs.showHidden)
                    return@onPreviewKeyEvent true
                }
                // Ctrl+F — quick name filter for the active pane (open the row and focus its field).
                if (event.isCtrlPressed && event.key == Key.F) {
                    if (active == ActivePane.Local) localFilterTick++ else remoteFilterTick++
                    return@onPreviewKeyEvent true
                }
                val pane = if (active == ActivePane.Local) c.local else c.remote
                val listState = if (active == ActivePane.Local) localList else remoteList
                val page = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
                when (event.key) {
                    Key.DirectionUp -> pane.moveCursor(-1)
                    Key.DirectionDown -> pane.moveCursor(1)
                    Key.PageUp -> pane.moveCursor(-page)
                    Key.PageDown -> pane.moveCursor(page)
                    Key.MoveHome -> pane.cursorToFirst()
                    Key.MoveEnd -> pane.cursorToLast()
                    Key.Enter, Key.NumPadEnter -> pane.enterCursored()
                    Key.DirectionRight -> pane.cursoredItem()?.let(pane::open)
                    Key.DirectionLeft, Key.Backspace -> pane.goUp()
                    Key.Insert -> pane.markCursoredAndAdvance()
                    Key.Spacebar -> pane.markCursored()
                    Key.Escape -> pane.clearSelection()
                    Key.Tab -> active = if (active == ActivePane.Local) ActivePane.Remote else ActivePane.Local
                    Key.F2 -> fKey(2)
                    Key.F3 -> fKey(3)
                    Key.F4 -> fKey(4)
                    Key.F5 -> fKey(5)
                    Key.F6 -> fKey(6)
                    Key.F7 -> fKey(7)
                    Key.F8 -> fKey(8)
                    Key.F9 -> fKey(9)
                    Key.F10 -> fKey(10)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable(),
    ) {
        // The panel's header is hidden while the editor is open: the editor brings its own, and two
        // stacked bars over one file — the upper one offering Upload/New folder that do nothing
        // there — are noise.
        if (editor == null) {
            LivePaneHeader(
                subtitle = subtitle,
                mono = mono,
                onUpload = {
                    val coord = c
                    if (coord != null) {
                        if (coord.local.selection.isNotEmpty()) {
                            coord.uploadSelection()
                        } else {
                            uiScope.launch { pickUploadSource()?.let { coord.uploadSource(it) } }
                        }
                    }
                },
                onNewFolder = { if (c != null) creatingFolder = true },
            )
            HLine()
        }
        val openEditor = editor
        when {
            // F3/F4: the editor takes over the panel area and the key bar, in this same window —
            // the panel's chrome stays, only what the function keys do changes.
            openEditor != null -> FileEditorScreen(
                controller = openEditor,
                onClose = { editor = null; focus.requestFocus() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            openError != null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaneNotice("error", stringResource(Res.string.sftp_unavailable), openError, Skerry.colors.sunset)
            }
            c == null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaneNotice("sync", stringResource(Res.string.sftp_opening), null, Skerry.colors.faint)
            }
            else -> {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LivePane(
                        c.local, "computer", Skerry.colors.dim, stringResource(Res.string.sftp_pane_local), mono,
                        listState = localList,
                        active = active == ActivePane.Local,
                        onActivate = { active = ActivePane.Local; focus.requestFocus() },
                        onEditingPath = { editingPath = it },
                        onEditingFilter = { editingFilter = it },
                        filterTick = localFilterTick,
                        onFilterClose = { localFilterTick = 0 },
                        restoreFocus = { focus.requestFocus() },
                        modifier = Modifier.weight(1f),
                    )
                    VLine(Skerry.colors.line)
                    LivePane(
                        c.remote, "dns", Skerry.colors.moss, stringResource(Res.string.sftp_pane_remote), mono,
                        listState = remoteList,
                        active = active == ActivePane.Remote,
                        onActivate = { active = ActivePane.Remote; focus.requestFocus() },
                        onEditingPath = { editingPath = it },
                        onEditingFilter = { editingFilter = it },
                        filterTick = remoteFilterTick,
                        onFilterClose = { remoteFilterTick = 0 },
                        restoreFocus = { focus.requestFocus() },
                        modifier = Modifier.weight(1f),
                    )
                }
                LiveTransferStrip(c.transfer, mono, onDismiss = c::clearTransfer)
            }
        }
        // The editor brings its own key bar (Save/Edit/Search/Quit) — the panel's would be a legend
        // for keys that aren't listening.
        if (openEditor == null) {
            HLine()
            FKeyBar(PANEL_FKEYS.map { it.copy(enabled = c != null) }, fKey, mono)
        }
    }

    if (creatingFolder && c != null) {
        // New folder is created in the active pane (F7/toolbar) — not always in remote, otherwise a
        // folder created from the local pane would "fly" to remote and seem to drop into another directory.
        val target = if (active == ActivePane.Local) c.local else c.remote
        NameDialog(
            title = stringResource(Res.string.sftp_new_folder),
            confirmLabel = stringResource(Res.string.sftp_create),
            initial = "",
            existing = target.currentEntryNames(),
            onConfirm = { target.mkdir(it); creatingFolder = false },
            onDismiss = { creatingFolder = false },
        )
    }

    // F8 Delete on the active pane: confirm by operands() (selection or the cursored row). If the target
    // suddenly emptied (a background refresh between the press and the frame) — close via an effect, not
    // by writing state directly in composition.
    deleteTarget?.let { pane ->
        val items = pane.operands()
        if (items.isEmpty()) {
            LaunchedEffect(pane) { deleteTarget = null }
        } else {
            ConfirmDeleteItemsDialog(
                items = items,
                onConfirm = { pane.deleteSelected(); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        }
    }

    // F6 Move the active pane to the other: copy + delete the source, with confirmation. Destination —
    // the opposite pane's current directory.
    moveTarget?.let { pane ->
        val coord = c
        val items = pane.operands()
        if (coord == null || items.isEmpty()) {
            LaunchedEffect(pane) { moveTarget = null }
        } else {
            val fromLocal = pane === coord.local
            val destPath = if (fromLocal) coord.remote.path else coord.local.path
            ConfirmMoveDialog(
                items = items,
                destLabel = if (fromLocal) stringResource(Res.string.sftp_pane_remote) else stringResource(Res.string.sftp_pane_local),
                destPath = destPath,
                onConfirm = { coord.moveSelection(fromLocal); moveTarget = null },
                onDismiss = { moveTarget = null },
            )
        }
    }

    // F5 Copy the active pane to the other (upload/download), with confirmation. Destination — the
    // opposite pane's current directory.
    copyTarget?.let { pane ->
        val coord = c
        val items = pane.operands()
        if (coord == null || items.isEmpty()) {
            LaunchedEffect(pane) { copyTarget = null }
        } else {
            val fromLocal = pane === coord.local
            val destPath = if (fromLocal) coord.remote.path else coord.local.path
            ConfirmCopyDialog(
                items = items,
                destLabel = if (fromLocal) stringResource(Res.string.sftp_pane_remote) else stringResource(Res.string.sftp_pane_local),
                destPath = destPath,
                onConfirm = {
                    if (fromLocal) coord.uploadSelection() else coord.downloadSelection()
                    copyTarget = null
                },
                onDismiss = { copyTarget = null },
            )
        }
    }

    // Overwrite conflict: a transfer (F5/F6 or drag) found same-named entries in the destination. Raised
    // by the coordinator after copy confirmation — otherwise we'd silently overwrite without asking.
    c?.overwrite?.let { conflict ->
        val single = conflict.names.singleOrNull()
        ConfirmDangerDialog(
            title = stringResource(Res.string.sftp_overwrite_q),
            body = if (single != null) stringResource(Res.string.sftp_overwrite_one, single)
            else stringResource(Res.string.sftp_overwrite_many, conflict.names.size),
            confirmLabel = stringResource(Res.string.sftp_overwrite),
            onConfirm = { c.resolveOverwrite(true) },
            onDismiss = { c.resolveOverwrite(false) },
        )
    }

    // F2 Rename the active pane's cursored row (classic mc — keyboard path, no menu).
    renameTarget?.let { (pane, item) ->
        NameDialog(
            title = stringResource(Res.string.sftp_rename),
            confirmLabel = stringResource(Res.string.sftp_rename),
            initial = item.name,
            existing = pane.currentEntryNames(),
            onConfirm = { pane.rename(item, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
}

/**
 * A live listing row (icon + name + size, no ⋮). Left-click just places the cursor (doesn't mark or
 * enter a directory) — responds instantly on press ([onPress]) so there's no double-click recognition
 * delay. A double click enters the directory ([onDoubleClick]). Selection — RMB press/drag (rubber-band,
 * [RowRubberBand]) or Space/Insert. No context menu: actions go through the bottom F-key bar.
 */
/** Which of the two panes is active (receives the keyboard and cursor highlight). */
private enum class ActivePane { Local, Remote }

/** Transfer progress bar: active (bar + counter), error (with a close), or nothing when Idle. */
@Composable
private fun LiveTransferStrip(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
    when (transfer) {
        TransferState.Idle -> Unit

        is TransferState.Active -> {
            HLine()
            Row(
                Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val up = transfer.direction == TransferDirection.Upload
                Sym(if (up) "upload" else "download", size = 16.sp, color = Skerry.colors.cyan)
                val title = if (transfer.fileCount > 1) {
                    stringResource(Res.string.ftail_transfer_counter, transfer.name, transfer.fileIndex, transfer.fileCount)
                } else {
                    transfer.name
                }
                Txt(title, color = Skerry.colors.textBright, size = 11.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = if (transfer.total > 0) transfer.transferred.toFloat() / transfer.total else 0f
                MeterBar(fraction, Skerry.colors.cyan, Modifier.weight(1f))
                val tail = if (transfer.total > 0) {
                    stringResource(Res.string.ftail_transfer_progress, humanSize(transfer.transferred), humanSize(transfer.total))
                } else {
                    humanSize(transfer.transferred)
                }
                Txt(tail, color = Skerry.colors.dim, size = 11.sp, font = mono)
            }
        }

        is TransferState.Failed -> {
            HLine()
            Row(
                Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("error", size = 16.sp, color = Skerry.colors.sunset)
                Txt(
                    stringResource(
                        Res.string.sftp_transfer_error,
                        transfer.name.ifBlank { stringResource(Res.string.ftail_file_fallback) },
                        transferFailureText(transfer.failure),
                    ),
                    color = Skerry.colors.sunset,
                    size = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconBtn("close", onClick = onDismiss, box = 26, icon = 16.sp)
            }
        }
    }
}

// Dialogs.

/** Centered notice in the listing area (opening/error/no session). */
@Composable
internal fun PaneNotice(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 26.sp, color = color)
            Txt(title, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) Txt(subtitle, color = Skerry.colors.faint, size = 11.5.sp)
        }
    }
}
