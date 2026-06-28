package app.skerry.ui.design

import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Чистая логика мобильного экрана Files: режим экрана, мета/иконки строк, путь-крошка. */
class MobileFilesTest {

    private fun item(name: String, type: FileItemType, size: Long = 0): FileItem =
        FileItem(name = name, path = "/var/www/$name", type = type, size = size, modifiedEpochSeconds = 0)

    // Режим экрана по состоянию сессий

    @Test
    fun files_mode_picks_preview_when_no_session_manager() {
        // Путь превью/офскрин без бэкенда.
        assertEquals(MobileFilesMode.Preview, mobileFilesMode(hasSessions = false, connected = false, connecting = false))
        assertEquals(MobileFilesMode.Preview, mobileFilesMode(hasSessions = false, connected = true, connecting = false))
    }

    @Test
    fun files_mode_is_live_only_when_connected() {
        assertEquals(MobileFilesMode.Live, mobileFilesMode(hasSessions = true, connected = true, connecting = false))
    }

    @Test
    fun files_mode_shows_connecting_while_session_opens() {
        // Открытая сессия ещё подключается — показываем «Connecting…», а не мигаем «No active session».
        assertEquals(
            MobileFilesMode.Connecting,
            mobileFilesMode(hasSessions = true, connected = false, connecting = true),
        )
    }

    @Test
    fun files_mode_is_no_session_when_inactive_or_failed() {
        // Нет активной сессии, либо она в форме/ошибке (ни connected, ни connecting) — листать нечего.
        assertEquals(MobileFilesMode.NoSession, mobileFilesMode(hasSessions = true, connected = false, connecting = false))
    }

    // Мета-подпись строки (честная проекция на FileItem)

    @Test
    fun row_meta_shows_human_size_for_files_and_empty_for_dirs() {
        assertEquals("3.0 KB", mobileFileRowMeta(item("nginx.conf", FileItemType.File, size = 3072)))
        assertEquals("112 B", mobileFileRowMeta(item("robots.txt", FileItemType.File, size = 112)))
        // У каталога в модели нет ни прав, ни числа элементов — мета пустая (без выдумок).
        assertEquals("", mobileFileRowMeta(item("html", FileItemType.Directory)))
    }

    // Ведущая иконка строки

    @Test
    fun leading_icon_maps_by_type_and_script_extension() {
        assertEquals("folder", mobileFileIcon(item("html", FileItemType.Directory)))
        assertEquals("link", mobileFileIcon(item("current", FileItemType.Symlink)))
        assertEquals("description", mobileFileIcon(item("nginx.conf", FileItemType.File)))
        // Шелл-скрипт — иконка terminal.
        assertEquals("terminal", mobileFileIcon(item("deploy.sh", FileItemType.File)))
    }

    // Завершающая иконка строки (видимое действие макета)

    @Test
    fun trailing_icon_is_chevron_for_dirs_and_share_for_files() {
        assertEquals("chevron_right", mobileFileTrailingIcon(FileItemType.Directory))
        assertEquals("ios_share", mobileFileTrailingIcon(FileItemType.File))
        assertEquals("ios_share", mobileFileTrailingIcon(FileItemType.Symlink))
    }

    // Строка пути (крошка под переключателем)

    @Test
    fun breadcrumb_joins_label_and_path() {
        assertEquals("prod-web-01 : /var/www", mobileFilesBreadcrumb("prod-web-01", "/var/www"))
        assertEquals("This Mac : /", mobileFilesBreadcrumb("This Mac", "/"))
    }
}
