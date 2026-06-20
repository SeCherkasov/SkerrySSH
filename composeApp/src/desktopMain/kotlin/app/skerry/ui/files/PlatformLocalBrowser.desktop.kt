package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.LocalFileBrowser
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

/** Desktop: реальная локальная ФС через okio со стартом в домашнем каталоге пользователя. */
actual fun platformLocalBrowser(): FileBrowser =
    LocalFileBrowser(
        fileSystem = FileSystem.SYSTEM,
        home = System.getProperty("user.home") ?: "/",
        label = "Этот компьютер",
        ioDispatcher = Dispatchers.IO,
    )
