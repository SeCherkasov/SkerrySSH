package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_fkey_copy
import app.skerry.ui.generated.resources.ftail_fkey_delete
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_mkdir
import app.skerry.ui.generated.resources.ftail_fkey_move
import app.skerry.ui.generated.resources.ftail_fkey_quit
import app.skerry.ui.generated.resources.ftail_fkey_refresh
import app.skerry.ui.generated.resources.ftail_fkey_rename
import app.skerry.ui.generated.resources.ftail_fkey_view
import app.skerry.ui.generated.resources.ftail_open_failed
import app.skerry.ui.generated.resources.sftp_already_exists
import app.skerry.ui.generated.resources.sftp_cancel
import app.skerry.ui.generated.resources.sftp_copy
import app.skerry.ui.generated.resources.sftp_copy_to_q
import app.skerry.ui.generated.resources.sftp_create
import app.skerry.ui.generated.resources.sftp_delete
import app.skerry.ui.generated.resources.sftp_delete_file_body
import app.skerry.ui.generated.resources.sftp_delete_file_q
import app.skerry.ui.generated.resources.sftp_delete_folder_body
import app.skerry.ui.generated.resources.sftp_delete_folder_q
import app.skerry.ui.generated.resources.sftp_delete_items_body
import app.skerry.ui.generated.resources.sftp_delete_items_dirs_body
import app.skerry.ui.generated.resources.sftp_delete_items_q
import app.skerry.ui.generated.resources.sftp_error
import app.skerry.ui.generated.resources.sftp_items_count
import app.skerry.ui.generated.resources.sftp_loading
import app.skerry.ui.generated.resources.sftp_move
import app.skerry.ui.generated.resources.sftp_move_to_q
import app.skerry.ui.generated.resources.sftp_overwrite
import app.skerry.ui.generated.resources.sftp_overwrite_many
import app.skerry.ui.generated.resources.sftp_overwrite_one
import app.skerry.ui.generated.resources.sftp_overwrite_q
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import app.skerry.ui.generated.resources.sftp_opening
import app.skerry.ui.generated.resources.sftp_pane_local
import app.skerry.ui.generated.resources.sftp_pane_remote
import app.skerry.ui.generated.resources.sftp_rename
import app.skerry.ui.generated.resources.sftp_subtitle_host
import app.skerry.ui.generated.resources.sftp_title
import app.skerry.ui.generated.resources.sftp_transfer_body
import app.skerry.ui.generated.resources.sftp_transfer_error
import app.skerry.ui.generated.resources.sftp_unavailable
import app.skerry.ui.generated.resources.sftp_upload
import app.skerry.ui.generated.resources.sftp_what_single
import app.skerry.ui.session.SessionView
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sftp.pickUploadSource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine

private data class FileEntry(val icon: String, val iconColor: Color, val name: String, val meta: String, val selected: Boolean = false)

private val LOCAL_FILES = listOf(
    FileEntry("arrow_upward", D.faint, "..", ""),
    FileEntry("folder", D.cyanBright, "skerry-app", "Jun 21 09:14"),
    FileEntry("folder", D.cyanBright, "deploy-scripts", "Jun 18 22:40"),
    FileEntry("description", D.dim, "docker-compose.yml", "2.4 KB"),
    FileEntry("key", D.dim, "id_ed25519.pub", "96 B"),
    FileEntry("description", D.dim, "backup.tar.gz", "418 MB"),
)

