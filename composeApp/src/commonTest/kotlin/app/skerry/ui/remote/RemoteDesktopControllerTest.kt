package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.ui.vnc.VncFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteDesktopControllerTest {

    @Test
    fun connect_transitions_to_connected_with_a_screen() = runTest {
        val session = FakeRemoteDesktop()
        val controller = RemoteDesktopController(this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        assertTrue(controller.uiState is RemoteDesktopUiState.Connecting)
        controller.connect { session }
        advanceUntilIdle()

        val state = controller.uiState
        assertTrue(state is RemoteDesktopUiState.Connected)
        assertEquals("fake-desktop", state.screen.serverName)

        controller.disconnect()
    }

    @Test
    fun connect_failure_becomes_error() = runTest {
        val controller = RemoteDesktopController(this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        controller.connect { throw IllegalStateException("refused") }
        advanceUntilIdle()

        val state = controller.uiState
        assertTrue(state is RemoteDesktopUiState.Error)
        assertEquals(VncFailure.Other, state.failure)
        assertEquals("refused", state.detail)
    }

    @Test
    fun a_server_side_close_becomes_disconnected_with_the_reason_it_gave() = runTest {
        // The path a dropped session actually takes: nothing the user did, so the frozen frame and
        // the server's reason are all the screen has left to show.
        // Replayed: the close is delivered whenever the screen's collector starts, so the test
        // does not depend on the order the two coroutines happen to be resumed in.
        val updates = MutableSharedFlow<RemoteDesktopUpdate>(replay = 1)
        val session = FakeRemoteDesktop(updates = updates)
        val controller = RemoteDesktopController(this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        controller.connect { session }
        advanceUntilIdle()
        updates.emit(RemoteDesktopUpdate.Closed(cleanExit = false, reason = "the server went away"))
        advanceUntilIdle()

        val state = controller.uiState
        assertTrue(state is RemoteDesktopUiState.Disconnected, "a dropped session stayed on screen as connected")
        assertEquals(false, state.cleanExit)
        assertEquals("the server went away", state.reason)
    }

    @Test
    fun disconnect_while_connecting_never_shows_connected() = runTest {
        // Closing the tab while the handshake is still running: the session that arrives afterwards
        // belongs to nobody, and a screen that flips to connected behind the user is worse.
        val gate = CompletableDeferred<Unit>()
        val session = FakeRemoteDesktop()
        val controller = RemoteDesktopController(this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        controller.connect {
            gate.await()
            session
        }
        controller.disconnect()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(controller.uiState is RemoteDesktopUiState.Connecting)
    }

    @Test
    fun disconnect_closes_the_session() = runTest {
        val session = FakeRemoteDesktop()
        val controller = RemoteDesktopController(this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        controller.connect { session }
        advanceUntilIdle()
        controller.disconnect()
        advanceUntilIdle()

        assertTrue(session.closed)
        assertTrue(controller.uiState is RemoteDesktopUiState.Connecting)
    }
}
