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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import androidx.compose.ui.unit.IntOffset
import app.skerry.shared.graphics.RemoteDesktopCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun region_updates_coalesce_into_one_published_frame() = runTest {
        // A Windows server sends many small updates inside one logical frame; each must reach the
        // pixel mirror at once, but the canvas is invalidated at most once per display frame — the
        // view drains [frameRequests] on its frame clock and calls [publishFrame].
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(2, 1), updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        assertEquals(0, screen.frame)
        repeat(5) { updates.emit(RemoteDesktopUpdate.Region(listOf(RemoteRect(0, 0, 2, 1)))) }
        assertEquals(0, screen.frame, "no redraw per update — the frame clock publishes")

        screen.frameRequests.first() // the five updates conflated into one pending request
        screen.publishFrame()
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
    fun the_same_cursor_shape_instance_is_not_rebuilt_into_a_new_sprite() = runTest {
        // An RDP server switches arrow and I-beam constantly through its pointer cache; the cached
        // slot re-emits the same shape instance, and rebuilding a bitmap for it every time is what
        // F-26 is about.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)
        val arrow = RemoteDesktopUpdate.CursorShape(IntArray(4), 2, 2, 0, 0)
        val beam = RemoteDesktopUpdate.CursorShape(IntArray(1) { -1 }, 1, 1, 0, 0)

        updates.emit(arrow)
        val sprite = screen.cursor
        updates.emit(beam)
        updates.emit(arrow) // the cache slot named again: same instance end to end

        assertSame(sprite, screen.cursor, "a shape already turned into a sprite is reused")
        scope.cancel()
    }

    @Test
    fun a_server_moved_pointer_is_shown_until_the_local_mouse_speaks() = runTest {
        // SetCursorPos: installers and remote apps move the pointer themselves; ignoring it left
        // the sprite where the local mouse was, so the user aims at one place and clicks in
        // another (F-21).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)

        updates.emit(RemoteDesktopUpdate.CursorPosition(17, 23))
        assertEquals(IntOffset(17, 23), screen.serverPointer)

        screen.onPointer(1, 1, 0)
        assertNull(screen.serverPointer, "the local mouse moving takes the cursor back")
        scope.cancel()
    }

    @Test
    fun view_only_without_cursor_handover_skips_the_wasted_handover_and_repaint() = runTest {
        // On RDP setLocalCursor is a documented no-op — the full repaint that followed it was pure
        // waste, and the sprite is the only cursor the protocol has (F-27).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop(
            capabilities = RemoteDesktopCapabilities(
                adjustableQuality = false,
                remoteResize = true,
                cursorHandover = false,
                audio = false,
                clipboard = true,
            ),
        )
        val screen = RemoteDesktopScreenState(session, scope)

        screen.toggleViewOnly()

        assertTrue(screen.viewOnly)
        assertEquals(emptyList(), session.localCursor)
        assertEquals(emptyList(), session.fullUpdates)
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
    fun the_profile_quality_is_applied_at_connect_and_changes_are_reported() = runTest {
        // V-03: without this, every session starts at Auto and the live menu's choice dies with
        // the tab — same persistence shape as remoteResize/vncResizeToWindow.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val applied = mutableListOf<RemoteDesktopQuality>()
        val session = object : FakeRemoteDesktop() {
            override suspend fun setQuality(quality: RemoteDesktopQuality) {
                applied += quality
            }
        }
        val reported = mutableListOf<RemoteDesktopQuality>()
        val screen = RemoteDesktopScreenState(
            session,
            scope,
            qualityInitial = RemoteDesktopQuality.High,
            onQualityChanged = { reported += it },
        )

        assertEquals(RemoteDesktopQuality.High, screen.quality)
        assertEquals(listOf(RemoteDesktopQuality.High), applied, "the profile's choice must reach the session")
        assertEquals(emptyList(), reported, "seeding from the profile is not a change to report")

        screen.applyQuality(RemoteDesktopQuality.Low)

        assertEquals(listOf(RemoteDesktopQuality.Low), reported)
        assertEquals(listOf(RemoteDesktopQuality.High, RemoteDesktopQuality.Low), applied)
        scope.cancel()
    }

    @Test
    fun an_auto_profile_quality_asks_the_session_for_nothing() = runTest {
        // Auto is the wire default; telling the server "Auto" at connect would be noise.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val applied = mutableListOf<RemoteDesktopQuality>()
        val session = object : FakeRemoteDesktop() {
            override suspend fun setQuality(quality: RemoteDesktopQuality) {
                applied += quality
            }
        }

        RemoteDesktopScreenState(session, scope)

        assertEquals(emptyList(), applied)
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

    /**
     * The window manager takes Alt+Tab and the Super key for itself: the press reaches us, the
     * release never does, and the server is left holding a modifier for the rest of the session.
     * Every click then arrives as Alt+click and the remote desktop stops answering the mouse —
     * until the user happens to press that modifier again, which is what made this look like
     * "the mouse works after I switch the keyboard layout".
     *
     * Every input event carries what the local machine really has down, so the state lifts what the
     * server should not be holding.
     */
    @Test
    fun a_modifier_the_local_machine_no_longer_holds_is_lifted_on_the_server() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val alt = RemoteKeyEvent(keySym = 0xFFE9, scancode = 0x38)

        screen.onKey(alt, down = true, modifier = RemoteModifier.Alt)
        advanceUntilIdle()
        assertEquals(listOf(alt to true), session.keys.toList())

        // The next event says nothing is held: the release we never saw has to go out now.
        screen.syncModifiers(RemoteModifiers(ctrl = false, alt = false, shift = false))
        advanceUntilIdle()
        assertEquals(listOf(alt to true, alt to false), session.keys.toList())

        // And only once — a second event with the same state has nothing left to lift.
        screen.syncModifiers(RemoteModifiers(ctrl = false, alt = false, shift = false))
        advanceUntilIdle()
        assertEquals(2, session.keys.size)
        scope.cancel()
    }

    /** A modifier the user really is holding stays down: the sync lifts only what drifted. */
    @Test
    fun a_modifier_still_held_locally_is_left_alone() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val ctrl = RemoteKeyEvent(keySym = 0xFFE3, scancode = 0x1D)

        screen.onKey(ctrl, down = true, modifier = RemoteModifier.Ctrl)
        screen.syncModifiers(RemoteModifiers(ctrl = true, alt = false, shift = false))
        advanceUntilIdle()
        assertEquals(listOf(ctrl to true), session.keys.toList())
        scope.cancel()
    }

    /**
     * The release of a modifier already says the modifier is up, so the reconciliation must leave
     * that one key alone — otherwise every ordinary Shift release goes down the wire twice.
     */
    @Test
    fun the_modifier_of_the_event_being_handled_is_not_reconciled() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val shift = RemoteKeyEvent(keySym = 0xFFE1, scancode = 0x2A)

        screen.onKey(shift, down = true, modifier = RemoteModifier.Shift)
        // What a key-up of Shift looks like: the event no longer reports Shift as held.
        screen.syncModifiers(RemoteModifiers(ctrl = false, alt = false, shift = false), except = RemoteModifier.Shift)
        screen.onKey(shift, down = false, modifier = RemoteModifier.Shift)
        advanceUntilIdle()

        assertEquals(listOf(shift to true, shift to false), session.keys.toList())
        scope.cancel()
    }

    /**
     * Left and right of one modifier are two keys the server holds separately. Releasing one must
     * not lose the record of the other, or a swallowed release for it can no longer be put right —
     * and pressing both is exactly what a layout switcher (Alt+Shift) does.
     */
    @Test
    fun both_sides_of_a_modifier_are_tracked_separately() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val left = RemoteKeyEvent(keySym = 0xFFE9, scancode = 0x38)
        val right = RemoteKeyEvent(keySym = 0xFFEA, scancode = 0x38, extended = true)

        screen.onKey(left, down = true, modifier = RemoteModifier.Alt)
        screen.onKey(right, down = true, modifier = RemoteModifier.Alt)
        screen.onKey(right, down = false, modifier = RemoteModifier.Alt)
        advanceUntilIdle()
        session.keys.clear()

        // The local machine holds neither now — the left one, still down on the server, has to go.
        screen.syncModifiers(RemoteModifiers(ctrl = false, alt = false, shift = false))
        advanceUntilIdle()
        assertEquals(listOf(left to false), session.keys.toList())
        scope.cancel()
    }

    /** View-only sends nothing at all, the reconciliation included: look, don't touch. */
    @Test
    fun view_only_reconciles_nothing() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val alt = RemoteKeyEvent(keySym = 0xFFE9, scancode = 0x38)

        screen.onKey(alt, down = true, modifier = RemoteModifier.Alt)
        advanceUntilIdle()
        session.keys.clear()

        screen.toggleViewOnly()
        screen.syncModifiers(RemoteModifiers(ctrl = false, alt = false, shift = false))
        advanceUntilIdle()
        assertTrue(session.keys.isEmpty(), "a view-only session wrote to the server: ${session.keys}")
        scope.cancel()
    }

    /**
     * One event, two modifiers: the one it reports releasing is left to [RemoteDesktopScreenState.onKey],
     * the one that drifted is lifted here — a layout switcher (Alt+Shift) makes exactly this shape.
     */
    @Test
    fun the_exempt_modifier_and_a_stale_one_are_told_apart() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeRemoteDesktop()
        val screen = RemoteDesktopScreenState(session, scope)
        val ctrl = RemoteKeyEvent(keySym = 0xFFE3, scancode = 0x1D)
        val shift = RemoteKeyEvent(keySym = 0xFFE1, scancode = 0x2A)

        screen.onKey(ctrl, down = true, modifier = RemoteModifier.Ctrl)
        screen.onKey(shift, down = true, modifier = RemoteModifier.Shift)
        advanceUntilIdle()
        session.keys.clear()

        // The Shift key-up: it reports both as released, but Shift's own transition is onKey's.
        screen.syncModifiers(
            RemoteModifiers(ctrl = false, alt = false, shift = false),
            except = RemoteModifier.Shift,
        )
        advanceUntilIdle()
        assertEquals(listOf(ctrl to false), session.keys.toList())
        scope.cancel()
    }
}
