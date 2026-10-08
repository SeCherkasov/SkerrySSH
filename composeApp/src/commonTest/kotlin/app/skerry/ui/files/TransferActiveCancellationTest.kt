package app.skerry.ui.files

import app.skerry.ui.sftp.TransferDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferActiveCancellationTest {
    @Test
    fun `cancelling active work releases its handle before starting the next operation`() = runTest {
        val queue = TransferQueue { 0L }
        val runner = TransferRunner(scope(), queue)
        val cleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        runner.submit(TransferDirection.Upload, "first", onFinally = {
            events += "cleanup-start"
            cleanup.await()
            events += "cleanup-end"
        }) { awaitCancellation() }
        runner.submit(TransferDirection.Upload, "next") { events += "next" }

        runner.cancel(queue.list.first().id)
        advanceUntilIdle()
        assertEquals(listOf("cleanup-start"), events)
        assertEquals(TransferStatus.Waiting, queue.list.last().status)
        cleanup.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("cleanup-start", "cleanup-end", "next"), events)
        assertEquals(listOf(TransferStatus.Cancelled, TransferStatus.Done), queue.list.map { it.status })
        assertFalse(queue.hasWork)
    }

    @Test
    fun `active picked download is discarded once and never finalized`() = runTest {
        val r = rig(remote = remoteFake().apply { transferGate = CompletableDeferred() })
        val target = FakeDownloadTarget("r.txt", "/staging/r.txt")
        r.coordinator.downloadToTarget(r.remote.entry("r.txt"), target)
        val id = r.coordinator.queue.single().id
        r.coordinator.dismissTransfer(id)
        r.coordinator.dismissTransfer(id)
        advanceUntilIdle()
        assertEquals(0, target.finalizes)
        assertEquals(1, target.discards)
        assertFalse(r.coordinator.writeInFlight)
    }

    @Test
    fun `cancelled move keeps the source and stops the rest of the batch`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10; transferGate = CompletableDeferred() }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))
        r.coordinator.moveSelection(fromLocal = true)
        val id = r.coordinator.queue.single().id
        r.coordinator.dismissTransfer(id)
        advanceUntilIdle()
        assertEquals(TransferStatus.Cancelled, r.coordinator.queue.single().status)
        assertTrue(r.localFake.stat("$LHOME/a.txt") != null)
        assertTrue(r.localFake.stat("$LHOME/b.txt") != null)
        assertNull(remote.stat("$RHOME/b.txt"))
        assertFalse(r.coordinator.writeInFlight)
    }

    @Test
    fun `stale cancel click does not stop the next active operation`() = runTest {
        val queue = TransferQueue { 0L }
        val runner = TransferRunner(scope(), queue)
        runner.submit(TransferDirection.Upload, "first") { awaitCancellation() }
        val first = queue.list.single().id
        val finish = CompletableDeferred<Unit>()
        runner.submit(TransferDirection.Upload, "next") { finish.await() }
        runner.cancel(first)
        advanceUntilIdle()
        runner.cancel(first)
        assertEquals(TransferStatus.Active, queue.list.last().status)
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferStatus.Done, queue.list.last().status)
    }
}
