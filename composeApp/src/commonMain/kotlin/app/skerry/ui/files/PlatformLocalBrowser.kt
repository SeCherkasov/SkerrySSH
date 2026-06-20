package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser

/**
 * Локальный файловый браузер платформы для левой панели двухпанельного SFTP. Desktop отдаёт
 * `LocalFileBrowser` над `FileSystem.SYSTEM` со стартом в домашнем каталоге пользователя; Android —
 * заглушка над корнем общего хранилища (полноценный SAF-доступ к локальной панели появится вместе
 * с мобильным двухпанельным режимом — сейчас он используется только в desktop-оболочке).
 */
expect fun platformLocalBrowser(): FileBrowser
