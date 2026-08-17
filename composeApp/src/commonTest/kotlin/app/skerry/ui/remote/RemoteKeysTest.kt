package app.skerry.ui.remote

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which key is which modifier, for the state that has to lift one the window manager swallowed
 * ([RemoteDesktopScreenState.syncModifiers]). Both sides count: a stuck right Alt reads as AltGr on
 * a European layout and kills every click on the remote machine just the same as the left one.
 */
class RemoteKeysTest {

    @Test
    fun both_sides_of_every_modifier_are_recognised() {
        assertEquals(RemoteModifier.Ctrl, remoteModifier(Key.CtrlLeft))
        assertEquals(RemoteModifier.Ctrl, remoteModifier(Key.CtrlRight))
        assertEquals(RemoteModifier.Alt, remoteModifier(Key.AltLeft))
        assertEquals(RemoteModifier.Alt, remoteModifier(Key.AltRight))
        assertEquals(RemoteModifier.Shift, remoteModifier(Key.ShiftLeft))
        assertEquals(RemoteModifier.Shift, remoteModifier(Key.ShiftRight))
    }

    /**
     * Super is not reconciled: `isMetaPressed` carries it on macOS only — AWT never sets it for the
     * Super key on X11 or Windows, so comparing against it would lift the key mid-chord and turn
     * Win+R on the remote machine into a bare "r".
     */
    @Test
    fun super_is_not_a_modifier_the_local_machine_can_be_asked_about() {
        assertNull(remoteModifier(Key.MetaLeft))
        assertNull(remoteModifier(Key.MetaRight))
    }

    @Test
    fun an_ordinary_key_is_no_modifier() {
        assertNull(remoteModifier(Key.A))
        assertNull(remoteModifier(Key.Enter))
        assertNull(remoteModifier(Key.Spacebar))
    }

    /** What [RemoteModifiers] answers is what the reconciliation asks it, one modifier at a time. */
    @Test
    fun the_local_state_answers_per_modifier() {
        val held = RemoteModifiers(ctrl = true, alt = false, shift = true)
        assertEquals(true, held.holds(RemoteModifier.Ctrl))
        assertEquals(false, held.holds(RemoteModifier.Alt))
        assertEquals(true, held.holds(RemoteModifier.Shift))
    }
}
