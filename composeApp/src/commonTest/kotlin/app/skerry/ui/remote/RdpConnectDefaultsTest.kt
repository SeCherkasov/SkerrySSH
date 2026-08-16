package app.skerry.ui.remote

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Connect-time defaults for an RDP session: the desktop size follows the viewport instead of a
 * hardcoded 1080p (F-06), and the keyboard layout follows the local machine instead of a hardcoded
 * US (F-16).
 */
class RdpConnectDefaultsTest {

    @Test
    fun the_requested_desktop_is_the_viewport_rounded_and_clamped() {
        // RDP wants an even width; anything else is passed through as measured.
        assertEquals(IntSize(1918, 1161), rdpDesktopSize(IntSize(1919, 1161), FALLBACK))
        assertEquals(IntSize(2560, 1440), rdpDesktopSize(IntSize(2560, 1440), FALLBACK))
        // Clamped into what the protocol allows rather than refused.
        assertEquals(IntSize(200, 200), rdpDesktopSize(IntSize(64, 90), FALLBACK))
        assertEquals(IntSize(8192, 8192), rdpDesktopSize(IntSize(20_000, 9_000), FALLBACK))
    }

    @Test
    fun an_unmeasured_viewport_falls_back_to_the_old_default() {
        assertEquals(FALLBACK, rdpDesktopSize(IntSize.Zero, FALLBACK))
    }

    @Test
    fun the_keyboard_layout_follows_the_locale() {
        assertEquals(0x419, keyboardLayoutFor("ru", "RU"))
        assertEquals(0x407, keyboardLayoutFor("de", "DE"))
        assertEquals(0x809, keyboardLayoutFor("en", "GB"))
        assertEquals(0x409, keyboardLayoutFor("en", "US"))
    }

    @Test
    fun unknown_locales_fall_back_to_us_and_bare_languages_to_their_home_country() {
        assertEquals(0x409, keyboardLayoutFor("xx", ""))
        assertEquals(0x407, keyboardLayoutFor("de", ""), "a bare language maps to its main layout")
        assertEquals(0x419, keyboardLayoutFor("ru", ""))
        assertEquals(0x40C, keyboardLayoutFor("fr", "XX"), "an unknown country keeps the language's layout")
    }

    private companion object {
        val FALLBACK = IntSize(1920, 1080)
    }
}