private val REMOTE_FILES = listOf(
    FileEntry("arrow_upward", D.faint, "..", ""),
    FileEntry("folder", D.cyanBright, "html", "drwxr-xr-x"),
    FileEntry("folder", D.cyanBright, "releases", "drwxr-xr-x"),
    FileEntry("description", D.dim, "nginx.conf", "3.1 KB", selected = true),
    FileEntry("description", D.dim, "robots.txt", "112 B"),
    FileEntry("terminal", D.dim, "deploy.sh", "1.8 KB"),
)

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
    val active = sessions?.active

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
private fun SftpTopBar(subtitle: String, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
        Txt(stringResource(Res.string.sftp_title), color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        Txt(subtitle, color = D.faint, size = 11.5.sp, font = mono)
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
    // A pane's path bar is being edited (type-to-jump). While true the Column's key handler steps aside
    // (preview events fire parent-first) so arrows/Enter reach the focused path field, not the listing.
    var editingPath by remember(controller) { mutableStateOf(false) }
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
        } catch (e: Exception) {
            openError = e.message ?: openFailedMsg
        }
    }

    val c = coord
    // Once the coordinator is open — give the panes focus so arrows/Tab work without a click.
    LaunchedEffect(c) { if (c != null) focus.requestFocus() }
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
    // F3 View / F4 Edit are stubs (need file reading in the FileBrowser contract).
    val fKey: (Int) -> Unit = remember(c) { fKey@{ n ->
        val coord = c ?: return@fKey
        val pane = if (active == ActivePane.Local) coord.local else coord.remote
        when (n) {
            2 -> pane.cursoredItem()?.let { renameTarget = pane to it } // Rename the cursored row
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
            else -> {} // F3 View / F4 Edit — stub
        }
    } }

    Column(
        Modifier
            .fillMaxSize()
            .background(D.bg)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (c == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Path bar has focus (type-to-jump): let its own handler take arrows/Enter/Esc.
                if (editingPath) return@onPreviewKeyEvent false
                // Ctrl+H — show/hide hidden entries (dotfiles); toggle the persistent setting, and the
                // LaunchedEffect below applies it to both panes (single source of truth).
                if (event.isCtrlPressed && event.key == Key.H) {
                    sftpPrefs.setShowHidden(!sftpPrefs.showHidden)
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
        // Header: "File transfer" + subtitle on the left, Upload + New folder on the right.
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
                Txt(stringResource(Res.string.sftp_title), color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
                Txt(stringResource(Res.string.sftp_subtitle_host, subtitle), color = D.faint, size = 11.5.sp, font = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Upload: if there's a local file selection → transfer it; otherwise fall back to the
                // native file picker → upload into the remote pane's current directory. New folder — in remote.
                GhostButton(
                    stringResource(Res.string.sftp_upload),
                    onClick = {
                        val coord = c
                        if (coord != null) {
                            if (coord.local.selection.isNotEmpty()) {
                                coord.uploadSelection()
                            } else {
                                uiScope.launch { pickUploadSource()?.let { coord.uploadSource(it) } }
                            }
                        }
                    },
                    icon = "upload",
                )
                GhostButton(stringResource(Res.string.sftp_new_folder), onClick = { if (c != null) creatingFolder = true }, icon = "create_new_folder")
            }
        }
        HLine()
        when {
            openError != null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaneNotice("error", stringResource(Res.string.sftp_unavailable), openError, D.sunset)
            }
            c == null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaneNotice("sync", stringResource(Res.string.sftp_opening), null, D.faint)
            }
            else -> {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LivePane(
                        c.local, "computer", D.dim, stringResource(Res.string.sftp_pane_local), mono,
                        listState = localList,
                        active = active == ActivePane.Local,
                        onActivate = { active = ActivePane.Local; focus.requestFocus() },
                        onEditingPath = { editingPath = it },
                        restoreFocus = { focus.requestFocus() },
                        modifier = Modifier.weight(1f),
                    )
                    VLine(D.line)
                    LivePane(
                        c.remote, "dns", D.moss, stringResource(Res.string.sftp_pane_remote), mono,
                        listState = remoteList,
                        active = active == ActivePane.Remote,
                        onActivate = { active = ActivePane.Remote; focus.requestFocus() },
                        onEditingPath = { editingPath = it },
                        restoreFocus = { focus.requestFocus() },
                        modifier = Modifier.weight(1f),
                    )
                }
                LiveTransferStrip(c.transfer, mono, onDismiss = c::clearTransfer)
            }
        }
        HLine()
        FKeyBar(enabled = c != null, onKey = fKey, mono = mono)
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
 * Target of the active pane's batch F-operations: if nothing is marked — select the cursored row (mc
 * copies/moves/deletes the cursored item when there's no selection). On ".."/empty — no-op.
 */
private fun ensureOperandSelection(pane: FilePaneController) {
    if (pane.selection.isEmpty()) pane.cursoredItem()?.let { pane.selectOnly(it) }
}

