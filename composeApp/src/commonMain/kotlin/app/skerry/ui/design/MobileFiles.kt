package app.skerry.ui.design

import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.sftp.humanSize

/**
 * Режим мобильного экрана Files (`docs/new/Skerry Mobile.html`, секция FILES). Зеркалит ветвление
 * desktop-`SftpView`, но для одной панели за раз (телефонный single-pane с переключателем Remote/Local):
 * - [Preview] — нет менеджера сессий (офскрин-рендер/превью без бэкенда) → статичный мок макета;
 * - [Live] — есть активная подключённая сессия → живой листинг поверх [app.skerry.ui.files.TransferCoordinator];
 * - [NoSession] — менеджер есть, но активная сессия не подключена → уведомление «нет сессии».
 */
enum class MobileFilesMode { Preview, NoSession, Live }

/** Выбрать режим экрана Files по наличию менеджера сессий и факту подключения активной сессии. */
fun mobileFilesMode(hasSessions: Boolean, connected: Boolean): MobileFilesMode = when {
    !hasSessions -> MobileFilesMode.Preview
    connected -> MobileFilesMode.Live
    else -> MobileFilesMode.NoSession
}

/**
 * Мета-подпись строки списка — честная проекция на [FileItem]: для файла человекочитаемый размер,
 * для каталога пусто. В макете у каталога стоят права + число элементов, но модель их не несёт —
 * не выдумываем (как пустая мета каталога в desktop-`SftpView`).
 */
fun mobileFileRowMeta(item: FileItem): String =
    if (item.type == FileItemType.File) humanSize(item.size) else ""

/**
 * Ведущая иконка строки (лигатура [Sym]): каталог → `folder`, симлинк → `link`, шелл-скрипт → `terminal`
 * (как `deploy.sh` в макете), прочий файл → `description`.
 */
fun mobileFileIcon(item: FileItem): String = when (item.type) {
    FileItemType.Directory -> "folder"
    FileItemType.Symlink -> "link"
    FileItemType.File, FileItemType.Other -> if (item.name.endsWith(".sh")) "terminal" else "description"
}

/**
 * Завершающая иконка строки = видимое действие макета: у каталога `chevron_right` (войти), у файла
 * `ios_share` (передать — скачать с remote-панели / залить с local-панели).
 */
fun mobileFileTrailingIcon(type: FileItemType): String =
    if (type == FileItemType.Directory) "chevron_right" else "ios_share"

/** Строка-крошка под переключателем: метка источника + текущий путь («prod-web-01 : /var/www»). */
fun mobileFilesBreadcrumb(label: String, path: String): String = "$label : $path"
