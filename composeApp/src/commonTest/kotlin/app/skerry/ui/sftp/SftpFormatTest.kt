package app.skerry.ui.sftp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure size decomposition for the SFTP listing. The visible string comes from a localized template
 * ([sizeText]), so the unit test covers the unit/digits split only.
 */
class SftpFormatTest {

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
