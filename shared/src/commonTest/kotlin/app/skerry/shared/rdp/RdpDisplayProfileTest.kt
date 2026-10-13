package app.skerry.shared.rdp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** Display choices must survive storage rather than silently returning to the old defaults. */
class RdpDisplayProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `display choices survive a profile storage round trip`() {
        val spec = json.decodeFromString(RdpSpec.serializer(),
            """{"wallpaper":true,"desktopScalePercent":125,"resolution":{"width":1920,"height":1080}}""")
        val saved = json.encodeToString(RdpSpec.serializer(), spec)
        assertTrue(saved.contains("\"wallpaper\":true"), saved)
        assertTrue(saved.contains("\"desktopScalePercent\":125"), saved)
        assertTrue(saved.contains("\"width\":1920"), saved)
        assertTrue(saved.contains("\"height\":1080"), saved)
    }
}
