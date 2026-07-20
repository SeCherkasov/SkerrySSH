package app.skerry.ui.mobile

import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.sftp.SizeParts
import app.skerry.ui.sftp.sizeParts

/**
 * Mode of the mobile Files screen (single-pane):
 * - [Preview] — no session manager (offscreen render/preview without a backend) → static mock;
 * - [Live] — an active connected session → live listing over [app.skerry.ui.files.TransferCoordinator];
 * - [Connecting] — the active session is still connecting (after tapping SFTP/Connect) → shows
 *   "Connecting…" instead of flashing "No active session" during the handshake/auth;
 * - [NoSession] — a session manager exists but there's no active session (or it's in a form/error state).
 */
enum class MobileFilesMode { Preview, NoSession, Connecting, Live }

/**
 * Picks the Files screen mode from session manager presence and active session state. [connecting]
 * has lower priority than [connected] so a session that finished connecting goes straight to Live.
 */
fun mobileFilesMode(hasSessions: Boolean, connected: Boolean, connecting: Boolean): MobileFilesMode = when {
    !hasSessions -> MobileFilesMode.Preview
    connected -> MobileFilesMode.Live
    connecting -> MobileFilesMode.Connecting
    else -> MobileFilesMode.NoSession
}

/**
 * Row subtitle, a direct projection of [FileItem]: size parts for a file, `null` for a directory
 * (the model doesn't carry permissions/item count, unlike the empty directory subtitle in desktop
 * `SftpView`). The view renders it through the localized template ([app.skerry.ui.sftp.sizeText]).
 */
fun mobileFileRowMeta(item: FileItem): SizeParts? =
    if (item.type == FileItemType.File) sizeParts(item.size) else null

/**
 * Leading row icon ([Sym] ligature): directory → `folder`, symlink → `link`, shell script →
 * `terminal`, other file → `description`.
 */
fun mobileFileIcon(item: FileItem): String = when (item.type) {
    FileItemType.Directory -> "folder"
    FileItemType.Symlink -> "link"
    FileItemType.File, FileItemType.Other -> if (item.name.endsWith(".sh")) "terminal" else "description"
}

/**
 * Trailing row icon: directory → `chevron_right` (enter), file → `ios_share` (transfer: download
 * from the remote pane / upload from the local pane).
 */
fun mobileFileTrailingIcon(type: FileItemType): String =
    if (type == FileItemType.Directory) "chevron_right" else "ios_share"

/** Breadcrumb row: source label + current path ("prod-web-01 : /var/www"). */
fun mobileFilesBreadcrumb(label: String, path: String): String = "$label : $path"
