package app.skerry.shared.files

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Epoch-seconds → local calendar decomposition for the file panel's modified column. The zone is
 * pinned explicitly so the assertions don't depend on the machine running the tests.
 */
class FileTimeTest {

    @Test
    fun epoch_zero_in_utc_is_the_epoch_start() {
        assertEquals(LocalFileTime(1970, 1, 1, 0, 0), localFileTime(0, zoneId = "UTC"))
    }

    @Test
    fun seconds_are_truncated_to_the_minute() {
        // 1 day + 61 s → 1970-01-02 00:01 (:01 seconds dropped).
        assertEquals(LocalFileTime(1970, 1, 2, 0, 1), localFileTime(86_461, zoneId = "UTC"))
    }

    @Test
    fun the_zone_shifts_the_local_time() {
        assertEquals(LocalFileTime(1970, 1, 1, 3, 0), localFileTime(0, zoneId = "GMT+3"))
    }
}
