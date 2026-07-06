package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.LocalFileBrowser
import app.skerry.ui.sftp.SafBridge
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

/**
 * Android: local Files panel rooted at the app's private storage. Under scoped storage, direct access
 * to `/storage/emulated/0` requires `MANAGE_EXTERNAL_STORAGE`, so the root is the app-specific external
 * files dir (`Android/data/app.skerry/files`), readable/writable without permissions and persisted
 * across restarts. "Download to device" writes here so it's immediately visible in Local; downloading
 * outside the sandbox uses "Save to…" ([pickDownloadTarget] → [TransferCoordinator.downloadToTarget]).
 *
 * Context comes from [SafBridge] (set by the Activity in `onCreate`); falls back to the external
 * storage root before that. `getExternalFilesDir` creates the directory on first access.
 */
actual fun platformLocalBrowser(): FileBrowser {
    val ctx = SafBridge.context()
    val home = ctx?.getExternalFilesDir(null)?.absolutePath
        ?: ctx?.filesDir?.absolutePath
        ?: "/storage/emulated/0"
    return LocalFileBrowser(
        fileSystem = FileSystem.SYSTEM,
        home = home,
        label = "На устройстве",
        ioDispatcher = Dispatchers.IO,
    )
}
