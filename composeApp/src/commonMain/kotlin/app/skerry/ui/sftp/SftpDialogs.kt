package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.fileDisplayName
import app.skerry.ui.files.fileDisplayPath
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_already_exists
import app.skerry.ui.generated.resources.sftp_cancel
import app.skerry.ui.generated.resources.sftp_copy
import app.skerry.ui.generated.resources.sftp_copy_to_local_q
import app.skerry.ui.generated.resources.sftp_copy_to_remote_q
import app.skerry.ui.generated.resources.sftp_delete
import app.skerry.ui.generated.resources.sftp_delete_file_body
import app.skerry.ui.generated.resources.sftp_delete_file_q
import app.skerry.ui.generated.resources.sftp_delete_folder_body
import app.skerry.ui.generated.resources.sftp_delete_folder_q
import app.skerry.ui.generated.resources.sftp_delete_items_body
import app.skerry.ui.generated.resources.sftp_delete_items_dirs_body
import app.skerry.ui.generated.resources.sftp_delete_items_q
import app.skerry.ui.generated.resources.sftp_items_count
import app.skerry.ui.generated.resources.sftp_move
import app.skerry.ui.generated.resources.sftp_move_to_local_q
import app.skerry.ui.generated.resources.sftp_move_to_remote_q
import app.skerry.ui.generated.resources.sftp_overwrite
import app.skerry.ui.generated.resources.sftp_overwrite_many
import app.skerry.ui.generated.resources.sftp_overwrite_one
import app.skerry.ui.generated.resources.sftp_overwrite_q
import app.skerry.ui.generated.resources.sftp_transfer_body
import app.skerry.ui.generated.resources.sftp_what_single
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.fieldName

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
    // [initial] is the raw name on purpose, unlike every drawn one: this field is edited and
    // submitted, so it has to hold the name the server actually has (see [fileDisplayName]).
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Rename opens on the current name, autofocused: select all of it — extension included, since
    // renaming report.log to notes.txt replaces both halves. "New folder" opens empty.
    val draft = rememberFieldDraft(name, selectAllOnFocus = name == initial)
    SftpDialogFrame(onDismiss = onDismiss) {
            Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
            // Border in decorationBox so a click anywhere in the field places the caret.
            BasicTextField(
                value = draft.textFieldValue(name),
                onValueChange = { draft.accept(it, name) { name = it } },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = mono),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                // Enter confirms (if the name is valid), Esc closes — handler before the focusable field.
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .fieldFocus(draft)
                    // The dialog's title is the field's caption; the box has none of its own.
                    .fieldName(fallback = title)
                    .testTag(UiTags.FORM_FIELD)
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
                            .background(Skerry.colors.panel)
                            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(7.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) { inner() }
                },
            )
            if (conflict) Txt(stringResource(Res.string.sftp_already_exists, trimmed), color = Skerry.colors.sunset, size = 11.5.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CancelButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
                PrimaryButton(
                    confirmLabel,
                    onClick = submit,
                    bg = if (ok) Skerry.colors.cyan else Skerry.colors.whiteFaint,
                    modifier = Modifier.testTag(UiTags.FORM_SAVE),
                )
            }
    }
}

/**
 * Confirmation for a transfer that found same-named objects at the destination ([names] are the
 * clashing entries, chosen by whichever side wrote them). Shared by both shells: the coordinator
 * behind it is shared, and so is the answer the user gives it.
 */
