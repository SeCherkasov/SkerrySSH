package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteFramebuffer
import androidx.compose.ui.unit.IntSize
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteRect
import app.skerry.shared.graphics.RemoteDesktopUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
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
    fun close_update_marks_closed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(extraBufferCapacity = 8)
        val session = FakeRemoteDesktop(updates = updates)
        val screen = RemoteDesktopScreenState(session, scope)

        updates.emit(RemoteDesktopUpdate.Closed(cleanExit = true))
        assertTrue(screen.closed)
        assertTrue(screen.cleanExit)
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
}
