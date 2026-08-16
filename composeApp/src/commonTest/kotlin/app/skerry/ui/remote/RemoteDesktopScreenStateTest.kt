package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteFramebuffer
import androidx.compose.ui.unit.IntSize
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteRect
import app.skerry.shared.graphics.RemoteDesktopUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteDesktopScreenStateTest {

    @Test
    fun a_dead_audio_device_is_visible_on_the_screen_state() = runTest {
        // The mute switch stays on while nothing comes out: only this flag separates "you silenced
        // it" from "the device died", and the panel has nothing else to show.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)

        assertFalse(screen.audioFailed)
        updates.emit(RemoteDesktopUpdate.AudioPlaybackFailing(failing = true))
        assertTrue(screen.audioFailed)
        assertFalse(screen.audioMuted) // the user never touched the switch

        updates.emit(RemoteDesktopUpdate.AudioPlaybackFailing(failing = false))
        assertFalse(screen.audioFailed)
        scope.cancel()
    }

    @Test
    fun region_update_bumps_the_frame_counter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(2, 1), updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        assertEquals(0, screen.frame)
        updates.emit(RemoteDesktopUpdate.Region(listOf(RemoteRect(0, 0, 2, 1))))
        assertEquals(1, screen.frame)
        scope.cancel()
    }

    @Test
    fun region_update_accumulates_pixel_bridge_time_for_the_overlay() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(2, 1), updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        updates.emit(RemoteDesktopUpdate.Region(listOf(RemoteRect(0, 0, 2, 1))))
        assertEquals(1, screen.renderStats.bridgeCount)
        scope.cancel()
    }

    @Test
    fun the_stats_overlay_is_off_until_asked_for() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(), scope)

        assertFalse(screen.showStats)
        screen.toggleStats()
        assertTrue(screen.showStats)
        scope.cancel()
    }

    @Test
    fun resize_update_tracks_the_new_desktop_size() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(2, 1), updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        updates.emit(RemoteDesktopUpdate.Resize(800, 600))
        assertEquals(800, screen.desktopSize.width)
        assertEquals(600, screen.desktopSize.height)
        scope.cancel()
    }

    @Test
    fun close_update_publishes_the_reason_the_server_gave() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        assertNull(screen.close.value)
        updates.emit(RemoteDesktopUpdate.Closed(cleanExit = true, reason = "the user logged off"))
        assertEquals(
            RemoteDesktopUpdate.Closed(cleanExit = true, reason = "the user logged off"),
            screen.close.value,
        )
        scope.cancel()
    }

    @Test
    fun a_throwing_update_flow_closes_the_screen_as_a_drop() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop(updates = flow { error("socket died") })
        val screen = RemoteDesktopScreenState(session, scope)

        assertEquals(RemoteDesktopUpdate.Closed(cleanExit = false), screen.close.value)
        scope.cancel()
    }

    @Test
    fun the_close_the_server_explained_survives_the_read_loop_blowing_up_after_it() = runTest {
        // Teardown throwing behind an orderly close must not replace the server's own words with a
        // bare drop — that is the only text the tab has to explain why it ended.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val explained = RemoteDesktopUpdate.Closed(cleanExit = true, reason = "the account may not log on remotely")
        val session = FakeRemoteDesktop(
            updates = flow {
                emit(explained)
                error("socket died")
            },
        )
        val screen = RemoteDesktopScreenState(session, scope)

        assertEquals(explained, screen.close.value)
        scope.cancel()
    }

    @Test
    fun pointer_key_and_clipboard_are_forwarded() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onPointer(5, 7, 0b001)
        screen.onKey(RemoteKeyEvent(keySym = 0xFF0DL, scancode = 0x1C), down = true)
        screen.onLocalClipboard("hello")

        assertEquals(Triple(5, 7, 0b001), session.pointers.single())
        assertEquals(0xFF0DL, session.keys.single().first.keySym)
        assertEquals(0x1C, session.keys.single().first.scancode)
        assertEquals("hello", session.clipboard.single())
        scope.cancel()
    }

    @Test
    fun muting_the_session_reaches_the_protocol() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        assertFalse(screen.audioMuted)
        screen.toggleAudioMuted()
        assertTrue(screen.audioMuted)
        screen.toggleAudioMuted()

        assertEquals(listOf(true, false), session.audioMutes)
        scope.cancel()
    }

    @Test
    fun unshared_clipboard_moves_in_neither_direction() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val pasted = mutableListOf<String>()
        val screen = RemoteDesktopScreenState(session, scope, onClipboard = { pasted += it })

        screen.toggleClipboardShared()
        screen.onLocalClipboard("secret")
        updates.emit(RemoteDesktopUpdate.ClipboardText("from the server"))

        assertTrue(session.clipboard.isEmpty())
        assertTrue(pasted.isEmpty())
        // Nothing of the remote clipboard is kept either — the panel would otherwise show text the
        // user has just said should not leave the remote machine.
        assertNull(screen.serverClipboard)
        scope.cancel()
    }

    @Test
    fun ctrl_alt_del_presses_all_three_and_releases_them_in_reverse() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.sendCtrlAltDel()

        assertEquals(6, session.keys.size)
        assertEquals(listOf(true, true, true, false, false, false), session.keys.map { it.second })
        val down = session.keys.take(3).map { it.first.scancode }
        assertEquals(down, session.keys.drop(3).map { it.first.scancode }.reversed())
        // The delete key is the extended one on the keypad-less block; without the flag the remote
        // driver reads it as the keypad's period and the secure attention sequence never fires.
        assertTrue(session.keys.first { it.first.scancode == DELETE_SCANCODE }.first.extended)
        scope.cancel()
    }

    @Test
    fun view_only_swallows_ctrl_alt_del() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.toggleViewOnly()
        screen.sendCtrlAltDel()

        assertTrue(session.keys.isEmpty())
        scope.cancel()
    }

    @Test
    fun sharing_the_clipboard_again_lets_text_cross_once_more() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val pasted = mutableListOf<String>()
        val screen = RemoteDesktopScreenState(session, scope, onClipboard = { pasted += it })

        screen.toggleClipboardShared()
        screen.toggleClipboardShared()
        screen.onLocalClipboard("mine")
        updates.emit(RemoteDesktopUpdate.ClipboardText("theirs"))

        assertEquals("mine", session.clipboard.single())
        assertEquals("theirs", pasted.single())
        assertEquals("theirs", screen.serverClipboard)
        scope.cancel()
    }

    @Test
    fun turning_sharing_off_retracts_the_text_that_already_crossed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)

        updates.emit(RemoteDesktopUpdate.ClipboardText("from the server"))
        assertEquals("from the server", screen.serverClipboard)

        screen.toggleClipboardShared()

        // Otherwise the panel keeps offering the remote machine's clipboard after the user said it
        // should stay there.
        assertNull(screen.serverClipboard)
        scope.cancel()
    }

    @Test
    fun cursor_shape_becomes_a_sprite_and_an_empty_one_clears_it() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)

        assertNull(screen.cursor)
        updates.emit(RemoteDesktopUpdate.CursorShape(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2, 1, 1))
        assertEquals(1, screen.cursor?.hotspotX)

        // The server hides the cursor by sending a 0x0 shape — the sprite has to go with it.
        updates.emit(RemoteDesktopUpdate.CursorShape(IntArray(0), 0, 0, 0, 0))
        assertNull(screen.cursor)
        scope.cancel()
    }

    @Test
    fun the_default_system_pointer_drops_the_sprite_and_gives_the_local_one_back() = runTest {
        // RDP's System Pointer Update (SYSPTR_DEFAULT) means "back to the ordinary arrow". Keeping the
        // last sprite there is how an I-beam gets stuck on screen after leaving a text field.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)

        updates.emit(RemoteDesktopUpdate.CursorShape(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2, 1, 1))
        updates.emit(RemoteDesktopUpdate.CursorVisible(true))

        assertNull(screen.cursor)
        assertTrue(screen.systemCursor, "the OS pointer stands in for the shape we no longer have")

        // A shape of its own takes the job back.
        updates.emit(RemoteDesktopUpdate.CursorShape(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2, 1, 1))
        assertFalse(screen.systemCursor)
        scope.cancel()
    }

    @Test
    fun a_hidden_cursor_shows_neither_sprite_nor_local_pointer() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)

        updates.emit(RemoteDesktopUpdate.CursorVisible(true))
        updates.emit(RemoteDesktopUpdate.CursorVisible(false))

        assertNull(screen.cursor)
        assertFalse(screen.systemCursor, "the server asked for no cursor at all, not for ours")
        scope.cancel()
    }

    @Test
    fun view_only_hands_the_cursor_back_to_the_server_and_repaints() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.toggleViewOnly()
        // Nothing drives our pointer now, so the server must paint the cursor where it really is; the
        // full update is what clears the sprite-era framebuffer of its cursor-shaped hole.
        assertEquals(listOf(false), session.localCursor)
        assertEquals(listOf(false), session.fullUpdates)

        screen.toggleViewOnly()
        assertEquals(listOf(false, true), session.localCursor)
        // ...and back, so the cursor the server just painted doesn't stay burnt in next to the sprite.
        assertEquals(listOf(false, false), session.fullUpdates)
        scope.cancel()
    }

    @Test
    fun a_write_to_a_dead_session_does_not_escape_the_launch() = runTest {
        // Every input write races the read loop, so the socket can already be gone. An exception out
        // of a bare launch reaches the default handler and kills the process on Android — the dropped
        // session has to surface through `closed` instead.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = object : FakeRemoteDesktop() {
            override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int): Unit =
                throw IllegalStateException("Socket closed")
            override suspend fun setQuality(quality: RemoteDesktopQuality): Unit = throw IllegalStateException("Socket closed")
            override suspend fun setLocalCursor(enabled: Boolean): Unit = throw IllegalStateException("Socket closed")
        }
        val screen = RemoteDesktopScreenState(session, scope)

        screen.onPointer(1, 1, 0)
        screen.applyQuality(RemoteDesktopQuality.High)
        screen.toggleViewOnly()

        // Reaching here at all is the assertion: an escaping exception would have failed the test.
        assertTrue(screen.viewOnly)
        scope.cancel()
    }

    @Test
    fun remote_resize_follows_the_viewport_after_a_debounce() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        assertFalse(screen.canResizeRemote)
        updates.emit(RemoteDesktopUpdate.RemoteResizeSupported)
        assertTrue(screen.canResizeRemote)

        screen.onViewportSize(IntSize(1280, 720))
        screen.toggleRemoteResize()
        assertTrue(session.desktopSizes.isEmpty()) // debounced, not instant
        advanceUntilIdle()
        assertEquals(1280 to 720, session.desktopSizes.single())
        scope.cancel()
    }

    @Test
    fun rapid_viewport_changes_collapse_into_the_last_request() = runTest {
        // A window drag-resize spews sizes; only the one the user settles on may reach the server.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)
        updates.emit(RemoteDesktopUpdate.RemoteResizeSupported)
        screen.toggleRemoteResize()

        screen.onViewportSize(IntSize(100, 100))
        screen.onViewportSize(IntSize(200, 200))
        screen.onViewportSize(IntSize(300, 300))
        advanceUntilIdle()
        assertEquals(listOf(300 to 300), session.desktopSizes)
        scope.cancel()
    }

    @Test
    fun no_request_when_the_viewport_already_matches_the_desktop() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(2, 1), updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)
        updates.emit(RemoteDesktopUpdate.RemoteResizeSupported)

        screen.onViewportSize(IntSize(2, 1))
        screen.toggleRemoteResize()
        advanceUntilIdle()
        assertTrue(session.desktopSizes.isEmpty())
        scope.cancel()
    }

    @Test
    fun server_answering_with_a_different_size_does_not_retrigger_a_request() = runTest {
        // Many servers clamp a requested size to a supported mode. The answer arriving as a Resize
        // must not bounce back as another SetDesktopSize — that would be a client↔server resize loop.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)
        updates.emit(RemoteDesktopUpdate.RemoteResizeSupported)

        screen.onViewportSize(IntSize(1000, 700))
        screen.toggleRemoteResize()
        advanceUntilIdle()
        assertEquals(listOf(1000 to 700), session.desktopSizes)

        updates.emit(RemoteDesktopUpdate.Resize(1024, 704))
        advanceUntilIdle()
        assertEquals(listOf(1000 to 700), session.desktopSizes)
        scope.cancel()
    }

    @Test
    fun initial_remote_resize_kicks_in_when_the_server_advertises_support() = runTest {
        // Restoring the saved per-host flag: the session starts with the toggle on, and the resize
        // must fire as soon as the server turns out to support it — without any user interaction.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope, remoteResizeInitial = true)

        screen.onViewportSize(IntSize(1280, 720))
        updates.emit(RemoteDesktopUpdate.RemoteResizeSupported)
        advanceUntilIdle()
        assertEquals(listOf(1280 to 720), session.desktopSizes)
        scope.cancel()
    }

    @Test
    fun toggling_remote_resize_reports_to_the_callback() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val reported = mutableListOf<Boolean>()
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(), scope, onRemoteResizeChanged = { reported += it })

        screen.toggleRemoteResize()
        screen.toggleRemoteResize()
        assertEquals(listOf(true, false), reported)
        scope.cancel()
    }

    @Test
    fun turning_remote_resize_off_cancels_the_pending_request() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)
        updates.emit(RemoteDesktopUpdate.RemoteResizeSupported)

        screen.toggleRemoteResize()
        screen.onViewportSize(IntSize(1280, 720))
        screen.toggleRemoteResize() // off again before the debounce fires
        advanceUntilIdle()
        assertTrue(session.desktopSizes.isEmpty())
        scope.cancel()
    }

    @Test
    fun a_window_going_off_screen_is_reported_to_the_session() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.setVisible(false)
        screen.setVisible(true)

        assertEquals(listOf(false, true), session.visibility)
        scope.cancel()
    }

    @Test
    fun a_session_starts_visible_and_says_so_only_once_it_changes() = runTest {
        // The state is the client's half of the protocol: the server was told nothing yet and is
        // drawing, so repeating "visible" is a PDU that buys nothing — and a hidden window reported
        // twice (window minimised, then the tab left the screen) must not un-suppress anything.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)

        screen.setVisible(true)
        assertTrue(session.visibility.isEmpty(), "a session that was never hidden has nothing to report")

        screen.setVisible(false)
        screen.setVisible(false)
        assertEquals(listOf(false), session.visibility)
        scope.cancel()
    }

    @Test
    fun visibility_reports_reach_the_session_in_the_order_they_were_made() = runTest {
        // Minimise and restore in quick succession and the two writes race: each is its own
        // coroutine on a multi-threaded scope. If "back on screen" overtakes "hidden", the server
        // ends up suppressed while the client has already recorded the session as visible — and the
        // record is what stops any later report from putting it right.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = object : FakeRemoteDesktop() {
            override suspend fun setOutputVisible(visible: Boolean) {
                // A slow first write is what gives the second one the chance to overtake it.
                if (!visible) delay(50)
                super.setOutputVisible(visible)
            }
        }
        val screen = RemoteDesktopScreenState(session, scope)

        screen.setVisible(false)
        screen.setVisible(true)
        advanceUntilIdle()

        assertEquals(listOf(false, true), session.visibility)
        scope.cancel()
    }

    @Test
    fun a_visibility_report_to_a_dead_session_does_not_escape_the_launch() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = object : FakeRemoteDesktop() {
            override suspend fun setOutputVisible(visible: Boolean): Unit = throw IllegalStateException("Socket closed")
        }
        val screen = RemoteDesktopScreenState(session, scope)

        screen.setVisible(false)

        // Reaching here at all is the assertion: the window is minimised on a session whose socket
        // has already gone, and an exception out of the bare launch kills the process on Android.
        assertTrue(session.visibility.isEmpty())
        scope.cancel()
    }

    @Test
    fun server_clipboard_reaches_the_callback() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val received = mutableListOf<String>()
        RemoteDesktopScreenState(session, scope, onClipboard = { received += it })

        updates.emit(RemoteDesktopUpdate.ClipboardText("copied"))
        assertEquals("copied", received.single())
        scope.cancel()
    }

    private companion object {
        const val DELETE_SCANCODE = 0x53
    }
}
