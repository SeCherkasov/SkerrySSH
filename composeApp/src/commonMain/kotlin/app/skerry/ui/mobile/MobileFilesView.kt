package app.skerry.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import app.skerry.ui.files.FileEditorScreen
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.PathJumpField
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.fileBrowserFailureText
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.files.transferFailureText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_file_fallback
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_view
import app.skerry.ui.generated.resources.ftail_local_label
import app.skerry.ui.generated.resources.ftail_open_failed
import app.skerry.ui.generated.resources.sftp_connecting
import app.skerry.ui.generated.resources.sftp_create
import app.skerry.ui.generated.resources.sftp_create_directory
import app.skerry.ui.generated.resources.sftp_delete
import app.skerry.ui.generated.resources.sftp_download_to_device
import app.skerry.ui.generated.resources.sftp_error
import app.skerry.ui.generated.resources.sftp_files_title
import app.skerry.ui.generated.resources.sftp_filter_hint
import app.skerry.ui.generated.resources.sftp_loading
import app.skerry.ui.generated.resources.sftp_meta_joined
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_overwrite
import app.skerry.ui.generated.resources.sftp_overwrite_many
import app.skerry.ui.generated.resources.sftp_overwrite_one
import app.skerry.ui.generated.resources.sftp_overwrite_q
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import app.skerry.ui.generated.resources.sftp_rename
import app.skerry.ui.generated.resources.sftp_transfer_error
import app.skerry.ui.generated.resources.sftp_unavailable
import app.skerry.ui.generated.resources.sftp_upload_file
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.fileDateText
import app.skerry.ui.sftp.permissionsText
import app.skerry.ui.sftp.sizeText
import app.skerry.ui.sftp.pickDownloadTarget
import app.skerry.ui.sftp.pickUploadSource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import app.skerry.ui.sftp.ConfirmDangerDialog
import app.skerry.ui.sftp.ConfirmDeleteDialog
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.MeterBar
import app.skerry.ui.sftp.NameDialog
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Root Files tab: single-pane browser of the active session's remote SFTP over a cached
 * [TransferCoordinator]. The local device pane is removed (Android scoped storage makes it
 * useless), and so is the Remote/Local switch — the screen always shows the host's directory.
 *
 * Mode is picked by [mobileFilesMode]: no session manager — static mock ([MockMobileFilesView]);
 * a connected active session — live listing; otherwise a "no session" notice. Visible actions:
 * tap a folder to enter, tap a file (`ios_share` icon) to download via the system "Save to…", FAB
 * creates a directory / uploads a file from the device. Rename/delete and "Download to device" are
 * in the context menu (long-press).
 */
@Composable
fun MobileFilesScreen(onBack: (() -> Unit)? = null) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    val uiState = active?.controller?.uiState
    val connected = uiState is ConnectionUiState.Connected
    val connecting = uiState is ConnectionUiState.Connecting
    when (mobileFilesMode(hasSessions = sessions != null, connected = connected, connecting = connecting)) {
        MobileFilesMode.Preview -> MockMobileFilesView(mono)
        // active?.let instead of !!: sessions.active is a derived getter over two snapshot fields,
        // and a session-close race could leave it null even while connected — fall back to nothing.
        MobileFilesMode.Live -> active?.let { LiveMobileFilesView(it.controller, it.subtitle, mono, onBack) }
        // "Connecting…" with the host subtitle: after tapping SFTP/Connect the session is still
        // handshaking — don't flash "No active session". active?.let for the same close race as Live.
        MobileFilesMode.Connecting -> active?.let { ConnectingMobileFilesView(it.subtitle, onBack) }
        MobileFilesMode.NoSession -> NoSessionMobileFilesView(mono, onBack)
    }
}

// Live path.

/**
 * Live Files screen over the session's cached [TransferCoordinator] (opened once and lives on the
 * session scope — switching tabs doesn't reset path/selection). Shows only the Remote pane (the
 * host's directory); the coordinator's Local pane is used only as the "Download to device" sink.
 */
