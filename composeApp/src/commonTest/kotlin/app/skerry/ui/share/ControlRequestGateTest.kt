package app.skerry.ui.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Issue #343: `ShareFrame.ControlRequest` was the one guest-to-host frame nothing limited. A member
 * can send it in a loop, and the relay can replay one captured ciphertext — it re-authenticates
 * under the team key exactly as the original did, there being no sequence number on this frame. So
 * the host's answer, not the wire, is what has to hold the prompt down.
 */
class ControlRequestGateTest {

    @Test
    fun the_first_request_is_put_in_front_of_the_host() {
        assertTrue(ControlRequestGate(TestTimeSource()).admits("mate@x.io"))
    }

    @Test
    fun a_request_arriving_while_one_is_pending_is_dropped() {
        val gate = ControlRequestGate(TestTimeSource())

        assertTrue(gate.admits("mate@x.io"))
        // The host is deciding; neither a repeat nor a colleague may rewrite the question on screen.
        assertFalse(gate.admits("mate@x.io"))
        assertFalse(gate.admits("mallory@x.io"))
    }

    /**
     * The Grant/Deny row lives inside the share popup, so "the host is deciding" can mean "the host
     * never opened it". A question nobody answers has to let go of the slot, or one frame at the
     * start of a share mutes every viewer for the rest of it.
     */
    @Test
    fun a_question_nobody_answers_ages_out() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)

        assertTrue(gate.admits("mallory@x.io"))
        time += CONTROL_REASK_WINDOW - 1.seconds
        assertFalse(gate.admits("mate@x.io"), "the question is still in front of the host")
        time += 1.seconds
        assertTrue(gate.admits("mate@x.io"), "an unanswered question held the slot for the whole share")
    }

    @Test
    fun a_denial_holds_the_same_asker_off_for_the_window() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)

        assertTrue(gate.admits("mate@x.io"))
        gate.answered(granted = false)

        time += CONTROL_PROMPT_FLOOR // past the share-wide floor, so it is the denial holding this
        assertFalse(gate.admits("mate@x.io"), "the denied prompt came straight back")
        time += CONTROL_REASK_WINDOW - CONTROL_PROMPT_FLOOR - 1.seconds
        assertFalse(gate.admits("mate@x.io"))
        time += 1.seconds
        assertTrue(gate.admits("mate@x.io"), "a decision this old is not the one being re-raised")
    }

    /** The window is the asker's, not the share's: a colleague still gets their one question. */
    @Test
    fun a_denial_does_not_silence_the_other_viewers() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)

        gate.admits("mate@x.io")
        gate.answered(granted = false)
        time += CONTROL_PROMPT_FLOOR

        assertTrue(gate.admits("mallory@x.io"))
    }

    /**
     * The account is the relay's own stamp, so the per-account window costs a hostile relay one
     * invented name per replay and nothing else. The floor is what it cannot write around: a minute
     * of frames buys it six questions, not sixty.
     */
    @Test
    fun a_relay_inventing_a_fresh_name_each_time_still_waits_out_the_floor() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)
        var raised = 0

        repeat(60) { i ->
            if (gate.admits("ghost$i@x.io")) {
                raised++
                gate.answered(granted = false) // the host dismisses it as fast as it arrives
            }
            time += 1.seconds
        }

        assertEquals(6, raised, "a minute of invented accounts should cost one prompt per floor")
    }

    /** Granting ends every refusal: input is allowed, and after a revoke asking must work again. */
    @Test
    fun granting_clears_what_was_refused_before() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)

        gate.admits("mate@x.io")
        gate.answered(granted = false)
        time += CONTROL_PROMPT_FLOOR
        assertTrue(gate.admits("mallory@x.io"))
        gate.answered(granted = true)
        time += CONTROL_PROMPT_FLOOR

        assertTrue(gate.admits("mate@x.io"))
    }

    /**
     * A relay older than the naming protocol sends no account. Those requests share one bucket —
     * nothing separates them — so a denial holds all of them off, and that is the whole budget an
     * unnamed asker gets.
     */
    @Test
    fun unnamed_requests_share_one_bucket() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)

        assertTrue(gate.admits(null))
        gate.answered(granted = false)
        time += CONTROL_PROMPT_FLOOR

        assertFalse(gate.admits(null))
        assertTrue(gate.admits("mate@x.io"), "a named viewer is not held down by an unnamed refusal")
    }

    /**
     * A viewer who leaves takes their question with them: the slot is freed at once, so the next
     * colleague to ask is not dropped in silence behind a question nobody can answer any more.
     * The account that asked is still charged its window — leaving is something a viewer decides,
     * so a free withdrawal would be a way to buy a prompt every [CONTROL_PROMPT_FLOOR] instead of
     * one a minute: ask, drop the socket, rejoin, ask again.
     */
    @Test
    fun a_withdrawn_question_frees_the_slot_for_everyone_else() {
        val time = TestTimeSource()
        val gate = ControlRequestGate(time)

        assertTrue(gate.admits("mate@x.io"))
        gate.withdrawn()

        time += CONTROL_PROMPT_FLOOR
        assertTrue(gate.admits("alice@x.io"), "the slot stayed held by a question that was withdrawn")
        gate.answered(granted = false)

        time += CONTROL_PROMPT_FLOOR
        assertFalse(gate.admits("mate@x.io"), "leaving and rejoining bought a prompt the window denied")
    }

    /** A new share is a new session: nothing was asked in it, refused in it, or waiting on the floor. */
    @Test
    fun a_reset_forgets_the_previous_share() {
        val gate = ControlRequestGate(TestTimeSource())

        gate.admits("mate@x.io")
        gate.answered(granted = false)
        gate.reset()

        assertTrue(gate.admits("mate@x.io"))
    }
}