@Composable
internal fun ConfirmOverwriteDialog(names: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val single = names.singleOrNull()
    ConfirmDangerDialog(
        title = stringResource(Res.string.sftp_overwrite_q),
        // The name is the other side's text and this dialog decides which file stops existing, so
        // it is drawn the way the listing row is (see [untrustedLabel]).
        body = if (single != null) stringResource(Res.string.sftp_overwrite_one, fileDisplayName(single))
        else stringResource(Res.string.sftp_overwrite_many, names.size),
        confirmLabel = stringResource(Res.string.sftp_overwrite),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Confirmation for deleting a batch of [items] (F8 on the active pane): a single item — its name,
 * several — a count. The text warns about recursion if the batch contains a directory.
 */
@Composable
internal fun ConfirmDeleteItemsDialog(items: List<FileItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val single = items.singleOrNull()
    val hasDir = items.any { it.type == FileItemType.Directory }
    val title = when {
        single != null && single.type == FileItemType.Directory -> stringResource(Res.string.sftp_delete_folder_q)
        single != null -> stringResource(Res.string.sftp_delete_file_q)
        else -> stringResource(Res.string.sftp_delete_items_q, items.size)
    }
    val body = when {
        single != null && single.type == FileItemType.Directory ->
            stringResource(Res.string.sftp_delete_folder_body, fileDisplayName(single.name))
        single != null -> stringResource(Res.string.sftp_delete_file_body, fileDisplayName(single.name))
        hasDir -> stringResource(Res.string.sftp_delete_items_dirs_body, items.size)
        else -> stringResource(Res.string.sftp_delete_items_body, items.size)
    }
    ConfirmDangerDialog(title, body, stringResource(Res.string.sftp_delete), onConfirm, onDismiss)
}

/**
 * Confirmation for copying a batch of [items] into directory [destPath] on the server, or on this
 * computer when not [toRemote] (F5).
 */
@Composable
internal fun ConfirmCopyDialog(
    items: List<FileItem>,
    toRemote: Boolean,
    destPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = items.singleOrNull()
    val what = if (single != null) stringResource(Res.string.sftp_what_single, fileDisplayName(single.name)) else stringResource(Res.string.sftp_items_count, items.size)
    ConfirmDangerDialog(
        title = stringResource(if (toRemote) Res.string.sftp_copy_to_remote_q else Res.string.sftp_copy_to_local_q),
        body = stringResource(Res.string.sftp_transfer_body, what, destPath),
        confirmLabel = stringResource(Res.string.sftp_copy),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmBg = Skerry.colors.cyan,
        confirmFg = Skerry.colors.ink,
    )
}

/** F5 Copy / F6 Move of [pane]'s operands into the opposite pane, awaiting confirmation. */
internal data class PaneTransferRequest(
    val pane: FilePaneController,
    val move: Boolean,
    /** Puts back what a drop changed in the pane's marks, when the question is not carried out. */
    val onAbandon: () -> Unit = {},
)

/**
 * The question a drop from this pane raises, or null when there is nothing left to ask about. An
 * unmarked row carries only itself: it is marked for the question, and the earlier marks come back
 * if the question is dismissed.
 */
internal fun FilePaneController.transferRequestFor(dropped: FileDrop): PaneTransferRequest? {
    val before = selectionSnapshot()
    if (dropped.items.any { it.path !in selection }) {
        dropped.items.singleOrNull()?.let(::selectOnly)
        // The row left the listing mid-drag: the question would fall back to the cursored row.
        if (selectedItems().isEmpty()) {
            restoreSelection(before)
            return null
        }
    }
    return PaneTransferRequest(this, dropped.move) { restoreSelection(before) }
}

/**
 * Confirms [request] and hands it to [coord]. The rows are the source pane's operands() at display
 * time; if they emptied (a background refresh between the press and the frame) or the coordinator is
 * gone, the request closes via an effect rather than by writing state in composition.
 */
@Composable
internal fun PaneTransferConfirmation(coord: TransferCoordinator?, request: PaneTransferRequest, onClose: () -> Unit) {
    val items = request.pane.operands()
    val abandon = { request.onAbandon(); onClose() }
    if (coord == null || items.isEmpty()) {
        LaunchedEffect(request) { abandon() }
        return
    }
    val fromLocal = request.pane === coord.local
    val destPath = fileDisplayPath(if (fromLocal) coord.remote.path else coord.local.path)
    if (request.move) {
        ConfirmMoveDialog(
            items = items,
            toRemote = fromLocal,
            destPath = destPath,
            onConfirm = { coord.moveSelection(fromLocal); onClose() },
            onDismiss = abandon,
        )
    } else {
        ConfirmCopyDialog(
            items = items,
            toRemote = fromLocal,
            destPath = destPath,
            onConfirm = {
                if (fromLocal) coord.uploadSelection() else coord.downloadSelection()
                onClose()
            },
            onDismiss = abandon,
        )
    }
}

/**
 * Confirmation for moving a batch of [items] into directory [destPath] on the server or this computer (F6). Moving
 * between filesystems = copy + delete the source, so confirm explicitly.
 */
@Composable
internal fun ConfirmMoveDialog(
    items: List<FileItem>,
    toRemote: Boolean,
    destPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = items.singleOrNull()
    val what = if (single != null) stringResource(Res.string.sftp_what_single, fileDisplayName(single.name)) else stringResource(Res.string.sftp_items_count, items.size)
    ConfirmDangerDialog(
        title = stringResource(if (toRemote) Res.string.sftp_move_to_remote_q else Res.string.sftp_move_to_local_q),
        body = stringResource(Res.string.sftp_transfer_body, what, destPath),
        confirmLabel = stringResource(Res.string.sftp_move),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmBg = Skerry.colors.cyan,
        confirmFg = Skerry.colors.ink,
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
    confirmBg: Color = Skerry.colors.sunset,
    confirmFg: Color = Skerry.colors.ink,
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
            Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
            Txt(body, color = Skerry.colors.faint, size = 12.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButtonFocus(focused = !focusConfirm) { CancelButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss) }
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
    // App-wide dismiss policy (see [app.skerry.ui.design.ModalScrim]): a stray click outside must
    // not discard a half-typed name — only Esc/Back or an explicit control closes a dialog.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surface)
                .border(1.dp, Skerry.colors.line, RoundedCornerShape(12.dp))
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
            .then(if (focused) Modifier.border(1.5.dp, Skerry.colors.cyanBright, RoundedCornerShape(9.dp)) else Modifier)
            .padding(1.5.dp),
    ) { content() }
}

/** File/directory deletion confirmation. */
@Composable
internal fun ConfirmDeleteDialog(entry: FileItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val isDir = entry.type == FileItemType.Directory
    SftpDialogFrame(onDismiss = onDismiss) {
            Txt(if (isDir) stringResource(Res.string.sftp_delete_folder_q) else stringResource(Res.string.sftp_delete_file_q), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
            Txt(
                if (isDir) stringResource(Res.string.sftp_delete_folder_body, fileDisplayName(entry.name))
                else stringResource(Res.string.sftp_delete_file_body, fileDisplayName(entry.name)),
                color = Skerry.colors.faint,
                size = 12.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CancelButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss)
                PrimaryButton(stringResource(Res.string.sftp_delete), onClick = onConfirm, bg = Skerry.colors.sunset, fg = Skerry.colors.ink)
            }
    }
}

// Shared and mock path.
