package app.skerry.ui.vnc

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class VncKeysymTest {

    @Test
    fun printable_ascii_maps_to_its_code_point() {
        assertEquals('a'.code.toLong(), keySymFor(Key.A, 'a'.code))
        assertEquals('Z'.code.toLong(), keySymFor(Key.Z, 'Z'.code))
        assertEquals('1'.code.toLong(), keySymFor(Key.One, '1'.code))
    }

    @Test
    fun named_keys_map_to_x11_keysyms() {
        assertEquals(0xFF0DL, keySymFor(Key.Enter, 0))
        assertEquals(0xFF08L, keySymFor(Key.Backspace, 0))
        assertEquals(0xFF1BL, keySymFor(Key.Escape, 0))
        assertEquals(0xFF09L, keySymFor(Key.Tab, 0))
        assertEquals(0xFF51L, keySymFor(Key.DirectionLeft, 0))
        assertEquals(0xFF54L, keySymFor(Key.DirectionDown, 0))
        assertEquals(0xFFE3L, keySymFor(Key.CtrlLeft, 0))
        assertEquals(0xFFBEL, keySymFor(Key.F1, 0))
        assertEquals(0xFFC9L, keySymFor(Key.F12, 0))
    }

    @Test
    fun named_key_wins_over_code_point() {
        // Enter can carry a '\r' code point; the named keysym must take priority.
        assertEquals(0xFF0DL, keySymFor(Key.Enter, '\r'.code))
    }

    @Test
    fun unicode_above_latin1_uses_the_x11_unicode_range() {
        val cyrillicA = 'а'.code // U+0430
        assertEquals(0x01000000L + cyrillicA, keySymFor(Key.Unknown, cyrillicA))
    }

    @Test
    fun no_code_point_and_no_mapping_yields_zero() {
        assertEquals(0L, keySymFor(Key.Unknown, 0))
    }
}