@Composable
private fun LiveMobileFilesView(controller: ConnectionController, subtitle: String, mono: FontFamily, onBack: (() -> Unit)? = null) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(controller) { mutableStateOf(false) }
    var fabOpen by remember(controller) { mutableStateOf(false) }
    // Quick name filter row under the breadcrumb (the funnel icon toggles it).
    var filterOpen by remember(controller) { mutableStateOf(false) }
    // Built-in viewer/editor over the cursored file (long-press menu). The controller comes from the
    // coordinator (session scope), so closing the modal never cancels a save in flight.
    var editor by remember(controller) { mutableStateOf<FileEditController?>(null) }
    // UI scope only for the native file picker (FAB Upload); the transfer itself lives on the
    // session scope inside the coordinator and survives the view leaving composition.
    val uiScope = rememberCoroutineScope()
    // stringResource can't be called inside LaunchedEffect — resolve the value beforehand.
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
    val openEditor = editor
    // Built-in viewer/editor (long-press → View/Edit): takes over the screen instead of opening a
    // dialog, so it gets the full height and the same chrome as the file list.
    if (openEditor != null) {
        FileEditorScreen(
            controller = openEditor,
            onClose = { editor = null },
            modifier = Modifier.fillMaxSize(),
            // No function keys on touch: Save/Close sit in the header instead.
            showKeyBar = false,
        )
        return
    }
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle(onBack)
            when {
                // One neutral centered status for the whole waiting phase: session handshake →
                // SFTP channel open → first directory listing. Text and position stay fixed (same
                // idea as the terminal — it doesn't matter whether it's a terminal or sftp), so the
                // screen doesn't flash or jump vertically. Breadcrumb + list show only once loaded.
                openError != null -> MobileFilesNoticeBox("error", stringResource(Res.string.sftp_unavailable), openError, Skerry.colors.sunset)
                c == null || c.remote.state is FilePaneState.Loading ->
                    MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_connecting), subtitle, Skerry.colors.cyanBright)
                else -> {
                    val pane = c.remote
                    // File row's visible action (ios_share): download OUT of the sandbox via the
                    // system "Save to…" ([pickDownloadTarget] → SAF on Android, native dialog on
                    // desktop) — the picker is suspend, so it goes through uiScope. Stabilized on
                    // (c, uiScope) so the lambda isn't recreated on every recomposition (e.g. when
                    // the transfer card updates) and doesn't needlessly invalidate the list.
                    val onTransfer = remember(c, uiScope) {
                        { item: FileItem ->
                            uiScope.launch { pickDownloadTarget(item.name)?.let { c.downloadToTarget(item, it) } }
                            Unit
                        }
                    }
                    // "Download to device" (long-press on a file): download WITHOUT a dialog into
                    // the app's directory (coordinator's Local pane), so the file lands on device right away.
                    val downloadHere = remember(c) {
                        { item: FileItem -> c.remote.selectOnly(item); c.downloadSelection() }
                    }
                    MobileFilesBreadcrumbRow(
                        pane.label.ifBlank { stringResource(Res.string.ftail_local_label) },
                        pane.path,
                        mono, onGoToPath = pane::goToPath,
                        onToggleFilter = { filterOpen = !filterOpen },
                        filterActive = filterOpen || pane.nameFilter.isNotEmpty(),
                    )
                    if (filterOpen || pane.nameFilter.isNotEmpty()) {
                        MobileFilterRow(pane, mono, onClose = { filterOpen = false })
                    }
                    // View/Edit a remote file in place (parity with desktop F3/F4).
                    val openEditor = remember(c) {
                        { item: FileItem, readOnly: Boolean ->
                            editor = c.openEditor(fromLocal = false, item = item, readOnly = readOnly)
                        }
                    }
                    MobileLivePane(
                        pane = pane,
                        mono = mono,
                        onTransfer = onTransfer,
                        onDownloadHere = downloadHere,
                        onOpenEditor = openEditor,
                        modifier = Modifier.weight(1f),
                    )
                    MobileTransferCard(c.transfer, mono, onDismiss = c::clearTransfer)
                    Spacer(Modifier.height(88.dp)) // room for the floating FAB (push screen has no tab bar)
                }
            }
        }
        // Scrim behind the expanded menu: tapping outside collapses the FAB (mobile speed-dial idiom).
        if (fabOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        fabOpen = false
                    },
            )
        }
        // Single "+" FAB: expands actions over the Remote pane — "Create directory" and "Upload
        // file" (uploadSource targets remote.path). Actions stack up ABOVE the button with labels.
        if (c != null && openError == null && c.remote.state !is FilePaneState.Loading) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (fabOpen) {
                    MobileFabAction("create_new_folder", stringResource(Res.string.sftp_create_directory)) {
                        fabOpen = false
                        creatingFolder = true
                    }
                    MobileFabAction("upload", stringResource(Res.string.sftp_upload_file)) {
                        fabOpen = false
                        uiScope.launch { pickUploadSource()?.let { c.uploadSource(it) } }
                    }
                }
                MobileFabButton(open = fabOpen, onClick = { fabOpen = !fabOpen })
            }
        }
        if (creatingFolder && c != null) {
            // Create a directory in the Remote pane. Reuses the shared NameDialog
            // (validates empty/"/"/"."/".."/control chars), like desktop "New folder".
            val pane = c.remote
            NameDialog(
                title = stringResource(Res.string.sftp_new_folder),
                confirmLabel = stringResource(Res.string.sftp_create),
                initial = "",
                onConfirm = { pane.mkdir(it); creatingFolder = false },
                onDismiss = { creatingFolder = false },
            )
        }
        // Overwrite conflict (download/upload found a same-named object at the destination) — the
        // same confirmation dialog as desktop; the coordinator is shared, so [overwrite] state is shared too.
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
    }
}

