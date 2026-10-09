package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Read/input lifetime of one screen. Ending the read stream or a failed write is terminal. */
internal class RemoteDesktopLifetime(
    private val session: RemoteDesktopSession,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
) {
    val input = RemoteInputActor(session)
    private var inputJob: Job? = null
    private val closed = MutableStateFlow<RemoteDesktopUpdate.Closed?>(null)
    val close = closed.asStateFlow()

    fun start(onUpdate: (RemoteDesktopUpdate) -> Unit) {
        if (close.value != null) return
        inputJob = scope.launch {
            try {
                input.run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                markClosed(RemoteDesktopUpdate.Closed(false))
            } finally {
                if (currentCoroutineContext().isActive) markClosed(RemoteDesktopUpdate.Closed(false))
            }
        }
        scope.launch {
            try {
                session.updates.takeWhile { update ->
                    onUpdate(update)
                    close.value == null
                }.collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                markClosed(RemoteDesktopUpdate.Closed(false))
            } finally {
                // A stream that ended without Closed still has no live reader. Parent cancellation
                // is local teardown and must not masquerade as a peer drop.
                if (currentCoroutineContext().isActive) markClosed(RemoteDesktopUpdate.Closed(false))
            }
        }
    }

    fun markClosed(update: RemoteDesktopUpdate.Closed) {
        // First terminal event wins, preserving an explicit reason through subsequent teardown.
        if (closed.compareAndSet(null, update)) {
            input.stop()
            inputJob?.cancel()
            onClose()
        }
    }

    /** A failed write must end the screen even while the read loop is still waiting. */
    fun send(block: suspend () -> Unit): Job = scope.launch {
        if (close.value != null) return@launch
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markClosed(RemoteDesktopUpdate.Closed(false))
        }
    }
}
