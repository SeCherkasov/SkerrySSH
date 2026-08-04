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
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_already_exists
import app.skerry.ui.generated.resources.sftp_cancel
import app.skerry.ui.generated.resources.sftp_copy
import app.skerry.ui.generated.resources.sftp_copy_to_q
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
import app.skerry.ui.generated.resources.sftp_move_to_q
import app.skerry.ui.generated.resources.sftp_transfer_body
import app.skerry.ui.generated.resources.sftp_what_single
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

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
            Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
            // Border in decorationBox so a click anywhere in the field places the caret.
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = mono),
                cursorBrush = SolidColor(Skerry.colors.cyan),
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
                CancelButton(stringResource(Res.string.sftp_cancel), onClick = onDismiss)
                PrimaryButton(
                    confirmLabel,
                    onClick = submit,
                    bg = if (ok) Skerry.colors.cyan else Skerry.colors.whiteFaint,
                )
            }
    }
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
internal fun ConfirmCopyDialog(
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
        confirmBg = Skerry.colors.cyan,
        confirmFg = Skerry.colors.ink,
    )
}

/**
 * Confirmation for moving a batch of [items] into directory [destPath] of pane [destLabel] (F6). Moving
 * between filesystems = copy + delete the source, so confirm explicitly.
 */
@Composable
internal fun ConfirmMoveDialog(
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
                if (isDir) stringResource(Res.string.sftp_delete_folder_body, entry.name)
                else stringResource(Res.string.sftp_delete_file_body, entry.name),
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
