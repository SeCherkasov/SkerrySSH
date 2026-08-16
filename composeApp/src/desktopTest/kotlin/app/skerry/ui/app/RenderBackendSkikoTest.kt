package app.skerry.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.skiko.OS

/**
 * The RenderBackend → skiko.renderApi mapping (F-30): AUTO must set nothing (so a hand-set
 * -Dskiko.renderApi survives), SOFTWARE is one value everywhere, HARDWARE is the platform's GPU API.
 */
class RenderBackendSkikoTest {

    @Test
    fun auto_sets_nothing_on_any_platform() {
        for (os in OS.entries) assertNull(skikoRenderApiFor(RenderBackend.AUTO, os))
    }

    @Test
    fun software_is_the_same_value_everywhere() {
        for (os in listOf(OS.Linux, OS.Windows, OS.MacOS)) {
            assertEquals("SOFTWARE", skikoRenderApiFor(RenderBackend.SOFTWARE, os))
        }
    }

    @Test
    fun hardware_names_the_platform_gpu_api() {
        assertEquals("OPENGL", skikoRenderApiFor(RenderBackend.HARDWARE, OS.Linux))
        assertEquals("DIRECT3D", skikoRenderApiFor(RenderBackend.HARDWARE, OS.Windows))
        assertEquals("METAL", skikoRenderApiFor(RenderBackend.HARDWARE, OS.MacOS))
    }
}