/**
 * mc-style bottom F-key bar layout (classic, adapted for Skerry).
 * [done] = the key actually does something; false — a stub. Non-working ones are marked "*" in the bar
 * so it's visible what's wired and what isn't.
 */
private data class FKeyDef(val n: Int, val label: StringResource, val done: Boolean)

private val FKEY_LABELS = listOf(
    FKeyDef(2, Res.string.ftail_fkey_rename, done = true),
    FKeyDef(3, Res.string.ftail_fkey_view, done = false),
    FKeyDef(4, Res.string.ftail_fkey_edit, done = false),
    FKeyDef(5, Res.string.ftail_fkey_copy, done = true),
    FKeyDef(6, Res.string.ftail_fkey_move, done = true),
    FKeyDef(7, Res.string.ftail_fkey_mkdir, done = true),
    FKeyDef(8, Res.string.ftail_fkey_delete, done = true),
    FKeyDef(9, Res.string.ftail_fkey_refresh, done = true),
    FKeyDef(10, Res.string.ftail_fkey_quit, done = true),
)

/**
 * Bottom hotkey bar (mc/Total Commander): a row of cells "F3 View … F10 Quit". Both a cell click and an
 * F-key press go through the shared [onKey]. [enabled] disables the bar until the SFTP coordinator is open.
 */
@Composable
private fun FKeyBar(enabled: Boolean, onKey: (Int) -> Unit, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FKEY_LABELS.forEach { def ->
            FKeyCell(def, enabled, mono, onClick = { onKey(def.n) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FKeyCell(
    def: FKeyDef,
    enabled: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(D.panel)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Txt("F${def.n}", color = if (enabled) D.cyanBright else D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(
            stringResource(def.label),
            color = if (enabled) D.text else D.faint,
            size = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // "*" marks a non-working stub (F3 View / F4 Edit).
        if (!def.done) Txt("*", color = D.sunset, size = 11.sp, weight = FontWeight.SemiBold)
    }
}

/**
 * One live pane over [FilePaneController]: header [label] + path (no toolbar — up-navigation via the
 * ".." row) and the listing. File operations go through the bottom F-key bar; selection is left-click
 * (toggle) and rubber-band with held right-click.
 */
@Composable
private fun LivePane(
    pane: FilePaneController,
    icon: String,
    iconColor: Color,
    label: String,
    mono: FontFamily,
    listState: LazyListState,
    active: Boolean,
    onActivate: () -> Unit,
    onEditingPath: (Boolean) -> Unit,
    restoreFocus: () -> Unit,
    modifier: Modifier,
) {
    // Keep the cursored row in view during keyboard navigation. The LazyColumn index is offset by the
    // synthetic ".." row (it precedes entries when we're not at root).
    LaunchedEffect(pane.cursor, pane.cursorOnParent, pane.state) {
        val st = pane.state as? FilePaneState.Loaded ?: return@LaunchedEffect
        val target = if (pane.cursorOnParent) {
            0 // the ".." row is always on top
        } else {
            val idx = st.entries.indexOfFirst { it.path == pane.cursor }
            if (idx < 0) return@LaunchedEffect
            idx + if (pane.path != "/") 1 else 0
        }
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (visible.isEmpty() || target < first || target > last) listState.scrollToItem(target)
    }

    // Activating a pane by clicking it must not flash a ripple over the whole pane — bare click, no
    // indication (the highlight is the header/cursor recolor, not a Material overlay).
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivate),
    ) {
        Row(
            Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = if (active) iconColor else D.faint)
            Txt(
                label.uppercase(),
                color = if (active) D.cyanBright else D.faint,
                size = 11.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            PathField(
                pane = pane,
                mono = mono,
                onActivate = onActivate,
                onEditingPath = onEditingPath,
                restoreFocus = restoreFocus,
                modifier = Modifier.weight(1f),
            )
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = pane.state) {
                FilePaneState.Loading -> PaneNotice("sync", stringResource(Res.string.sftp_loading), null, D.faint)
                is FilePaneState.Error -> PaneNotice("error", stringResource(Res.string.sftp_error), st.message, D.sunset)
                is FilePaneState.Loaded -> LivePaneList(
                    pane = pane,
                    entries = st.entries,
                    mono = mono,
                    listState = listState,
                    active = active,
                    onActivate = onActivate,
                )
            }
        }
    }
}

/**
 * Editable path bar in a pane header. Shows [pane].path as text; a click turns it into an input so a
 * known destination can be typed and jumped to (Enter → [FilePaneController.goToPath], Esc → cancel;
 * blurring the field also cancels). While editing, [onEditingPath] tells the screen to stand its key
 * handler down so arrows/Enter reach this field, not the listing; on close [restoreFocus] hands the
 * keyboard back to the panes.
 */
@Composable
private fun PathField(
    pane: FilePaneController,
    mono: FontFamily,
    onActivate: () -> Unit,
    onEditingPath: (Boolean) -> Unit,
    restoreFocus: () -> Unit,
    modifier: Modifier,
) {
    var editing by remember(pane) { mutableStateOf(false) }
    if (!editing) {
        Txt(
            pane.path,
            color = D.textBright,
            size = 11.5.sp,
            font = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onActivate(); editing = true },
            ),
        )
        return
    }
    // Prefill with the current path, fully selected so typing replaces it outright.
    var draft by remember { mutableStateOf(TextFieldValue(pane.path, TextRange(0, pane.path.length))) }
    val fieldFocus = remember { FocusRequester() }
    // Close once and restore keyboard focus to the panes (Enter/Esc/blur all route here).
    val close = {
        editing = false
        onEditingPath(false)
        restoreFocus()
    }
    LaunchedEffect(Unit) {
        onEditingPath(true)
        fieldFocus.requestFocus()
    }
    // Guard against the initial unfocused frame before requestFocus lands, then close on any real blur
    // (clicking away/switching panes) so a stale editor is never left open.
    var everFocused by remember { mutableStateOf(false) }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 11.5.sp, fontFamily = mono),
        cursorBrush = SolidColor(D.cyan),
        modifier = modifier
            .focusRequester(fieldFocus)
            .onFocusChanged {
                if (it.isFocused) everFocused = true else if (everFocused) close()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { pane.goToPath(draft.text); close(); true }
                    Key.Escape -> { close(); true }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(D.bg)
                    .border(1.dp, D.lineStrong, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { inner() }
        },
    )
}

