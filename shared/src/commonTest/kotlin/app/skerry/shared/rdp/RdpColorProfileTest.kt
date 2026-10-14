package app.skerry.shared.rdp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RdpColorProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `color choices survive storage and keep an otherwise default profile`() {
        val spec = json.decodeFromString(RdpSpec.serializer(),
            """{"colorDepth":16,"backgroundRgb":1122867}""")
        val stored = json.encodeToString(RdpSpec.serializer(), spec)
        assertTrue(stored.contains("\"colorDepth\":16"), stored)
        assertTrue(stored.contains("\"backgroundRgb\":1122867"), stored)
        assertFalse(spec.isEmpty)
        assertEquals(16, spec.requestedColorDepth)
        assertEquals(0x112233, spec.localBackgroundRgb)
    }

    @Test
    fun `old and unsupported stored choices keep safe defaults`() {
        assertEquals(32, RdpSpec().requestedColorDepth)
        assertNull(RdpSpec().localBackgroundRgb)
        for (depth in listOf(-1, 0, 8, 15, 33, Int.MAX_VALUE)) {
            assertEquals(32, RdpSpec(colorDepth = depth).requestedColorDepth)
        }
        for (rgb in listOf(-1, 0x1000000, Int.MAX_VALUE)) {
            assertNull(RdpSpec(backgroundRgb = rgb).localBackgroundRgb)
        }
    }

    @Test
    fun `rdp file session bpp imports only supported color depths`() {
        for (depth in listOf(16, 24, 32)) {
            val entry = checkNotNull(RdpFileImport.read("full address:s:host\nsession bpp:i:$depth", "host.rdp").host)
            assertEquals(depth, RdpFileImport.toHost(entry, "rdp").rdp?.colorDepth)
        }
        val entry = checkNotNull(RdpFileImport.read("full address:s:host\nsession bpp:i:15", "host.rdp").host)
        assertNull(RdpFileImport.toHost(entry, "rdp").rdp?.colorDepth)
    }
}
