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

    // Row icon: the type decides for everything that isn't a plain file; a file is read by extension.

    @Test
    fun directories_and_symlinks_ignore_the_extension() {
        assertEquals("folder", sftpFileIcon("releases.tar.gz", FileItemType.Directory))
        assertEquals("link", sftpFileIcon("current.png", FileItemType.Symlink))
    }

    @Test
    fun archives_keys_images_and_scripts_have_their_own_icon() {
        assertEquals("archive", sftpFileIcon("release-0.2.1.tar.gz", FileItemType.File))
        assertEquals("archive", sftpFileIcon("backup.zip", FileItemType.File))
        assertEquals("key", sftpFileIcon("deploy.key", FileItemType.File))
        assertEquals("key", sftpFileIcon("id_ed25519.pub", FileItemType.File))
        assertEquals("image", sftpFileIcon("og.png", FileItemType.File))
        assertEquals("terminal", sftpFileIcon("deploy.sh", FileItemType.File))
    }

    @Test
    fun extension_case_does_not_matter() {
        assertEquals("image", sftpFileIcon("SCREEN.PNG", FileItemType.File))
        assertEquals("archive", sftpFileIcon("Backup.TGZ", FileItemType.File))
    }

    // Transfer queue: percentage and speed of a running transfer.

    @Test
    fun percentage_needs_a_known_total() {
        assertEquals(63, transferPercent(transferred = 7_100_000, total = 11_200_000))
        assertEquals(0, transferPercent(transferred = 0, total = 1_000))
        assertEquals(100, transferPercent(transferred = 1_000, total = 1_000))
        // Unknown size (the source didn't report one): no percentage at all rather than a fake 0%.
        assertNull(transferPercent(transferred = 512, total = 0))
    }

    @Test
    fun percentage_never_leaves_the_scale() {
        // A source that reports more bytes than it promised must not render "104%".
        assertEquals(100, transferPercent(transferred = 1_040, total = 1_000))
        assertEquals(0, transferPercent(transferred = -10, total = 1_000))
    }

    @Test
    fun speed_is_the_transferred_bytes_over_the_elapsed_time() {
        assertEquals(5_000_000, transferSpeed(bytesDone = 10_000_000, elapsedMillis = 2_000))
        assertEquals(1_000, transferSpeed(bytesDone = 500, elapsedMillis = 500))
    }

    @Test
    fun speed_is_unknown_until_the_transfer_has_run_long_enough() {
        // The first frames divide a handful of bytes by a few milliseconds — a number that jumps
        // between "2 GB/s" and "3 KB/s" is worse than no number.
        assertNull(transferSpeed(bytesDone = 4_096, elapsedMillis = 40))
        assertNull(transferSpeed(bytesDone = 0, elapsedMillis = 5_000))
        assertNull(transferSpeed(bytesDone = 4_096, elapsedMillis = 0))
        // The floor itself is enough to divide by; one millisecond below it is not.
        assertEquals(16_384, transferSpeed(bytesDone = 4_096, elapsedMillis = 250))
        assertNull(transferSpeed(bytesDone = 4_096, elapsedMillis = 249))
    }

    @Test
    fun anything_else_is_a_plain_document() {
        assertEquals("description", sftpFileIcon("nginx.conf", FileItemType.File))
        assertEquals("description", sftpFileIcon("LICENSE", FileItemType.File))
        // A leading dot is the name, not an extension: ".env" must not read as an "env" type.
        assertEquals("description", sftpFileIcon(".env", FileItemType.File))
        assertEquals("description", sftpFileIcon("socket", FileItemType.Other))
    }
}
