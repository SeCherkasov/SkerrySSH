package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.LocalFileBrowser
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

/**
 * Android: заглушка локального браузера над общим хранилищем. Двухпанельный режим на телефоне пока
 * не разводится (узкий экран — однопанельная вкладка Files с SAF-пикером), поэтому полноценный
 * SAF-доступ к локальной панели появится вместе с мобильным Total-Commander-режимом. Стартовый
 * каталог — корень внешнего хранилища; чтения за пределами выданных прав вернут [FileBrowserException].
 */
actual fun platformLocalBrowser(): FileBrowser =
    LocalFileBrowser(
        fileSystem = FileSystem.SYSTEM,
        home = "/storage/emulated/0",
        label = "Устройство",
        ioDispatcher = Dispatchers.IO,
    )