/**
 * Live pane (Remote or Local) over [FilePaneController]: listing + ".." up row + rename/delete
 * context menu (long-press). [onTransfer] is the file transfer action (the file row's `ios_share`
 * visible action).
 */
@Composable
private fun MobileLivePane(
    pane: FilePaneController,
    mono: FontFamily,
    onTransfer: (FileItem) -> Unit,
    onDownloadHere: ((FileItem) -> Unit)?,
    onOpenEditor: (FileItem, Boolean) -> Unit,
    modifier: Modifier,
) {
    var renaming by remember(pane) { mutableStateOf<FileItem?>(null) }
    var deleting by remember(pane) { mutableStateOf<FileItem?>(null) }

    // Always show a new directory from the top. The pane doesn't reload through Loading (reload()
    // sets Loaded directly), so the scroll column survives the directory change, and without an
    // explicit reset verticalScroll would inherit the previous directory's scroll — a leftover
    // offset (after a fling/overscroll) would carry into the new listing, making the list jump a
    // few pixels on every navigation. Reset to the top on path change (scrollTo is instant).
    val scroll = rememberScrollState()
    LaunchedEffect(pane.path) { scroll.scrollTo(0) }

    Box(modifier.fillMaxWidth()) {
        when (val st = pane.state) {
            FilePaneState.Loading -> MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_loading), null, Skerry.colors.faint)
            is FilePaneState.Error ->
                MobileFilesNoticeBox("error", stringResource(Res.string.sftp_error), fileBrowserFailureText(st.failure), Skerry.colors.sunset)
            is FilePaneState.Loaded -> Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 12.dp, start = 12.dp, end = 12.dp),
            ) {
                if (pane.path != "/") {
                    MobileFileUpRow(mono, onClick = pane::goUp)
                }
                st.entries.forEach { entry ->
                    // key by path: forEach in Column reuses slots positionally, and without a key
                    // an open context menu would "migrate" to a different row after refresh/rename.
                    key(entry.path) {
                        val isDir = entry.type == FileItemType.Directory
                        MobileFileRow(
                            entry = entry,
                            selected = entry.path in pane.selection,
                            mono = mono,
                            onClick = { if (isDir) pane.open(entry) else onTransfer(entry) },
                            onDownloadHere = if (!isDir && onDownloadHere != null) ({ onDownloadHere(entry) }) else null,
                            onOpenEditor = if (entry.type == FileItemType.File) ({ readOnly -> onOpenEditor(entry, readOnly) }) else null,
                            onRename = { renaming = entry },
                            onDelete = { deleting = entry },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        NameDialog(
            title = stringResource(Res.string.sftp_rename),
            confirmLabel = stringResource(Res.string.sftp_rename),
            initial = entry.name,
            onConfirm = { pane.rename(entry, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { entry ->
        ConfirmDeleteDialog(
            entry = entry,
            onConfirm = { pane.delete(entry); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * File list row: leading icon ([mobileFileIcon]) + name (mono) + meta + trailing icon
 * ([mobileFileTrailingIcon]). Tap is [onClick] (enter/transfer); long-press opens the
 * Rename/Delete context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileFileRow(
    entry: FileItem,
    selected: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    onDownloadHere: (() -> Unit)?,
    onOpenEditor: ((Boolean) -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isDir = entry.type == FileItemType.Directory
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Skerry.colors.cyan06 else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym(mobileFileIcon(entry), size = 23.sp, color = if (isDir) Skerry.colors.cyanBright else Skerry.colors.dim)
        Column(Modifier.weight(1f)) {
            Txt(entry.name, color = Skerry.colors.text, size = 14.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            mobileFileMetaText(entry)?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp) }
        }
        Sym(mobileFileTrailingIcon(entry.type), size = 20.sp, color = Skerry.colors.faint)
    }
    if (menuOpen) {
        MobileActionSheet(
            title = entry.name,
            actions = buildList {
                onDownloadHere?.let { dl ->
                    add(MobileSheetAction(stringResource(Res.string.sftp_download_to_device), onClick = dl, icon = "download"))
                }
                onOpenEditor?.let { open ->
                    add(MobileSheetAction(stringResource(Res.string.ftail_fkey_view), onClick = { open(true) }, icon = "visibility"))
                    add(MobileSheetAction(stringResource(Res.string.ftail_fkey_edit), onClick = { open(false) }, icon = "edit_note"))
                }
                add(MobileSheetAction(stringResource(Res.string.sftp_rename), onClick = onRename, icon = "edit"))
                add(MobileSheetAction(stringResource(Res.string.sftp_delete), onClick = onDelete, icon = "delete", danger = true))
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/**
 * Row subtitle: a file shows "size · date" (date omitted when unreported, like the layout's
 * "3.1 KB · Jun 20"); a directory/symlink shows its permissions (`ls -l` style) when the source
 * reports mode bits — the remote SFTP pane does, matching the layout's "drwxr-xr-x".
 */
@Composable
private fun mobileFileMetaText(entry: FileItem): String? {
    val parts = mobileFileRowMeta(entry) ?: return permissionsText(entry.type, entry.permissions)
    val size = sizeText(parts)
    val date = fileDateText(entry.modifiedEpochSeconds, withTime = false)
    return if (date.isEmpty()) size else stringResource(Res.string.sftp_meta_joined, size, date)
}

/** Row that navigates up to the parent directory (".."). */
@Composable
private fun MobileFileUpRow(mono: FontFamily, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym("arrow_upward", size = 23.sp, color = Skerry.colors.faint)
        Txt("..", color = Skerry.colors.dim, size = 14.5.sp, font = mono)
    }
}

/**
 * Mobile layout's transfer card (below the list): direction icon + name + percent + bar.
 * Active shows live progress; Failed shows the error with a close button; Idle renders nothing.
 */
@Composable
private fun MobileTransferCard(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
    when (transfer) {
        TransferState.Idle -> Unit

        is TransferState.Active -> {
            val up = transfer.direction == TransferDirection.Upload
            val fraction = if (transfer.total > 0) transfer.transferred.toFloat() / transfer.total else 0f
            val percent = (fraction * 100).toInt()
            Column(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Sym(if (up) "upload" else "download", size = 17.sp, color = Skerry.colors.cyan)
                    Txt(transfer.name, color = Skerry.colors.textBright, size = 12.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Txt("$percent%", color = Skerry.colors.dim, size = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Skerry.colors.overlayStrong)) {
                    MeterBar(fraction, Skerry.colors.cyan, Modifier.fillMaxWidth())
                }
            }
        }

        is TransferState.Failed -> {
            Row(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.sunset.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Sym("error", size = 17.sp, color = Skerry.colors.sunset)
                Txt(
                    stringResource(
                        Res.string.sftp_transfer_error,
                        transfer.name.ifBlank { stringResource(Res.string.ftail_file_fallback) },
                        transferFailureText(transfer.failure),
                    ),
                    color = Skerry.colors.sunset, size = 11.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBtn("close", onClick = onDismiss, box = 26, icon = 16.sp)
            }
        }
    }
}

// Shared chrome.

/**
 * "Files" title (28sp, as in the layout). Actions (create directory/upload) live in the shared
 * "+" FAB. [onBack] (push-mode SFTP from a host card) adds a back arrow on the left, like the
 * terminal; absent (`null`) in preview.
 */
@Composable
private fun MobileFilesTitle(onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 14.dp else 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            Sym(
                "chevron_left",
                size = 27.sp,
                color = Skerry.colors.cyanBright,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
            )
        }
        MobileScreenTitle(stringResource(Res.string.sftp_files_title))
    }
}

/**
 * Breadcrumb row below the title: host icon (dns) + "label : path" of the active Remote session.
 * When [onGoToPath] is supplied (live mode) the crumb is tappable: it turns into a path input so a
 * known destination can be typed and jumped to (IME "Go" → [onGoToPath], blur → cancel). The editor
 * closes on its own once the pane navigates (the row re-keys on [path]); the mock passes no callback.
 */
@Composable
private fun MobileFilesBreadcrumbRow(
    label: String,
    path: String,
    mono: FontFamily,
    onGoToPath: ((String) -> Unit)? = null,
    // Live mode: the trailing funnel icon toggles the quick-filter row; [filterActive] tints it.
    onToggleFilter: (() -> Unit)? = null,
    filterActive: Boolean = false,
) {
    var editing by remember(path) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("dns", size = 16.sp, color = Skerry.colors.moss)
        if (editing && onGoToPath != null) {
            PathJumpField(
                path = path,
                mono = mono,
                textSize = 12.sp,
                onCommit = { onGoToPath(it); editing = false },
                onCancel = { editing = false },
                modifier = Modifier.weight(1f),
            ) { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Skerry.colors.bg)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) { inner() }
            }
        } else {
            Txt(
                mobileFilesBreadcrumb(label, path),
                color = Skerry.colors.dim,
                size = 12.sp,
                font = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(
                    if (onGoToPath != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { editing = true }
                    } else {
                        Modifier
                    },
                ),
            )
        }
        if (onToggleFilter != null && !editing) {
            Sym(
                "filter_alt",
                size = 18.sp,
                color = if (filterActive) Skerry.colors.cyanBright else Skerry.colors.faint,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleFilter,
                ),
            )
        }
    }
}

/**
 * Quick name filter row under the breadcrumb: live filtering as you type
 * ([FilePaneController.setNameFilter] — substring or `*`/`?` glob). The close icon clears the
 * filter and hides the row. The local text state is re-keyed on the pane's path: navigation
 * clears the controller's filter, so the field follows.
 */
@Composable
private fun MobileFilterRow(pane: FilePaneController, mono: FontFamily, onClose: () -> Unit) {
    var text by remember(pane, pane.path) { mutableStateOf(pane.nameFilter) }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                pane.setNameFilter(it)
            },
            singleLine = true,
            textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = mono),
            cursorBrush = SolidColor(Skerry.colors.cyan),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Skerry.colors.bg)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    if (text.isEmpty()) {
                        Txt(stringResource(Res.string.sftp_filter_hint), color = Skerry.colors.dim, size = 13.sp, font = mono)
                    }
                    inner()
                }
            },
        )
        IconBtn(
            "close",
            onClick = {
                pane.setNameFilter("")
                onClose()
            },
            box = 26,
            icon = 16.sp,
        )
    }
}

