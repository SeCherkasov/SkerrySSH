package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The display control channel (MS-RDPEDISP): capability handling and the monitor layout it sends. */
class DisplayControlChannelTest {

    private val sent = mutableListOf<ByteArray>()
    private val channel = DisplayControlChannel { data -> sent.add(data) }

    /** DISPLAYCONTROL_CAPS_PDU: header, MaxNumMonitors, MaxMonitorAreaFactorA/B. */
    private fun capsPdu(monitors: Int = 16, factorA: Int = 8192, factorB: Int = 8192): ByteArray =
        RdpWriter(20).u32le(0x00000005).u32le(20).u32le(monitors).u32le(factorA).u32le(factorB).toByteArray()

    private fun layout(pdu: ByteArray): List<Int> =
        RdpReader(pdu).let { reader -> List(pdu.size / 4) { reader.u32le() } }

    private companion object {
        /** Indices of the monitor's Width/Height inside the flattened PDU. */
        const val WIDTH = 7
        const val HEIGHT = 8

        /** Index of PhysicalWidth, the first of the five scaling fields, in the same PDU. */
        const val PHYSICAL_WIDTH = 9
    }

    @Test
    fun `a resolution request before the server's capabilities is not sent`() = runTest {
        channel.requestResolution(1920, 1080)

        assertTrue(sent.isEmpty(), "a monitor layout went out before the server offered the channel")
    }

    @Test
    fun `the capability PDU is what announces that resizing is possible`() = runTest {
        assertTrue(channel.drainUpdates().isEmpty())

        channel.onMessage(capsPdu())

        assertEquals(listOf(RdpUpdate.ResizeSupported), channel.drainUpdates())
        assertTrue(channel.drainUpdates().isEmpty(), "the announcement repeated itself")
    }

    @Test
    fun `a resolution request is a layout of one primary monitor`() = runTest {
        channel.onMessage(capsPdu())

        channel.requestResolution(1920, 1080)

        assertContentEquals(
            listOf(
                0x00000002, // DISPLAYCONTROL_PDU_TYPE_MONITOR_LAYOUT
                56, // header (8) + MonitorLayoutSize/NumMonitors (8) + one 40-byte monitor
                40, // MonitorLayoutSize
                1, // NumMonitors
                0x00000001, // DISPLAYCONTROL_MONITOR_PRIMARY
                0, 0, // Left, Top
                1920, 1080, // Width, Height
                0, 0, // PhysicalWidth, PhysicalHeight — unknown, so the server ignores them
                0, // Orientation: landscape
                0, 0, // DesktopScaleFactor, DeviceScaleFactor — unset
            ),
            layout(sent.single()),
        )
    }

    @Test
    fun `a scaled display states its physical size and both scale factors`() = runTest {
        // Without these the server fills the physical pixels with a 96 dpi desktop, and every glyph
        // in the session comes out at a fraction of the size of the client's own UI.
        channel.onMessage(capsPdu())

        channel.requestResolution(2880, 1800, scale = 1.5f)

        val scale = RdpDisplayScale.of(2880, 1800, 1.5f)
        assertContentEquals(
            listOf(
                scale.physicalWidthMm, scale.physicalHeightMm,
                0, // Orientation: landscape
                scale.desktopScaleFactor, scale.deviceScaleFactor,
            ),
            layout(sent.single()).subList(PHYSICAL_WIDTH, PHYSICAL_WIDTH + 5),
        )
        assertEquals(150, scale.desktopScaleFactor)
    }

    @Test
    fun `an odd width is rounded down, which the protocol requires`() = runTest {
        channel.onMessage(capsPdu())

        channel.requestResolution(1367, 768)

        assertEquals(1366, layout(sent.single())[WIDTH])
    }

    @Test
    fun `a size outside the protocol's range is clamped instead of refused`() = runTest {
        channel.onMessage(capsPdu())

        channel.requestResolution(120, 20_000)

        val fields = layout(sent.single())
        assertEquals(200, fields[WIDTH], "width below the minimum")
        assertEquals(8192, fields[HEIGHT], "height above the maximum")
    }

    @Test
    fun `a layout past the server's maximum area is scaled down, keeping its proportions`() = runTest {
        // 1000 x 1000 = one megapixel; a 1920x1080 desktop is twice that.
        channel.onMessage(capsPdu(factorA = 1000, factorB = 1000))

        channel.requestResolution(1920, 1080)

        val fields = layout(sent.single())
        val width = fields[WIDTH]
        val height = fields[HEIGHT]
        assertTrue(width.toLong() * height <= 1_000_000, "sent ${width}x$height, past the server's limit")
        assertTrue(width > height, "the aspect ratio was not kept: ${width}x$height")
        assertEquals(0, width % 2)
    }

    @Test
    fun `a truncated capability PDU leaves the channel unusable rather than half-configured`() = runTest {
        channel.onMessage(RdpWriter(12).u32le(0x00000005).u32le(12).u32le(16).toByteArray())

        channel.requestResolution(1920, 1080)

        assertTrue(channel.drainUpdates().isEmpty())
        assertTrue(sent.isEmpty())
    }
}
