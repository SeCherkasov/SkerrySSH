package app.skerry.ui.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Подбор размеров окна desktop под доступную область экрана: ~90% экрана, но в рамках
 * [MIN_WINDOW]…[MAX_WINDOW] и никогда не больше самого экрана (для маленьких дисплеев — сжатие).
 */
class WindowSizingTest {

    @Test
    fun huge_screen_is_capped_at_max() {
        assertEquals(MAX_WINDOW, optimalWindowSize(DpSize(3840.dp, 2160.dp)))
    }

    @Test
    fun fullhd_caps_width_keeps_proportional_height() {
        val size = optimalWindowSize(DpSize(1920.dp, 1080.dp))
        // 0.9*1920=1728 > max → ширина прижата к максимуму; высота 0.9*1080 в допустимом диапазоне.
        assertEquals(MAX_WINDOW.width, size.width)
        assertTrue(size.height in MIN_WINDOW.height..MAX_WINDOW.height)
        assertTrue(size.height < 1080.dp)
    }

    @Test
    fun small_screen_shrinks_to_fit_and_never_exceeds_screen() {
        val screen = DpSize(1024.dp, 768.dp)
        val size = optimalWindowSize(screen)
        // Окно не должно вылезать за экран, даже если это меньше «минимума».
        assertTrue(size.width <= screen.width)
        assertTrue(size.height <= screen.height)
        assertEquals(1024.dp, size.width)
        assertEquals(MIN_WINDOW.height, size.height)
    }

    @Test
    fun typical_laptop_stays_within_bounds() {
        val screen = DpSize(1366.dp, 768.dp)
        val size = optimalWindowSize(screen)
        assertTrue(size.width <= screen.width && size.width in MIN_WINDOW.width..MAX_WINDOW.width)
        assertTrue(size.height <= screen.height && size.height in MIN_WINDOW.height..MAX_WINDOW.height)
    }

    @Test
    fun never_exceeds_screen_for_a_range_of_sizes() {
        listOf(
            DpSize(800.dp, 600.dp),
            DpSize(1280.dp, 800.dp),
            DpSize(2560.dp, 1440.dp),
        ).forEach { screen ->
            val size = optimalWindowSize(screen)
            assertTrue(size.width <= screen.width, "width exceeds screen for $screen")
            assertTrue(size.height <= screen.height, "height exceeds screen for $screen")
        }
    }
}
