package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.LocalFileBrowser
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

/** Desktop: real local filesystem via okio, starting in the user's home directory. */
actual fun platformLocalBrowser(): FileBrowser =
    LocalFileBrowser(
        fileSystem = FileSystem.SYSTEM,
        home = System.getProperty("user.home") ?: "/",
        label = "This computer",
        ioDispatcher = Dispatchers.IO,
    )
