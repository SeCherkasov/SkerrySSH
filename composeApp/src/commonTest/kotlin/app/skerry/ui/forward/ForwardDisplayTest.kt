package app.skerry.ui.forward

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure throughput decomposition behind the rate labels and meters. */
class ForwardDisplayTest {

    @Test
    fun `rate parts scale bytes per second across units`() {
        assertEquals(RateParts(RateUnit.Bytes, 512), rateParts(512))
        assertEquals(RateParts(RateUnit.KB, 42), rateParts(42L * 1024))
        assertEquals(RateParts(RateUnit.MB, 1, 1), rateParts(1_200_000)) // ~1.14 MiB/s truncates to 1.1
        assertEquals(RateParts(RateUnit.Bytes, 0), rateParts(0))
    }

    @Test
    fun `rate fraction saturates at one mebibyte per second`() {
        assertEquals(0f, rateFraction(0))
        assertEquals(1f, rateFraction(1024L * 1024))
        assertEquals(1f, rateFraction(5L * 1024 * 1024)) // saturation
    }
}
