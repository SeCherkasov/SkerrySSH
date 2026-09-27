package app.skerry.shared.sync

import app.skerry.shared.io.HostilePeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Each frame a sync server sends is bounded; how many of them the client queues must be too. While
 * the code reading a socket is busy — a signal starts a pull, a viewer is still drawing — whatever
 * arrives waits in memory, and a server that keeps sending would grow that queue until the app runs
 * out of it. The queue has to fill and then stop reading, so the rest waits in TCP on the server.
 */
class WebSocketBackpressureTest {

    private val session = SyncSession(accountId = "a@example.com", accessToken = "t", refreshToken = "r")

    /** Frames the peer got through while its reader was stuck; far below [FLOOD_FRAMES] if the queue is bounded. */
    private fun floodedWhileStuck(peer: HostilePeer, stuckReader: suspend (KtorSyncClient, CompletableDeferred<Unit>) -> Unit): Long =
        peer.use {
            runBlocking {
                val reading = CompletableDeferred<Unit>()
                val job = CoroutineScope(Dispatchers.Default).launch { stuckReader(KtorSyncClient(peer.httpUrl), reading) }
                try {
                    withTimeout(10_000) { reading.await() }
                    peer.settle()
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun `the sync socket stops reading while a signal is still being handled`() {
        val peer = HostilePeer.frameFlood(first = "1", opcode = TEXT, frameBytes = FRAME_BYTES, count = FLOOD_FRAMES)
        val sent = floodedWhileStuck(peer) { client, reading ->
            client.changes(session).collect {
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        assertTrue(sent < FLOOD_FRAMES / 2, "the client queued $sent of $FLOOD_FRAMES frames it was not reading")
    }

    @Test
    fun `a share relay socket stops reading while the viewer is not taking frames`() {
        val peer = HostilePeer.frameFlood(first = "viewers:", opcode = BINARY, frameBytes = FRAME_BYTES, count = FLOOD_FRAMES)
        val sent = floodedWhileStuck(peer) { client, reading ->
            client.joinShare(session, "team-e2e", "share-e2e") {
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        assertTrue(sent < FLOOD_FRAMES / 2, "the client queued $sent of $FLOOD_FRAMES frames it was not reading")
    }

    private companion object {
        const val TEXT = 0x1
        const val BINARY = 0x2

        /** Under the client's 64 KiB frame cap, so each frame on its own is one the client accepts. */
        const val FRAME_BYTES = 32 * 1024

        /** 64 MiB in all: several times what socket buffers hold, small enough for a test heap. */
        const val FLOOD_FRAMES = 2_000
    }
}
