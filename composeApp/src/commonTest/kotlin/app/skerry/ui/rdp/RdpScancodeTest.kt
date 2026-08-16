package app.skerry.ui.rdp

import androidx.compose.ui.input.key.Key
import app.skerry.shared.graphics.RemoteScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The scancode table around F-18: keys that are more than one scancode. Pause is the E1-prefixed
 * pair (it is not an E0 key, and `extended(0x46)` — Ctrl+Break — is what it wrongly was);
 * PrintScreen is the full `E0 2A E0 37`, which servers beyond Windows require.
 */
class RdpScancodeTest {

    @Test
    fun a_letter_is_one_plain_scancode() {
        assertEquals(listOf(RemoteScan(0x1E)), scancodeFor(Key.A)?.scans)
    }

    @Test
    fun pause_is_the_e1_prefixed_pair() {
        assertEquals(
            listOf(RemoteScan(0x1D, extended1 = true), RemoteScan(0x45)),
            scancodeFor(Key.Break)?.scans,
        )
    }

    @Test
    fun print_screen_is_the_full_two_scan_sequence() {
        assertEquals(
            listOf(RemoteScan(0x2A, extended = true), RemoteScan(0x37, extended = true)),
            scancodeFor(Key.PrintScreen)?.scans,
        )
    }

    @Test
    fun an_unmapped_key_still_has_no_scancode() {
        assertNull(scancodeFor(Key.Unknown))
    }
}