@Composable
private fun LivePaneList(
    pane: FilePaneController,
    entries: List<FileItem>,
    mono: FontFamily,
    listState: LazyListState,
    active: Boolean,
    onActivate: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(6.dp), state = listState) {
        if (pane.path != "/") {
            item(key = "..") {
                LiveFileRow(
                    "arrow_upward", D.faint, "..", "", selected = false, cursored = pane.cursorOnParent, active = active, mono,
                    // A single click only puts the cursor on ".."; going up is a double click (like entering a directory).
                    onPress = { onActivate(); pane.setCursorOnParent() },
                    onDoubleClick = { onActivate(); pane.goUp() },
                    rubberBand = null, // the ".." row can't be marked — no rubber-band needed on it
                )
            }
        }
        items(entries, key = { it.path }) { entry ->
            // Single click (on press): activate the pane and place the cursor — doesn't mark or enter.
            // Entering a directory is a double click (open; no-op for a file). Selection — RMB/Space/Insert.
            val onPress = {
                onActivate()
                pane.setCursor(entry)
            }
            val onDoubleClick = {
                onActivate()
                pane.setCursor(entry)
                pane.open(entry)
            }
            LiveFileRow(
                icon = fileItemIcon(entry.type),
                iconColor = if (entry.type == FileItemType.Directory) D.cyanBright else D.dim,
                name = entry.name,
                meta = if (entry.type == FileItemType.File) humanSize(entry.size) else "",
                selected = entry.path in pane.selection,
                cursored = entry.path == pane.cursor,
                active = active,
                mono = mono,
                onPress = onPress,
                onDoubleClick = onDoubleClick,
                rubberBand = RowRubberBand(entry, pane, listState, entries, onActivate),
            )
        }
    }
}