/** Round "+" FAB for Files — the shared [MobileFabButton]; when expanded, [open] shows "x" to collapse. */
@Composable
private fun MobileFabButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MobileFabButton(onClick = onClick, modifier = modifier, icon = if (open) "close" else "add", iconSize = 26.sp)
}

/** "+" FAB menu item: pill with icon and label (floats above the button). */
@Composable
private fun MobileFabAction(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(icon, size = 20.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
    }
}

/** Centered notice in the free area below the title (connecting/opening/loading/error). */
@Composable
private fun MobileFilesNoticeBox(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MobileFilesNoticeContent(icon, title, subtitle, color)
    }
}

@Composable
private fun MobileFilesNoticeContent(icon: String, title: String, subtitle: String?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium)
        if (subtitle != null) Txt(subtitle, color = Skerry.colors.faint, size = 12.sp)
    }
}

/** Session manager exists but the active session isn't connected: title + notice. */
@Composable
private fun NoSessionMobileFilesView(mono: FontFamily, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobileFilesTitle(onBack)
        MobileFilesNoticeBox("cloud_off", stringResource(Res.string.sftp_no_session), stringResource(Res.string.sftp_no_session_hint), Skerry.colors.faint)
    }
}

/** Active session is still connecting (tapped SFTP/Connect): title + "Connecting…" with the host subtitle. */
@Composable
private fun ConnectingMobileFilesView(subtitle: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobileFilesTitle(onBack)
        MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_connecting), subtitle, Skerry.colors.cyanBright)
    }
}

