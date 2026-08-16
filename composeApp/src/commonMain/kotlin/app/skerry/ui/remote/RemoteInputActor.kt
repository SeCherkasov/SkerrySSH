package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteKeyEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * The one input writer per session (F-10): draining a single channel is what gives input the order
 * it was made in — the fire-and-forget launches this replaced raced each other across dispatcher
 * threads, so a key-up could overtake its key-down and a click could run ahead of the move that
 * aimed it. It is also the only place moves coalesce (F-11): a run of pure moves collapses to the
 * freshest and is paced to [MOVE_INTERVAL], while a queued click, key or wheel is never made to
 * wait behind that pacing — the pending move goes out first, so a click always lands at a fresh
 * position.
 *
 * Owned and driven by [RemoteDesktopScreenState]; extracted because it is a self-contained unit
 * with state of its own ([actorLastMask], [lastMoveAt]) that nothing else may touch.
 */
internal class RemoteInputActor(private val session: RemoteDesktopSession) {

    sealed interface Write
    data class PointerWrite(val x: Int, val y: Int, val mask: Int) : Write
    data class KeyWrite(val event: RemoteKeyEvent, val down: Boolean) : Write
    data class LockWrite(val keys: LockKeys) : Write

    private val input = Channel<Write>(Channel.UNLIMITED)

    /** Enqueue one write; order of submission is the order the transport will see. */
    fun submit(write: Write) {
        input.trySend(write)
    }

    /** The drain loop; runs until the session scope is cancelled. */
    suspend fun run() {
        var pending: Write? = null
        while (true) {
            val event = pending ?: input.receive()
            pending = when (event) {
                is PointerWrite ->
                    if (event.mask == actorLastMask) {
                        sendCollapsedMove(event)
                    } else {
                        write { session.sendPointer(event.x, event.y, event.mask) }
                        // Wheel bits are edges, not state: the mask a later move repeats has none.
                        actorLastMask = event.mask and BUTTONS_ONLY
                        null
                    }

                is KeyWrite -> {
                    write { session.sendKey(event.event, event.down) }
                    null
                }

                is LockWrite -> {
                    write { session.syncLockKeys(event.keys.scroll, event.keys.num, event.keys.caps) }
                    null
                }
            }
        }
    }

    private var actorLastMask = 0
    private var lastMoveAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Send the freshest of the queued pure moves, pacing the stream to [MOVE_INTERVAL] — but only
     * while nothing else waits. Returns the first non-move it ran into, which the caller handles
     * next, so a click is delivered right after the move that positioned it and is never delayed.
     */
    private suspend fun sendCollapsedMove(first: PointerWrite): Write? {
        var move = first
        var interrupt: Write? = null
        fun collapseQueuedMoves() {
            while (interrupt == null) {
                val queued = input.tryReceive().getOrNull() ?: return
                if (queued is PointerWrite && queued.mask == actorLastMask) move = queued else interrupt = queued
            }
        }
        collapseQueuedMoves()
        if (interrupt == null) {
            val since = lastMoveAt?.elapsedNow()
            if (since != null && since < MOVE_INTERVAL) {
                delay(MOVE_INTERVAL - since)
                collapseQueuedMoves()
            }
        }
        lastMoveAt = TimeSource.Monotonic.markNow()
        write { session.sendPointer(move.x, move.y, move.mask) }
        return interrupt
    }

    /**
     * Swallow-the-write discipline: every write races the read loop, so the socket can already be
     * dead — the dropped session surfaces through the session close, and a failed input write has
     * nothing to add to the imminent "Connection lost".
     */
    private suspend fun write(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** Floor between two pure moves: ~120/s, about what a mature client sends (F-11). */
        val MOVE_INTERVAL = 8.milliseconds

        /** The state-carrying bits of the RFB mask; wheel bits (3..6) are edges and never repeat. */
        const val BUTTONS_ONLY = 0b110000111
    }
}