/**
 * Row rubber-band gesture (mc-style selection with held RMB). A press paints [entry] (toggle by its
 * current state), dragging down/up paints the whole range with the same sign. The mouse cursor captured
 * by the pressed row is translated into list coordinates via the row offset in [listState], then the row
 * under it is found in [listState] and painted up to it. No scroll on edge drag (the right button doesn't
 * scroll the list) — offsets are stable for the whole gesture.
 */
private class RowRubberBand(
    val entry: FileItem,
    val pane: FilePaneController,
    val listState: LazyListState,
    val entries: List<FileItem>,
    val onActivate: () -> Unit,
) {
    // Member-extension on the restricted-scope AwaitPointerEventScope — else awaitPointerEvent() can't be called.
    suspend fun AwaitPointerEventScope.dragSelect(press: PointerEvent) {
        onActivate() // painting in this pane — make it active (F-keys go here)
        // Fix the sign by the pressed row: not marked → paint, marked → clear.
        val select = entry.path !in pane.selection
        pane.rubberBandTo(entry, entry, select)
        press.changes.forEach { it.consume() }
        val anchorOffset = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == entry.path }?.offset ?: 0
        while (true) {
            val drag = awaitPointerEvent()
            if (!drag.buttons.isSecondaryPressed) break // RMB released — end of gesture
            val listY = anchorOffset + drag.changes.first().position.y
            val key = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { listY >= it.offset && listY < it.offset + it.size }?.key as? String
            key?.let { k -> entries.firstOrNull { it.path == k } }
                ?.let { target -> pane.rubberBandTo(entry, target, select) }
            drag.changes.forEach { it.consume() }
        }
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

/** Double-click threshold for a row (ms between two LMB presses → enter directory). */
private const val DOUBLE_CLICK_MS = 350L

@Composable
private fun LiveFileRow(
    icon: String,
    iconColor: Color,
    name: String,
    meta: String,
    selected: Boolean,
    cursored: Boolean,
    active: Boolean,
    mono: FontFamily,
    onPress: () -> Unit,
    onDoubleClick: () -> Unit,
    // Data for held-RMB rubber-band (mc): the anchor row, controller, list state for translating the
    // cursor position to a row, and the current listing. null for the synthetic ".." row.
    rubberBand: RowRubberBand?,
) {
    // Latest callbacks without restarting the gesture (pointerInput is keyed on Unit — it lives the row's whole life).
    val currentPress by rememberUpdatedState(onPress)
    val currentDouble by rememberUpdatedState(onDoubleClick)
    // Cursor (navigation position) and selection (marked files) are distinct in mc: the active pane's
    // cursor is a bright bar, the inactive one's a border; selection is a highlight + bold name.
    val rowBg = when {
        cursored && active -> D.cyan.copy(alpha = 0.22f)
        selected -> D.cyan06
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(rowBg)
            .then(if (cursored && !active) Modifier.border(1.dp, D.lineStrong, RoundedCornerShape(5.dp)) else Modifier)
            // LMB: our own tap parsing in one loop — more reliable than detectTapGestures (which lost
            // double clicks to slop/timeouts). Each LMB press instantly places the cursor (currentPress);
            // two presses closer than DOUBLE_CLICK_MS are a double click (enter directory). Time comes
            // from the event itself (uptimeMillis) — deterministic. RMB is skipped (rubber-band below handles it).
            .pointerInput(Unit) {
                var lastDownMs = 0L
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Press || e.buttons.isSecondaryPressed) continue
                        val t = e.changes.first().uptimeMillis
                        currentPress()
                        if (t - lastDownMs <= DOUBLE_CLICK_MS) {
                            currentDouble()
                            lastDownMs = 0L // reset so a triple click doesn't give a second enter
                        } else {
                            lastDownMs = t
                        }
                    }
                }
            }
            // RMB (mc): a press paints the row (toggle by sign), dragging down/up paints the range with
            // the same sign — rubber-band. It runs after the tap detector (inner) and consumes the right
            // button in the Main pass earlier — so detectTapGestures (requireUnconsumed) ignores it, while
            // LMB is untouched (no consume) and reaches the tap detector.
            .then(
                if (rubberBand != null) {
                    // Key — anchor+listing (stable during a drag: a selection change doesn't touch them,
                    // so the gesture isn't restarted mid rubber-band).
                    Modifier.pointerInput(rubberBand.entry, rubberBand.entries) {
                        awaitPointerEventScope {
                            while (true) {
                                val press = awaitPointerEvent()
                                if (press.type != PointerEventType.Press) continue
                                if (!press.buttons.isSecondaryPressed) continue
                                with(rubberBand) { dragSelect(press) }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(icon, size = 17.sp, color = iconColor)
        Txt(
            name,
            color = when {
                name == ".." -> D.dim
                selected -> D.cyanBright
                else -> D.textBright
            },
            size = 12.sp,
            font = mono,
            weight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (meta.isNotEmpty()) Txt(meta, color = D.faint, size = 11.sp)
    }
}

/** Transfer progress bar: active (bar + counter), error (with a close), or nothing when Idle. */
@Composable
private fun LiveTransferStrip(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
    when (transfer) {
        TransferState.Idle -> Unit

        is TransferState.Active -> {
            HLine()
            Row(
                Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val up = transfer.direction == TransferDirection.Upload
                Sym(if (up) "upload" else "download", size = 16.sp, color = D.cyan)
                val counter = if (transfer.fileCount > 1) " (${transfer.fileIndex}/${transfer.fileCount})" else ""
                Txt(transfer.name + counter, color = D.textBright, size = 11.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = if (transfer.total > 0) transfer.transferred.toFloat() / transfer.total else 0f
                MeterBar(fraction, D.cyan, Modifier.weight(1f))
                val tail = if (transfer.total > 0) {
                    "${humanSize(transfer.transferred)} / ${humanSize(transfer.total)}"
                } else {
                    humanSize(transfer.transferred)
                }
                Txt(tail, color = D.dim, size = 11.sp, font = mono)
            }
        }

        is TransferState.Failed -> {
            HLine()
            Row(
                Modifier.fillMaxWidth().background(D.surface2).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("error", size = 16.sp, color = D.sunset)
                Txt(
                    stringResource(Res.string.sftp_transfer_error, transfer.name, transfer.message),
                    color = D.sunset,
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

/** Modal name input (New folder / Rename). Confirm is enabled only for a valid name. */
@Composable
internal fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    existing: Set<String> = emptySet(),
) {
    // Keyed on initial: on a re-show under a different entry (rename without leaving composition) the
    // field must reset to the new name rather than keep the old one.
    var name by remember(initial) { mutableStateOf(initial) }
    val trimmed = name.trim()
    // Catch name conflicts early (name already in the directory) — otherwise mkdir/rename would fail
    // into Error and the pane would "jump"; instead show a message in the dialog and keep it open.
    // initial is allowed (rename to the same name — a no-op, not a conflict).
    val conflict = trimmed.isNotEmpty() && trimmed != initial && trimmed in existing
    // Reject an empty name, a path separator, "."/".." and control characters (null byte/newline) — the
    // latter break paths on POSIX FS/SFTP servers and the row layout.
    val valid = trimmed.isNotEmpty() &&
        "/" !in trimmed &&
        trimmed != "." &&
        trimmed != ".." &&
        trimmed.none { it == '\u0000' || it == '\n' || it == '\r' }
    val mono = LocalFonts.current.mono
    val ok = valid && !conflict
    val submit = { if (ok) onConfirm(trimmed) }
    // Autofocus: the field should be ready for input the moment the dialog opens, without a click.
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    SftpDialogFrame(onDismiss = onDismiss) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
            // Border in decorationBox so a click anywhere in the field places the caret.
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = D.text, fontSize = 13.sp, fontFamily = mono),
                cursorBrush = SolidColor(D.cyan),
                // Enter confirms (if the name is valid), Esc closes — handler before the focusable field.
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { submit(); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(D.panel)
                            .border(1.dp, D.lineStrong, RoundedCornerShape(7.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) { inner() }
                },
            )
            if (conflict) Txt(stringResource(Res.string.sftp_already_exists, trimmed), color = D.sunset, size = 11.5.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss)
                PrimaryButton(
                    confirmLabel,
                    onClick = submit,
                    bg = if (ok) D.cyan else D.whiteFaint,
                )
            }
    }
}

/**
 * Confirmation for deleting a batch of [items] (F8 on the active pane): a single item — its name,
 * several — a count. The text warns about recursion if the batch contains a directory.
 */
@Composable
private fun ConfirmDeleteItemsDialog(items: List<FileItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val single = items.singleOrNull()
    val hasDir = items.any { it.type == FileItemType.Directory }
    val title = when {
        single != null && single.type == FileItemType.Directory -> stringResource(Res.string.sftp_delete_folder_q)
        single != null -> stringResource(Res.string.sftp_delete_file_q)
        else -> stringResource(Res.string.sftp_delete_items_q, items.size)
    }
    val body = when {
        single != null && single.type == FileItemType.Directory ->
            stringResource(Res.string.sftp_delete_folder_body, single.name)
        single != null -> stringResource(Res.string.sftp_delete_file_body, single.name)
        hasDir -> stringResource(Res.string.sftp_delete_items_dirs_body, items.size)
        else -> stringResource(Res.string.sftp_delete_items_body, items.size)
    }
    ConfirmDangerDialog(title, body, stringResource(Res.string.sftp_delete), onConfirm, onDismiss)
}

/**
 * Confirmation for copying a batch of [items] into directory [destPath] of pane [destLabel] (F5).
 */
@Composable
private fun ConfirmCopyDialog(
    items: List<FileItem>,
    destLabel: String,
    destPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = items.singleOrNull()
    val what = if (single != null) stringResource(Res.string.sftp_what_single, single.name) else stringResource(Res.string.sftp_items_count, items.size)
    ConfirmDangerDialog(
        title = stringResource(Res.string.sftp_copy_to_q, destLabel),
        body = stringResource(Res.string.sftp_transfer_body, what, destPath),
        confirmLabel = stringResource(Res.string.sftp_copy),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmBg = D.cyan,
        confirmFg = D.ink,
    )
}

/**
 * Confirmation for moving a batch of [items] into directory [destPath] of pane [destLabel] (F6). Moving
 * between filesystems = copy + delete the source, so confirm explicitly.
 */
@Composable
private fun ConfirmMoveDialog(
    items: List<FileItem>,
    destLabel: String,
    destPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = items.singleOrNull()
    val what = if (single != null) stringResource(Res.string.sftp_what_single, single.name) else stringResource(Res.string.sftp_items_count, items.size)
    ConfirmDangerDialog(
        title = stringResource(Res.string.sftp_move_to_q, destLabel),
        body = stringResource(Res.string.sftp_transfer_body, what, destPath),
        confirmLabel = stringResource(Res.string.sftp_move),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmBg = D.cyan,
        confirmFg = D.ink,
    )
}

/**
 * Shared confirmation dialog frame (title + text + Cancel/action). Keyboard-driven (mc-style): by
 * default focus is on the action — Enter confirms immediately (F8→Enter deletes); ←/→/Tab switch between
 * Cancel and the action, Esc cancels. The focused button is outlined.
 */
@Composable
internal fun ConfirmDangerDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmBg: Color = D.sunset,
    confirmFg: Color = D.ink,
) {
    var focusConfirm by remember { mutableStateOf(true) }
    val dialogFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { dialogFocus.requestFocus() }
    SftpDialogFrame(
        onDismiss = onDismiss,
        modifier = Modifier
            .focusRequester(dialogFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { if (focusConfirm) onConfirm() else onDismiss(); true }
                    Key.Escape -> { onDismiss(); true }
                    Key.DirectionLeft, Key.DirectionRight, Key.Tab -> { focusConfirm = !focusConfirm; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
            Txt(body, color = D.faint, size = 12.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButtonFocus(focused = !focusConfirm) { GhostButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss) }
                DialogButtonFocus(focused = focusConfirm) {
                    PrimaryButton(confirmLabel, onClick = onConfirm, bg = confirmBg, fg = confirmFg)
                }
            }
    }
}

/**
 * Shared SFTP modal frame: [Dialog] + a 340dp card (surface/12 rounding/line border, 18 padding, content
 * in a column with 14 spacing). [modifier] is appended after the padding — so ConfirmDangerDialog hangs
 * its focus/keyboard handler without changing the frame.
 */
@Composable
private fun SftpDialogFrame(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surface)
                .border(1.dp, D.line, RoundedCornerShape(12.dp))
                .padding(18.dp)
                .then(modifier),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Outlines a dialog button when it has keyboard focus (←/→/Tab). */
@Composable
private fun DialogButtonFocus(focused: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (focused) Modifier.border(1.5.dp, D.cyanBright, RoundedCornerShape(9.dp)) else Modifier)
            .padding(1.5.dp),
    ) { content() }
}

/** File/directory deletion confirmation. */
@Composable
internal fun ConfirmDeleteDialog(entry: FileItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val isDir = entry.type == FileItemType.Directory
    SftpDialogFrame(onDismiss = onDismiss) {
            Txt(if (isDir) stringResource(Res.string.sftp_delete_folder_q) else stringResource(Res.string.sftp_delete_file_q), color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
            Txt(
                if (isDir) stringResource(Res.string.sftp_delete_folder_body, entry.name)
                else stringResource(Res.string.sftp_delete_file_body, entry.name),
                color = D.faint,
                size = 12.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss)
                PrimaryButton(stringResource(Res.string.sftp_delete), onClick = onConfirm, bg = D.sunset, fg = D.ink)
            }
    }
}

// Shared and mock path.

/** Centered notice in the listing area (opening/error/no session). */
@Composable
private fun PaneNotice(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 26.sp, color = color)
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) Txt(subtitle, color = D.faint, size = 11.5.sp)
        }
    }
}

