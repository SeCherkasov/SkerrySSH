package app.skerry.ui.sftp

import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.LocalFileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure size decomposition for the SFTP listing. The visible string comes from a localized template
 * ([sizeText]), so the unit test covers the unit/digits split only.
 */
class SftpFormatTest {

    // POSIX permissions column, ls -l style.

    @Test
    fun permissions_render_in_ls_long_format() {
        assertEquals("drwxr-xr-x", permissionsText(FileItemType.Directory, 0b111_101_101))
        assertEquals("-rw-r--r--", permissionsText(FileItemType.File, 0b110_100_100))
        assertEquals("lrwxrwxrwx", permissionsText(FileItemType.Symlink, 0b111_111_111))
        assertEquals("----------", permissionsText(FileItemType.Other, 0))
    }

    @Test
    fun setuid_setgid_and_sticky_bits_take_the_execute_slots() {
        assertEquals("-rwsr-xr-x", permissionsText(FileItemType.File, 0x800 or 0b111_101_101))
        assertEquals("-rwSr--r--", permissionsText(FileItemType.File, 0x800 or 0b110_100_100))
        assertEquals("-rwxr-sr-x", permissionsText(FileItemType.File, 0x400 or 0b111_101_101))
        assertEquals("drwxrwxrwt", permissionsText(FileItemType.Directory, 0x200 or 0b111_111_111))
        assertEquals("drwxrwxrwT", permissionsText(FileItemType.Directory, 0x200 or 0b111_111_110))
    }

    @Test
    fun unknown_permissions_render_as_empty() {
        assertNull(permissionsText(FileItemType.File, null))
    }

    // Modified-date column decomposition. The visible string comes from localized templates
    // (month names + order), so the unit test covers epoch handling and the recent/old split.

    @Test
    fun zero_mtime_means_unreported_and_renders_nothing() {
        assertNull(fileDateParts(0, now = LocalFileTime(2026, 7, 24, 12, 0), at = { error("not called") }))
    }

    @Test
    fun same_year_shows_day_and_time() {
        val parts = fileDateParts(
            1_000,
            now = LocalFileTime(2026, 7, 24, 12, 0),
            at = { LocalFileTime(2026, 7, 12, 9, 5) },
        )
        assertEquals(FileDateParts.Recent(month = 7, day = 12, time = "09:05"), parts)
    }

    @Test
    fun another_year_shows_the_year_instead_of_time() {
        val parts = fileDateParts(
            1_000,
            now = LocalFileTime(2026, 7, 24, 12, 0),
            at = { LocalFileTime(2024, 11, 3, 23, 59) },
        )
        assertEquals(FileDateParts.Old(month = 11, day = 3, year = 2024), parts)
    }

    @Test
    fun bytes_below_kib_stay_in_raw_bytes() {
        assertEquals(SizeParts(SizeUnit.Bytes, 0), sizeParts(0))
        assertEquals(SizeParts(SizeUnit.Bytes, 96), sizeParts(96))
        assertEquals(SizeParts(SizeUnit.Bytes, 1023), sizeParts(1023))
    }

    @Test
    fun larger_sizes_use_one_decimal_binary_units() {
        assertEquals(SizeParts(SizeUnit.KB, 1, 0), sizeParts(1024))
        assertEquals(SizeParts(SizeUnit.KB, 1, 5), sizeParts(1536))
        assertEquals(SizeParts(SizeUnit.MB, 1, 0), sizeParts(1024L * 1024))
        assertEquals(SizeParts(SizeUnit.MB, 418, 0), sizeParts(418L * 1024 * 1024))
        assertEquals(SizeParts(SizeUnit.GB, 2, 0), sizeParts(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun rounding_at_a_unit_boundary_carries_into_the_next_unit() {
        // 1048575 B = 1024 KiB - 1 B: rounding pushes it to 1024.0 KB, so we show 1.0 MB.
        assertEquals(SizeParts(SizeUnit.MB, 1, 0), sizeParts(1024L * 1024 - 1))
    }
}