// Mock (preview/offscreen).

private data class MockFileEntry(val icon: String, val name: String, val meta: String, val trailing: String, val selected: Boolean = false)

/** Static Remote pane data (preview/offscreen). */
private val MOCK_REMOTE_FILES = listOf(
    MockFileEntry("folder", "html", "drwxr-xr-x · 4 items", "chevron_right"),
    MockFileEntry("folder", "releases", "drwxr-xr-x · 12 items", "chevron_right"),
    MockFileEntry("description", "nginx.conf", "3.1 KB · Jun 20", "ios_share", selected = true),
    MockFileEntry("terminal", "deploy.sh", "1.8 KB · Jun 18", "ios_share"),
    MockFileEntry("description", "robots.txt", "112 B · May 30", "ios_share"),
)

/** Static mock of the Files section (preview/offscreen, no backend). */
@Composable
private fun MockMobileFilesView(mono: FontFamily) {
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle()
            MobileFilesBreadcrumbRow(label = "prod-web-01", path = "/var/www", mono = mono)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
                MOCK_REMOTE_FILES.forEach { MockFileRow(it, mono) }
            }
            MockTransferCard(mono)
            Spacer(Modifier.height(96.dp))
        }
        MobileFabButton(open = false, onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp))
    }
}

@Composable
private fun MockFileRow(entry: MockFileEntry, mono: FontFamily) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (entry.selected) Skerry.colors.cyan06 else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym(entry.icon, size = 23.sp, color = if (entry.icon == "folder") Skerry.colors.cyanBright else Skerry.colors.dim)
        Column(Modifier.weight(1f)) {
            Txt(entry.name, color = Skerry.colors.text, size = 14.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt(entry.meta, color = Skerry.colors.faint, size = 11.sp)
        }
        Sym(entry.trailing, size = 20.sp, color = Skerry.colors.faint)
    }
}

/** Layout's transfer card (static, 64%). */
@Composable
private fun MockTransferCard(mono: FontFamily) {
    Column(
        Modifier
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Sym("upload", size = 17.sp, color = Skerry.colors.cyan)
            Txt("backup.tar.gz", color = Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Txt("64%", color = Skerry.colors.dim, size = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Skerry.colors.overlayStrong)) {
            MeterBar(0.64f, Skerry.colors.cyan, Modifier.fillMaxWidth())
        }
    }
}
