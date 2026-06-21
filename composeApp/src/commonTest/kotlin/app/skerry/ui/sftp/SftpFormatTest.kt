package app.skerry.ui.sftp

import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чистое форматирование SFTP-листинга для живого Remote-пейна: человекочитаемый размер,
 * строка прав в стиле `ls -l`, мета-колонка и выбор иконки по типу объекта.
 */
class SftpFormatTest {

    private fun entry(
        name: String = "f",
        type: SftpEntryType = SftpEntryType.File,
        size: Long = 0,
        permissions: Int = 0,
    ) = SftpEntry(name = name, path = "/$name", type = type, size = size, modifiedEpochSeconds = 0, permissions = permissions)

    @Test
    fun bytes_below_kib_show_raw_bytes() {
        assertEquals("0 B", humanSize(0))
        assertEquals("96 B", humanSize(96))
        assertEquals("1023 B", humanSize(1023))
    }

    @Test
    fun larger_sizes_use_one_decimal_binary_units() {
        assertEquals("1.0 KB", humanSize(1024))
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("1.0 MB", humanSize(1024L * 1024))
        assertEquals("418.0 MB", humanSize(418L * 1024 * 1024))
        assertEquals("2.0 GB", humanSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun rounding_at_a_unit_boundary_carries_into_the_next_unit() {
        // 1048575 B = 1024 МиБ − 1 B: округление дотягивает до 1024.0 KB → показываем 1.0 MB.
        assertEquals("1.0 MB", humanSize(1024L * 1024 - 1))
    }

    @Test
    fun permission_string_renders_type_char_and_rwx_triplets() {
        assertEquals("drwxr-xr-x", sftpPermissionString(SftpEntryType.Directory, 0b111_101_101))
        assertEquals("-rw-r--r--", sftpPermissionString(SftpEntryType.File, 0b110_100_100))
        assertEquals("lrwxrwxrwx", sftpPermissionString(SftpEntryType.Symlink, 0b111_111_111))
    }

    @Test
    fun meta_is_size_for_files_and_permissions_for_directories() {
        assertEquals("2.0 KB", sftpEntryMeta(entry(type = SftpEntryType.File, size = 2048)))
        assertEquals("drwxr-xr-x", sftpEntryMeta(entry(type = SftpEntryType.Directory, permissions = 0b111_101_101)))
    }

    @Test
    fun icon_reflects_entry_type() {
        assertEquals("folder", sftpEntryIcon(SftpEntryType.Directory))
        assertEquals("link", sftpEntryIcon(SftpEntryType.Symlink))
        assertEquals("description", sftpEntryIcon(SftpEntryType.File))
        assertEquals("description", sftpEntryIcon(SftpEntryType.Other))
    }
}
