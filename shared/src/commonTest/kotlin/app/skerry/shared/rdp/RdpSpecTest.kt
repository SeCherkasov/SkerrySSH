package app.skerry.shared.rdp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a stored RDP profile promises across versions: a record written before a field existed still
 * loads, and the quality travels as its name rather than its position in the enum.
 */
class RdpSpecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a profile saved before the quality setting keeps the picture it had`() {
        val stored = """{"loadBalanceInfo":"tsv://x","audioOutput":true}"""

        val spec = json.decodeFromString(RdpSpec.serializer(), stored)

        assertEquals(RdpImageQuality.Medium, spec.quality)
    }

    @Test
    fun `the quality is stored by name, so reordering the enum cannot repaint a profile`() {
        val encoded = json.encodeToString(RdpSpec.serializer(), RdpSpec(quality = RdpImageQuality.High))

        assertTrue(encoded.contains("\"quality\":\"High\""), encoded)
        assertEquals(RdpImageQuality.High, json.decodeFromString(RdpSpec.serializer(), encoded).quality)
    }

    @Test
    fun `only a non-default quality makes a spec worth storing`() {
        assertTrue(RdpSpec().isEmpty)
        assertFalse(RdpSpec(quality = RdpImageQuality.Low).isEmpty)
    }

    // --- F-28: graphics-path settings ---

    @Test
    fun `a profile saved before the display settings keeps today's graphics path`() {
        val stored = """{"loadBalanceInfo":"tsv://x"}"""

        val spec = json.decodeFromString(RdpSpec.serializer(), stored)

        assertTrue(spec.graphicsPipeline)
        assertTrue(spec.remoteFx)
        assertEquals(RdpH264Mode.Auto, spec.h264)
    }

    @Test
    fun `the h264 mode is stored by name, so reordering the enum cannot switch codecs`() {
        val encoded = json.encodeToString(RdpSpec.serializer(), RdpSpec(h264 = RdpH264Mode.Avc444))

        assertTrue(encoded.contains("\"h264\":\"Avc444\""), encoded)
        assertEquals(RdpH264Mode.Avc444, json.decodeFromString(RdpSpec.serializer(), encoded).h264)
    }

    @Test
    fun `any non-default display setting makes the spec worth storing`() {
        assertFalse(RdpSpec(graphicsPipeline = false).isEmpty)
        assertFalse(RdpSpec(remoteFx = false).isEmpty)
        assertFalse(RdpSpec(h264 = RdpH264Mode.Off).isEmpty)
    }
}
