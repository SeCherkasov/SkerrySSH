package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteKeyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteDesktopReconnectTest {
    @Test
    fun reconnect_preserves_local_restrictions_even_after_a_failed_attempt() = runTest {
        for (failFirstRetry in listOf(false, true)) {
            val updates = MutableSharedFlow<RemoteDesktopUpdate>()
            val old = FakeRemoteDesktop(updates = updates)
            val fresh = FakeRemoteDesktop()
            var opens = 0
            val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
            controller.connect {
                when (opens++) {
                    0 -> old
                    1 -> if (failFirstRetry) error("retry failed") else fresh
                    else -> fresh
                }
            }
            advanceUntilIdle()
            val screen = assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen
            screen.toggleClipboardShared()
            screen.toggleViewOnly()
            updates.emit(RemoteDesktopUpdate.Closed(false))
            advanceUntilIdle()
            controller.reconnect()
            advanceUntilIdle()
            if (failFirstRetry) {
                assertIs<RemoteDesktopUiState.Error>(controller.uiState)
                controller.reconnect()
                advanceUntilIdle()
            }
            val replacement = assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen
            assertFalse(replacement.clipboardShared, "reconnect must retain the user's clipboard restriction")
            assertTrue(replacement.viewOnly, "reconnect must retain the user's input restriction")
            replacement.onLocalClipboard("private local text")
            replacement.onKey(RemoteKeyEvent(2, 2), true)
            replacement.onPointer(1, 0, 1)
            advanceUntilIdle()
            assertTrue(fresh.clipboard.isEmpty())
            assertTrue(fresh.keys.isEmpty())
            assertTrue(fresh.pointers.isEmpty())
            controller.disconnect()
            advanceUntilIdle()
        }
    }

    @Test
    fun reconnect_opens_a_fresh_session_and_old_teardown_cannot_close_it() = runTest {
        val updates = MutableSharedFlow<RemoteDesktopUpdate>()
        val finishClose = CompletableDeferred<Unit>()
        val old = object : FakeRemoteDesktop(updates = updates) {
            override suspend fun close() {
                finishClose.await()
                super.close()
            }
        }
        val fresh = FakeRemoteDesktop()
        var opens = 0
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { if (opens++ == 0) old else fresh }
        advanceUntilIdle()
        val oldScreen = assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen
        updates.emit(RemoteDesktopUpdate.Closed(true, "idle timeout"))
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
        assertEquals(1, opens, "closure must not reconnect automatically")

        controller.reconnect()
        controller.reconnect()
        advanceUntilIdle()
        val newScreen = assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen
        assertEquals(2, opens)
        oldScreen.onKey(RemoteKeyEvent(1, 1), true)
        newScreen.onKey(RemoteKeyEvent(2, 2), true)
        finishClose.complete(Unit)
        advanceUntilIdle()
        assertTrue(old.closed)
        assertFalse(fresh.closed)
        assertTrue(old.keys.isEmpty())
        assertEquals(2L, fresh.keys.single().first.keySym)
        controller.disconnect()
        advanceUntilIdle()
        controller.reconnect()
        advanceUntilIdle()
        assertEquals(2, opens, "closing the tab must clear its reconnect request")
    }

    @Test
    fun a_queued_old_close_cannot_disconnect_the_replacement_screen() = runTest {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(dispatcher)
        val controller = RemoteDesktopController(scope) { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) }
        val updates = MutableSharedFlow<RemoteDesktopUpdate>()
        val old = FakeRemoteDesktop(updates = updates)
        val fresh = FakeRemoteDesktop()
        try {
            controller.connect { old }
            dispatcher.runLast()
            updates.emit(RemoteDesktopUpdate.Closed(true)) // queues the old screen's transition
            controller.disconnect()
            controller.connect { fresh }
            dispatcher.runLast() // new connect completes before the queued old transition
            val screen = assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen
            dispatcher.drain()
            assertSame(screen, assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen)
            assertFalse(fresh.closed)
        } finally {
            controller.disconnect()
            dispatcher.drain()
            scope.cancel()
        }
    }

    @Test
    fun a_cancelled_connect_releases_a_session_returned_by_non_cancellable_setup() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeRemoteDesktop()
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect {
            withContext(NonCancellable) { gate.await() }
            session
        }
        runCurrent()
        controller.disconnect()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(session.closed)
        assertIs<RemoteDesktopUiState.Connecting>(controller.uiState)
    }

    @Test
    fun a_cancelled_old_connect_failure_cannot_release_the_new_session() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fresh = FakeRemoteDesktop()
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect {
            withContext(NonCancellable) {
                gate.await()
                error("late handshake failure")
            }
        }
        runCurrent()
        controller.disconnect()
        controller.connect { fresh }
        runCurrent()
        val screen = assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(screen, assertIs<RemoteDesktopUiState.Connected>(controller.uiState).screen)
        assertFalse(fresh.closed)
        controller.disconnect()
        advanceUntilIdle()
    }
}

/** Deliberately delivers a newer main-scope event before an already-dispatched close callback. */
private class QueuedDispatcher : CoroutineDispatcher() {
    private val pending = mutableListOf<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending += block }
    fun runLast() = pending.removeAt(pending.lastIndex).run()
    fun drain() { while (pending.isNotEmpty()) pending.removeAt(0).run() }
}
