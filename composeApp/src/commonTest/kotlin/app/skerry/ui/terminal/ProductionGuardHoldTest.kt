package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuardPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two rules every input path shares. [TerminalScreenStateTest] covers them through the real
 * paths (typed, pasted, ready-made); these pin them on the state machine itself.
 */
class ProductionGuardHoldTest {

    private fun guarding() = ProductionGuardHold().apply {
        policy = ProductionGuardPolicy(production = true, confirmWarnings = true)
    }

    @Test
    fun a_session_with_no_guard_holds_nothing_and_never_classifies() {
        val hold = ProductionGuardHold()
        var asked = false
        assertFalse(hold.hold("rm -rf /\n", HeldInputSource.Typed) { asked = true; listOf("rm -rf /") })
        // Reading the screen and the tracked line is wasted work off a production host.
        assertFalse(asked)
        assertNull(hold.pending)
    }

    @Test
    fun a_risky_block_is_held_with_the_input_to_replay() {
        val hold = guarding()
        assertTrue(hold.hold("rm -rf /srv\n", HeldInputSource.Paste) { listOf("rm -rf /srv") })
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertEquals(HeldInput("rm -rf /srv\n", HeldInputSource.Paste), hold.take())
        assertNull(hold.pending)
    }

    @Test
    fun nothing_else_runs_while_something_is_held() {
        val hold = guarding()
        hold.hold("rm -rf /srv\n", HeldInputSource.Typed) { listOf("rm -rf /srv") }

        var asked = false
        // Harmless or not, it is held back — and it isn't even classified: the answer is the same.
        assertTrue(hold.hold("uptime\n", HeldInputSource.Command) { asked = true; listOf("uptime") })
        assertFalse(asked)
        // What was dropped stays dropped: the pending command is still the one being asked about.
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertEquals("rm -rf /srv\n", hold.take()?.text)
    }

    @Test
    fun a_harmless_block_is_not_held() {
        val hold = guarding()
        assertFalse(hold.hold("uptime\n", HeldInputSource.Typed) { listOf("uptime") })
        assertNull(hold.pending)
        assertNull(hold.take())
    }

    @Test
    fun confirming_twice_replays_nothing_the_second_time() {
        val hold = guarding()
        hold.hold("shutdown now\n", HeldInputSource.Command) { listOf("shutdown now") }

        assertEquals("shutdown now\n", hold.take()?.text)
        // A double click on Confirm must not run the command again.
        assertNull(hold.take())
    }

    @Test
    fun dismissing_drops_the_held_input() {
        val hold = guarding()
        hold.hold("shutdown now\n", HeldInputSource.Typed) { listOf("shutdown now") }

        hold.dismiss()
        assertNull(hold.pending)
        assertNull(hold.take())
        // The next command is judged on its own again.
        assertTrue(hold.hold("rm -rf /etc\n", HeldInputSource.Typed) { listOf("rm -rf /etc") })
    }
}
