package app.skerry.ui.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule behind the idle auto-lock, driven a tick at a time. Both directions matter: a vault that
 * never locks is a security setting that lies, and one that locks over live work drops SSH sessions
 * and tunnels (issue #291).
 */
class IdleLockPolicyTest {

    @Test
    fun `locks after a full quiet window`() {
        val policy = IdleLockPolicy(IDLE)

        assertFalse(policy.runQuietly(IDLE - policy.tickMs), "locked before the window was over")
        assertTrue(policy.onTick(workInFlight = false))
    }

    @Test
    fun `input restarts the countdown`() {
        val policy = IdleLockPolicy(IDLE)

        // Nearly the whole window quiet, then one keystroke — the countdown starts over.
        policy.runQuietly(IDLE - policy.tickMs)
        policy.touch()
        assertFalse(policy.onTick(workInFlight = false))

        assertFalse(policy.runQuietly(IDLE - policy.tickMs), "the keystroke didn't reset the window")
        assertTrue(policy.onTick(workInFlight = false))
    }

    /** A transfer or a runbook step is the user's work: locking would close the session it runs on. */
    @Test
    fun `work in flight defers the lock`() {
        val policy = IdleLockPolicy(IDLE)
        policy.runQuietly(IDLE - policy.tickMs)

        assertFalse(policy.onTick(workInFlight = true), "locked on top of running work")
        // Deferred, not cancelled: nothing further is owed to the timer once the work ends.
        assertFalse(policy.runQuietly(IDLE - policy.tickMs))
        assertTrue(policy.onTick(workInFlight = false))
    }

    /**
     * A policy outlives the session it locked: the gate keeps it while the threshold stands, so the
     * vault unlocked again must get a whole window, not the tail of the one that locked it.
     */
    @Test
    fun `restart gives back the whole window`() {
        val policy = IdleLockPolicy(IDLE)
        assertTrue(policy.runQuietly(IDLE + policy.tickMs), "the window never ran out")

        policy.restart()

        assertFalse(policy.runQuietly(IDLE - policy.tickMs), "locked on the tail of the old window")
        assertTrue(policy.onTick(workInFlight = false))
    }

    /**
     * "Work in flight" is a claim the far end of the wire gets to make, so it cannot be open-ended:
     * a host that never finishes a transfer must not keep the vault open on an empty desk all night.
     */
    @Test
    fun `deferral is bounded`() {
        val policy = IdleLockPolicy(IDLE)

        assertFalse(policy.runQuietly(IDLE * DEFERRAL_WINDOWS, workInFlight = true), "locked inside the allowance")
        assertTrue(
            policy.runQuietly(IDLE + policy.tickMs, workInFlight = true),
            "work held the lock off past the cap",
        )
    }

    /**
     * The allowance is measured in the user's own windows, not in a flat hour. Someone who asked for
     * "After 1 minute" said how long this desk may stand unattended and unlocked; a fixed cap would
     * answer that with an hour, and the setting would be off by sixty.
     */
    @Test
    fun `the cap scales with the threshold`() {
        val impatient = IdleLockPolicy(60_000L)

        assertFalse(impatient.runQuietly(10 * 60_000L, workInFlight = true), "locked inside the allowance")
        assertTrue(
            impatient.runQuietly(60_000L + impatient.tickMs, workInFlight = true),
            "a one-minute threshold bought an hour of deferral",
        )
    }

    /** And never past the absolute ceiling, however patient the threshold is. */
    @Test
    fun `the cap never exceeds the ceiling`() {
        val patient = IdleLockPolicy(30 * 60_000L)

        assertTrue(
            patient.runQuietly(MAX_DEFERRAL + 30 * 60_000L + patient.tickMs, workInFlight = true),
            "work held the lock off past the absolute ceiling",
        )
    }

    /** The cap is on absence, not on the work: someone at the keyboard buys the whole allowance back. */
    @Test
    fun `the user's return lifts the cap`() {
        val policy = IdleLockPolicy(IDLE)
        policy.runQuietly(MAX_DEFERRAL, workInFlight = true)

        policy.touch()

        assertFalse(
            policy.runQuietly(IDLE + policy.tickMs, workInFlight = true),
            "the cap survived the user's return",
        )
    }

    /** The tick is a fraction of the threshold, so the lock is late by at most that fraction. */
    @Test
    fun `the tick scales with the threshold`() {
        assertEquals(6_000L, IdleLockPolicy(60_000L).tickMs)
        assertEquals(180_000L, IdleLockPolicy(30 * 60_000L).tickMs)
        // Never below the floor, and never longer than the window it divides.
        assertEquals(500L, IdleLockPolicy(500L).tickMs)
        // A tenth of the window under the floor is raised to it, not left to poll every 500ms.
        assertEquals(1_000L, IdleLockPolicy(5_000L).tickMs)
    }

    /** "No auto-lock" is a null policy in [VaultGate], never a zero threshold that spins the timer. */
    @Test
    fun `a non-positive threshold is refused`() {
        assertFailsWith<IllegalArgumentException> { IdleLockPolicy(0) }
    }

    /**
     * Ticks [duration] worth of silence — no user input, [workInFlight] as given.
     * @return whether the vault locked during it.
     */
    private fun IdleLockPolicy.runQuietly(duration: Long, workInFlight: Boolean = false): Boolean {
        var elapsed = 0L
        while (elapsed < duration) {
            if (onTick(workInFlight)) return true
            elapsed += tickMs
        }
        return false
    }
}

private const val IDLE = 5 * 60_000L

/** Mirrors `MAX_DEFERRAL_WINDOWS`, which is private to the policy. */
private const val DEFERRAL_WINDOWS = 10

/** Mirrors `MAX_DEFERRAL_MS`, the absolute ceiling, which is private to the policy. */
private const val MAX_DEFERRAL = 60 * 60_000L
