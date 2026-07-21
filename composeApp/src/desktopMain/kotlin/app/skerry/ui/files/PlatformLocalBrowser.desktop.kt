package app.skerry.ui.files

import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.LocalFileBrowser
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

/**
 * Desktop: real local filesystem via okio, starting in the user's home directory. The label is left
 * blank on purpose — the UI substitutes the localized `ftail_local_label` (parity with Android).
 */
actual fun platformLocalBrowser(): FileContentBrowser =
    LocalFileBrowser(
        fileSystem = FileSystem.SYSTEM,
        home = System.getProperty("user.home") ?: "/",
        label = "",
        ioDispatcher = Dispatchers.IO,
    )
