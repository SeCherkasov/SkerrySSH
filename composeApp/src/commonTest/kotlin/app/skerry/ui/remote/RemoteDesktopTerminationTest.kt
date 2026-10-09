package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteDesktopTerminationTest {
    @Test
    fun update_stream_completion_disconnects_and_releases_the_session() = runTest {
        val session = FakeRemoteDesktop(updates = emptyFlow())
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { session }
        advanceUntilIdle()

        assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
        assertTrue(session.closed)
    }

    @Test
    fun failed_pointer_write_disconnects_even_when_reads_are_still_waiting() = runTest {
        val session = object : FakeRemoteDesktop() {
            override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int): Unit = error("write failed")
        }
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { session }
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen.onPointer(1, 0, 1)
        advanceUntilIdle()

        assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
        assertTrue(session.closed)
    }

    @Test
    fun failed_clipboard_write_disconnects_even_when_reads_are_still_waiting() = runTest {
        val session = object : FakeRemoteDesktop() {
            override suspend fun sendClipboardText(text: String): Unit = error("write failed")
        }
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { session }
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen.onLocalClipboard("text")
        advanceUntilIdle()

        assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
        assertTrue(session.closed)
    }

    @Test
    fun closed_screen_refuses_input_and_late_server_clipboard() = runTest {
        val updates = MutableSharedFlow<RemoteDesktopUpdate>()
        val session = FakeRemoteDesktop(updates = updates)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val received = mutableListOf<String>()
        try {
            val screen = RemoteDesktopScreenState(session, scope, onClipboard = { received += it })
            updates.emit(RemoteDesktopUpdate.Closed(true, "logged off"))
            screen.onPointer(1, 0, 1)
            screen.onKey(RemoteKeyEvent(1, 1), true)
            screen.onLocalClipboard("text")
            updates.emit(RemoteDesktopUpdate.ClipboardText("late"))
            advanceUntilIdle()

            assertTrue(session.pointers.isEmpty())
            assertTrue(session.keys.isEmpty())
            assertTrue(session.clipboard.isEmpty())
            assertTrue(received.isEmpty())
            assertEquals(RemoteDesktopUpdate.Closed(true, "logged off"), screen.close.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun idle_and_hidden_session_stays_connected_without_new_frames() = runTest {
        val session = FakeRemoteDesktop()
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { session }
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen.setVisible(false)
        advanceTimeBy(24 * 60 * 60 * 1000L)
        advanceUntilIdle()

        assertIs<RemoteDesktopUiState.Connected>(controller.uiState)
        assertFalse(session.closed)
        controller.disconnect()
        advanceUntilIdle()
    }

    @Test
    fun cancelling_the_session_scope_is_not_a_remote_drop() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(), scope)
        scope.cancel()
        advanceUntilIdle()
        assertNull(screen.close.value)
    }
}
