package app.skerry.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the OS color-scheme parsers that drive [ThemeMode.SYSTEM] live-switching on desktop. */
class SystemThemeDetectorTest {

    @Test
    fun windows_dword_zero_is_dark_one_is_light() {
        val dark = "\r\nHKEY_CURRENT_USER\\...\\Personalize\r\n    AppsUseLightTheme    REG_DWORD    0x0\r\n"
        val light = "    AppsUseLightTheme    REG_DWORD    0x1"
        assertEquals(true, parseWindowsDark(0, dark))
        assertEquals(false, parseWindowsDark(0, light))
    }

    @Test
    fun windows_missing_value_or_failed_query_is_unknown() {
        assertNull(parseWindowsDark(1, "ERROR: The system was unable to find the specified registry key"))
        assertNull(parseWindowsDark(0, "some unrelated output"))
    }

    @Test
    fun mac_dark_only_when_key_present() {
        assertEquals(true, parseMacDark(0, "Dark\n"))
        // Light mode: `defaults` exits non-zero because the key does not exist.
        assertEquals(false, parseMacDark(1, "does not exist"))
    }

    @Test
    fun portal_color_scheme_maps_prefer_dark_and_light() {
        assertEquals(true, parsePortalColorScheme(0, "(<<uint32 1>>,)"))
        assertEquals(false, parsePortalColorScheme(0, "(<<uint32 2>>,)"))
        // 0 = no preference → unknown, caller falls back.
        assertNull(parsePortalColorScheme(0, "(<<uint32 0>>,)"))
        assertNull(parsePortalColorScheme(1, "Error: portal not available"))
    }

    @Test
    fun gsettings_prefer_dark_is_dark() {
        assertEquals(true, parseGsettingsColorScheme(0, "'prefer-dark'\n"))
        assertEquals(false, parseGsettingsColorScheme(0, "'default'\n"))
        assertEquals(false, parseGsettingsColorScheme(0, "'prefer-light'\n"))
        assertNull(parseGsettingsColorScheme(1, ""))
    }
}
