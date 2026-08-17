package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.shared.graphics.RemoteScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Input travels through one actor per session (F-10): the transport sees events in exactly the
 * order the user produced them, a burst of raw mouse moves collapses to the freshest one (F-11),
 * focus loss releases what was held (F-12), and the lock keys are kept in step (F-13).
 */
class RemoteInputOrderTest {

    /** Every write suspends mid-flight, so racing coroutines would interleave visibly. */
    private class SlowSession(
        updates: Flow<RemoteDesktopUpdate> = MutableSharedFlow(),
    ) : FakeRemoteDesktop(updates = updates) {
        val trace = mutableListOf<String>()

        override suspend fun sendKey(event: RemoteKeyEvent, down: Boolean) {
            trace += "key:${event.scancode}:$down:begin"
            delay(5)
            trace += "key:${event.scancode}:$down:end"
            super.sendKey(event, down)
        }

        override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int) {
            trace += "pointer:$x:$buttonMask:begin"
            delay(5)
            trace += "pointer:$x:$buttonMask:end"
            super.sendPointer(x, y, buttonMask)
        }
    }

    @Test
    fun events_reach_the_transport_whole_and_in_the_order_they_were_made() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = SlowSession()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onKey(RemoteKeyEvent(scancode = 30), down = true)
        screen.onKey(RemoteKeyEvent(scancode = 30), down = false)
        screen.onPointer(5, 5, 1)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "key:30:true:begin", "key:30:true:end",
                "key:30:false:begin", "key:30:false:end",
                "pointer:5:1:begin", "pointer:5:1:end",
            ),
            session.trace,
            "a write must finish before the next one starts, in submission order",
        )
        scope.cancel()
    }

    @Test
    fun a_burst_of_moves_collapses_to_the_freshest_and_a_click_is_never_dropped() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = SlowSession()
        val screen = RemoteDesktopScreenState(session, scope)

        for (x in 1..5) screen.onPointer(x, 0, 0)
        screen.onPointer(5, 0, 1) // press
        screen.onPointer(5, 0, 0) // release
        advanceUntilIdle()

        val sent = session.pointers
        assertEquals(1, sent.count { it.third == 1 }, "exactly one press: $sent")
        assertTrue(sent.size <= 4, "moves must coalesce, not queue: $sent")
        val press = sent.indexOfFirst { it.third == 1 }
        assertEquals(5, sent[press - 1].first, "the freshest move lands before the click")
        scope.cancel()
    }

    @Test
    fun cancelling_the_session_scope_stops_the_actor_mid_write() = runTest {
        // The project's named bug class: a coroutine with its own pacing delay must die with the
        // scope, and nothing queued may fire into a dead session afterwards.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = SlowSession()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onKey(RemoteKeyEvent(scancode = 30), down = true)
        screen.onKey(RemoteKeyEvent(scancode = 30), down = false)
        testScheduler.advanceTimeBy(1) // the first write begins and parks in its delay
        testScheduler.runCurrent()
        scope.cancel()
        advanceUntilIdle()

        assertEquals(
            listOf("key:30:true:begin"),
            session.trace,
            "the write in flight stops at its suspension point and the queue dies with the scope",
        )
    }

    @Test
    fun losing_focus_releases_every_held_key_in_reverse_order() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onKey(RemoteKeyEvent(scancode = 29), down = true) // Ctrl
        screen.onKey(RemoteKeyEvent(scancode = 56), down = true) // Alt
        screen.onKey(RemoteKeyEvent(scancode = 15), down = true) // Tab
        screen.onKey(RemoteKeyEvent(scancode = 15), down = false) // Tab released normally
        advanceUntilIdle()
        screen.notifyFocus(false)
        advanceUntilIdle()

        assertEquals(
            listOf(29 to true, 56 to true, 15 to true, 15 to false, 56 to false, 29 to false),
            session.keys.map { it.first.scancode to it.second },
            "held keys are released newest-first; a key already up is not released again",
        )
        scope.cancel()
    }

    @Test
    fun two_held_sequence_keys_are_both_released_on_focus_loss() = runTest {
        // PrintScreen and Pause carry no flat scancode — only a sequence (F-18). Their identities
        // in the held-key map must still differ, or losing focus releases one and leaves the other
        // latched on the server for the rest of the session.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val printScreen = RemoteKeyEvent(
            sequence = listOf(RemoteScan(0x2A, extended = true), RemoteScan(0x37, extended = true)),
        )
        val pause = RemoteKeyEvent(
            sequence = listOf(RemoteScan(0x1D, extended1 = true), RemoteScan(0x45)),
        )

        screen.onKey(printScreen, down = true)
        screen.onKey(pause, down = true)
        advanceUntilIdle()
        screen.notifyFocus(false)
        advanceUntilIdle()

        val downs = session.keys.count { it.second }
        val ups = session.keys.count { !it.second }
        assertEquals(2, downs)
        assertEquals(2, ups, "both held sequence keys must be released, not just the last one")
        scope.cancel()
    }

    @Test
    fun lock_keys_are_synchronised_when_they_change_and_when_focus_returns() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onLockKeys(LockKeys(scroll = false, num = true, caps = false))
        screen.onLockKeys(LockKeys(scroll = false, num = true, caps = false)) // unchanged: no send
        screen.onLockKeys(LockKeys(scroll = false, num = true, caps = true))
        advanceUntilIdle()
        // Away from the session the user may toggle a lock; on the way back the state is resent.
        screen.notifyFocus(true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Triple(false, true, false),
                Triple(false, true, true),
                Triple(false, true, true),
            ),
            session.lockSyncs,
        )
        scope.cancel()
    }

    @Test
    fun hiding_the_session_releases_held_keys_too() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onKey(RemoteKeyEvent(scancode = 56), down = true)
        advanceUntilIdle()
        screen.setVisible(false)
        advanceUntilIdle()

        assertEquals(listOf(56 to true, 56 to false), session.keys.map { it.first.scancode to it.second })
        scope.cancel()
    }

    /**
     * A wheel notch is a press mask and the release that follows it. The release repeats the mask
     * the actor last saw, so it used to take the pure-move path and wait out [MOVE_INTERVAL] — a
     * fast scroll then arrived paced at 8 ms a pair, which is what "scrolling sometimes does
     * nothing" looks like from the far side (issue #265). A wheel write is an edge, not a move:
     * it goes out as made.
     */
    @Test
    fun wheel_notches_are_not_paced_like_moves() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        // Where the pointer already is, so the actor has a mask to repeat.
        screen.onPointer(10, 10, 0)
        advanceUntilIdle()
        val started = testScheduler.currentTime

        repeat(3) {
            screen.onPointer(10, 10, WHEEL_UP, wheel = true)
            screen.onPointer(10, 10, 0, wheel = true)
        }
        advanceUntilIdle()

        assertEquals(7, session.pointers.size, "every notch and its release reached the transport")
        assertEquals(0L, testScheduler.currentTime - started, "the notches were paced like moves")
        scope.cancel()
    }

    private companion object {
        /** RFB button 4 — the bit [app.skerry.ui.vnc.wheelMasks] sets for a scroll up. */
        const val WHEEL_UP = 1 shl 3
    }
}
