package app.skerry.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the whole feature rests on: the CPU is held exactly while sessions are open and the user
 * asked for it. A lock left held with no session drains the battery invisibly until the process
 * dies, and the transitions that can leave one behind — a stray remove, an empty replay after a
 * restart, the switch turned off under a live session — are all reachable on a device.
 */
class WakeLockGateTest {

    @Test
    fun theLockIsTakenOnlyWithSessionsAndTheSwitchOn() {
        val lock = FakeLock()
        val gate = WakeLockGate { lock }

        gate.sync(sessionCount = 0, enabled = true)
        assertFalse(lock.isHeld, "no session, nothing to keep awake")

        gate.sync(sessionCount = 1, enabled = false)
        assertFalse(lock.isHeld, "the switch is off")

        gate.sync(sessionCount = 1, enabled = true)
        assertTrue(lock.isHeld)
    }

    /** The last session closing, and the switch turned off under a live one, both let go. */
    @Test
    fun theLockIsReleasedWhenEitherSideStops() {
        val lock = FakeLock()
        val gate = WakeLockGate { lock }

        gate.sync(1, enabled = true)
        gate.sync(0, enabled = true)
        assertFalse(lock.isHeld, "the last session closed")

        gate.sync(2, enabled = true)
        assertTrue(lock.isHeld)
        gate.sync(2, enabled = false)
        assertFalse(lock.isHeld, "the switch was turned off with sessions still open")
    }

    /** Every teardown path calls it, including ones where nothing was ever taken. */
    @Test
    fun releasingIsIdempotent() {
        val lock = FakeLock()
        val gate = WakeLockGate { lock }

        gate.release()
        gate.release()
        assertEquals(0, lock.releases, "nothing was held, nothing to release")

        gate.sync(1, enabled = true)
        gate.release()
        gate.release()
        assertEquals(1, lock.releases)
        assertFalse(lock.isHeld)
    }

    /** Repeated syncs under an unchanged state must not stack acquisitions. */
    @Test
    fun holdingIsIdempotent() {
        val lock = FakeLock()
        val gate = WakeLockGate { lock }

        repeat(3) { gate.sync(1, enabled = true) }
        assertEquals(1, lock.acquisitions)
        assertTrue(lock.isHeld)
    }

    /** Release drops the handle, so the next hold has to ask for a fresh one. */
    @Test
    fun theLockIsTakenAgainAfterARelease() {
        val lock = FakeLock()
        var handles = 0
        val gate = WakeLockGate { handles++; lock }

        gate.sync(1, enabled = true)
        gate.sync(0, enabled = true)
        gate.sync(1, enabled = true)
        assertEquals(2, handles)
        assertEquals(2, lock.acquisitions)
        assertTrue(lock.isHeld)
    }

    /** A device with no PowerManager: the sessions still run, the gate just holds nothing. */
    @Test
    fun withoutALockToTakeTheGateStaysQuiet() {
        val gate = WakeLockGate { null }
        gate.sync(1, enabled = true)
        gate.release()
    }

    private class FakeLock : WakeLockHandle {
        var acquisitions = 0
        var releases = 0
        override var isHeld = false
            private set

        override fun acquire() {
            acquisitions++
            isHeld = true
        }

        override fun release() {
            releases++
            isHeld = false
        }
    }
}
