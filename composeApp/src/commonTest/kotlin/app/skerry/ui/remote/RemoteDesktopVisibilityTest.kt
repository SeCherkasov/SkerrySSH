package app.skerry.ui.remote

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The lifecycle event a remote desktop reads as "someone can see this". Both platforms put the same
 * meaning on the started state — an Android activity that is on screen, a desktop window that is not
 * minimised — while focus (`ON_PAUSE`/`ON_RESUME`) says nothing about whether the picture is visible.
 */
class RemoteDesktopVisibilityTest {

    @Test
    fun the_started_state_is_what_being_on_screen_means() {
        assertEquals(true, windowVisibleAt(Lifecycle.Event.ON_START))
        assertEquals(false, windowVisibleAt(Lifecycle.Event.ON_STOP))
    }

    @Test
    fun losing_focus_does_not_hide_a_window() {
        // A desktop window drops to PAUSED whenever another application is clicked, and the remote
        // desktop is still there — suppressing output on it would blank the picture the user is
        // looking at while typing in a window next to it.
        assertNull(windowVisibleAt(Lifecycle.Event.ON_PAUSE))
        assertNull(windowVisibleAt(Lifecycle.Event.ON_RESUME))
        assertNull(windowVisibleAt(Lifecycle.Event.ON_CREATE))
    }

    @Test
    fun watching_a_window_that_is_already_up_reports_it_visible_at_once() {
        // What un-suppresses a session on the way back: the session that left the screen was told it
        // was hidden, and nothing else will say otherwise — the lifecycle is already started by the
        // time the picture is composed again, so only the state replayed on attach can put it right.
        val window = FakeWindow()
        window.registry.currentState = Lifecycle.State.RESUMED
        val reported = mutableListOf<Boolean>()

        observeWindowVisibility(window.lifecycle) { reported += it }

        assertEquals(listOf(true), reported)
    }

    @Test
    fun a_window_going_down_and_the_watch_ending_both_report_hidden() {
        val window = FakeWindow()
        window.registry.currentState = Lifecycle.State.RESUMED
        val reported = mutableListOf<Boolean>()
        val stopWatching = observeWindowVisibility(window.lifecycle) { reported += it }

        window.registry.currentState = Lifecycle.State.CREATED // minimised, or sent to the background
        assertEquals(listOf(true, false), reported)

        window.registry.currentState = Lifecycle.State.RESUMED
        stopWatching()
        // Leaving the composition is the other way a session goes off screen: another tab took the
        // window. Reporting it hidden is what stops the server drawing for nobody.
        assertEquals(listOf(true, false, true, false), reported)
    }

    @Test
    fun nothing_is_reported_once_the_watch_has_ended() {
        val window = FakeWindow()
        window.registry.currentState = Lifecycle.State.RESUMED
        val reported = mutableListOf<Boolean>()
        val stopWatching = observeWindowVisibility(window.lifecycle) { reported += it }
        stopWatching()

        window.registry.currentState = Lifecycle.State.CREATED
        window.registry.currentState = Lifecycle.State.RESUMED

        // A session that is no longer on screen must not be un-suppressed by the window it left.
        assertEquals(listOf(true, false), reported)
    }

    /** A window whose lifecycle the test drives; `createUnsafe` skips the main-thread check. */
    private class FakeWindow : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
