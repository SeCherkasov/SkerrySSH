package app.skerry.ui.sftp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure formatting for the SFTP listing: human-readable file size. */
class SftpFormatTest {

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
        // 1048575 B = 1024 MiB - 1 B: rounding pushes it to 1024.0 KB, so we show 1.0 MB.
        assertEquals("1.0 MB", humanSize(1024L * 1024 - 1))
    }
}