/** Material icon name ([Sym] ligature) for a file-pane item type. */
private fun fileItemIcon(type: FileItemType): String = when (type) {
    FileItemType.Directory -> "folder"
    FileItemType.Symlink -> "link"
    FileItemType.File, FileItemType.Other -> "description"
}

/** A live session exists but isn't connected: header + notice. */
@Composable
private fun NoSessionSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        SftpTopBar(stringResource(Res.string.sftp_no_session), mono)
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PaneNotice("cloud_off", stringResource(Res.string.sftp_no_session), stringResource(Res.string.sftp_no_session_hint), D.faint)
        }
    }
}

/** Static mock (offscreen render/preview without a session backend). */
@Composable
private fun MockSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
                Txt(stringResource(Res.string.sftp_title), color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
                Txt("root@prod-web-01 · SFTP", color = D.faint, size = 11.5.sp, font = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(Res.string.sftp_upload), onClick = {}, icon = "upload")
                GhostButton(stringResource(Res.string.sftp_new_folder), onClick = {}, icon = "create_new_folder")
            }
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            MockPane("computer", D.dim, stringResource(Res.string.sftp_pane_local), "~/projects", LOCAL_FILES, mono, Modifier.weight(1f))
            VLine(D.line)
            MockPane("dns", D.moss, stringResource(Res.string.sftp_pane_remote), "/var/www", REMOTE_FILES, mono, Modifier.weight(1f))
        }
        HLine()
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym("upload", size = 16.sp, color = D.cyan)
            Txt("backup.tar.gz", color = D.textBright, size = 11.5.sp, font = mono)
            MeterBar(0.64f, D.cyan, Modifier.weight(1f))
            Txt("64% · 12.4 MB/s · 02:18 left", color = D.dim, size = 11.sp, font = mono)
        }
    }
}

@Composable
private fun MockPane(
    icon: String,
    iconColor: Color,
    label: String,
    path: String,
    files: List<FileEntry>,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = iconColor)
            Txt(label.uppercase(), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Txt(path, color = D.textBright, size = 11.5.sp, font = mono)
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(6.dp)) {
            files.forEach { MockRow(it, mono) }
        }
    }
}

@Composable
private fun MockRow(entry: FileEntry, mono: FontFamily) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (entry.selected) D.cyan06 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(entry.icon, size = 17.sp, color = entry.iconColor)
        Txt(entry.name, color = if (entry.name == "..") D.dim else D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        if (entry.meta.isNotEmpty()) Txt(entry.meta, color = D.faint, size = 11.sp)
    }
}
