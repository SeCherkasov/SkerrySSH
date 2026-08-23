package app.skerry.shared.rdp

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The display-scaling fields MS-RDPBCGR and MS-RDPEDISP carry, derived from the local display. */
class RdpDisplayScaleTest {

    @Test
    fun `an unscaled display says nothing at all`() {
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(1920, 1080, 1f))
    }

    @Test
    fun `a 150 percent laptop reports the factors and a size the server accepts`() {
        // 2880x1800 physical pixels at 150% — the desktop the user actually wants is 1920x1200
        // logical pixels' worth of room, drawn into every physical pixel there is.
        val scale = RdpDisplayScale.of(2880, 1800, 1.5f)

        assertEquals(150, scale.desktopScaleFactor)
        assertEquals(RdpDisplayScale.DEVICE_140, scale.deviceScaleFactor)
        // 2880 px at 144 dpi is 20 inches across, 1800 px is 12.5 inches down.
        assertEquals(508, scale.physicalWidthMm)
        assertEquals(318, scale.physicalHeightMm)
    }

    @Test
    fun `a scale that would leave a desktop too small for a window is capped`() {
        // A phone reports a density near 3. Taken at face value it would ask Windows for a desktop
        // 780x360 logical pixels across, which no dialog fits into.
        val scale = RdpDisplayScale.of(2340, 1080, 3f)

        assertTrue(scale.desktopScaleFactor <= 169, "the phone's density went out unclamped: ${scale.desktopScaleFactor}")
        assertTrue(
            1080 / (scale.desktopScaleFactor / 100.0) >= RdpDisplayScale.MIN_LOGICAL_HEIGHT,
            "the remote desktop would be shorter than a window",
        )
    }

    @Test
    fun `the factors never leave the range the protocol allows`() {
        val huge = RdpDisplayScale.of(7680, 4320, 12f)

        assertTrue(huge.desktopScaleFactor in RdpDisplayScale.MIN_FACTOR..RdpDisplayScale.MAX_FACTOR)
        assertTrue(
            huge.deviceScaleFactor in setOf(
                RdpDisplayScale.DEVICE_100,
                RdpDisplayScale.DEVICE_140,
                RdpDisplayScale.DEVICE_180,
            ),
        )
        assertTrue(huge.physicalWidthMm in RdpDisplayScale.MIN_PHYSICAL_MM..RdpDisplayScale.MAX_PHYSICAL_MM)
    }

    @Test
    fun `a device scale factor is one of the three values or the whole layout is discarded`() {
        assertEquals(RdpDisplayScale.DEVICE_100, RdpDisplayScale.of(1920, 1080, 1.1f).deviceScaleFactor)
        assertEquals(RdpDisplayScale.DEVICE_140, RdpDisplayScale.of(2560, 1440, 1.25f).deviceScaleFactor)
        assertEquals(RdpDisplayScale.DEVICE_180, RdpDisplayScale.of(3840, 2160, 2f).deviceScaleFactor)
    }

    @Test
    fun `the millimetres are derived from the pixels and the factor, never measured`() {
        // Deliberate: the size is what the pixel count and the reported factor imply, so it tells a
        // server nothing it cannot already compute. Reading a real EDID or DisplayMetrics.xdpi here
        // would turn a protocol field into a hardware fingerprint.
        val scale = RdpDisplayScale.of(2560, 1440, 1.25f)
        val applied = scale.desktopScaleFactor / 100.0

        assertEquals((2560 * 25.4 / (96 * applied)).roundToInt(), scale.physicalWidthMm)
        assertEquals((1440 * 25.4 / (96 * applied)).roundToInt(), scale.physicalHeightMm)
    }

    @Test
    fun `a nonsensical scale or size is reported as no scaling`() {
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(1920, 1080, 0f))
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(1920, 1080, -2f))
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(1920, 1080, Float.NaN))
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(0, 1080, 2f))
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(1920, 0, 2f))
    }

    @Test
    fun `a desktop too small to scale keeps the server's own DPI`() {
        // Under the minimum logical size there is no room to scale into, and reporting a factor
        // would shrink the session instead of enlarging its text.
        assertEquals(RdpDisplayScale.NONE, RdpDisplayScale.of(800, 600, 2f))
    }
}
