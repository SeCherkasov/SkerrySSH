package app.skerry.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.fileBrowserFailureText
import app.skerry.ui.files.transferFailureText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_file_fallback
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_view
import app.skerry.ui.generated.resources.sftp_delete
import app.skerry.ui.generated.resources.sftp_download_to_device
import app.skerry.ui.generated.resources.sftp_error
import app.skerry.ui.generated.resources.sftp_loading
import app.skerry.ui.generated.resources.sftp_meta_joined
import app.skerry.ui.generated.resources.sftp_rename
import app.skerry.ui.generated.resources.sftp_transfer_error
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.fileDateText
import app.skerry.ui.sftp.permissionsText
import app.skerry.ui.sftp.sizeText
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.sftp.ConfirmDeleteDialog
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.MeterBar
import app.skerry.ui.sftp.NameDialog
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Live pane (Remote or Local) over [FilePaneController]: listing + ".." up row + rename/delete
 * context menu (long-press). [onTransfer] is the file transfer action (the file row's `ios_share`
 * visible action).
 */
@Composable
internal fun MobileLivePane(
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
internal fun MobileTransferCard(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
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
