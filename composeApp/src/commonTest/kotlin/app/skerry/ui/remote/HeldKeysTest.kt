package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the server is told to release when the local machine and the server disagree about a
 * modifier. The events themselves are what matters, not their number: a release carrying the wrong
 * key leaves the real one held, and a remote desktop that thinks Alt is down stops answering the
 * mouse — the bug this whole class exists for.
 */
class HeldKeysTest {

    @Test
    fun the_release_owed_carries_the_key_that_was_actually_held() {
        val held = HeldKeys()
        held.record(ALT_LEFT, down = true, modifier = RemoteModifier.Alt)
        held.record(CTRL_LEFT, down = true, modifier = RemoteModifier.Ctrl)

        // The window manager kept Alt's release: the local machine holds Ctrl only.
        val owed = held.outOfStep(RemoteModifiers(ctrl = true, alt = false, shift = false), except = null)

        assertEquals(listOf(ALT_LEFT), owed, "the release named a key other than the one still down")
    }

    /** Left and right are two keys the server holds separately; releasing the wrong one strands the other. */
    @Test
    fun the_two_sides_of_a_modifier_are_released_by_identity() {
        val held = HeldKeys()
        held.record(ALT_LEFT, down = true, modifier = RemoteModifier.Alt)
        held.record(ALT_RIGHT, down = true, modifier = RemoteModifier.Alt)

        val owed = held.outOfStep(RemoteModifiers(ctrl = false, alt = false, shift = false), except = null)

        assertEquals(setOf(ALT_LEFT, ALT_RIGHT), owed.toSet(), "a side of the modifier was left held on the server")
        assertEquals(ALT_RIGHT, owed.first(), "the releases did not reverse the press order")
    }

    /** Nothing drifted: the common case on every raw mouse sample owes nothing and allocates nothing. */
    @Test
    fun a_modifier_the_user_still_holds_is_left_alone() {
        val held = HeldKeys()
        held.record(SHIFT_LEFT, down = true, modifier = RemoteModifier.Shift)

        val owed = held.outOfStep(RemoteModifiers(ctrl = false, alt = false, shift = true), except = null)

        assertTrue(owed.isEmpty(), "a modifier the user is holding was released: $owed")
    }

    /** The reconciliation runs once: the key it released is no longer held to release again. */
    @Test
    fun a_key_released_once_is_not_owed_twice() {
        val held = HeldKeys()
        held.record(CTRL_LEFT, down = true, modifier = RemoteModifier.Ctrl)
        val none = RemoteModifiers(ctrl = false, alt = false, shift = false)

        assertEquals(listOf(CTRL_LEFT), held.outOfStep(none, except = null))
        assertTrue(held.outOfStep(none, except = null).isEmpty(), "the same release went out twice")
    }

    private companion object {
        val CTRL_LEFT = RemoteKeyEvent(keySym = 0xFFE3, scancode = 0x1D)
        val ALT_LEFT = RemoteKeyEvent(keySym = 0xFFE9, scancode = 0x38)
        val ALT_RIGHT = RemoteKeyEvent(keySym = 0xFFEA, scancode = 0x38, extended = true)
        val SHIFT_LEFT = RemoteKeyEvent(keySym = 0xFFE1, scancode = 0x2A)
    }
}
