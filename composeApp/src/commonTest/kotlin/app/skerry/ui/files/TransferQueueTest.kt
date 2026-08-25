package app.skerry.ui.files

import app.skerry.ui.sftp.TransferDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The queue's own contract, driven directly rather than through a transfer: what an entry says
 * while it runs, what closes it, what the single-line view reads off it, and what dismissing does.
 * The clock is injected, so elapsed time is exact.
 */
class TransferQueueTest {

    private var clock = 1_000L
    private fun queue() = TransferQueue { clock }

    /** Opens an entry and starts it right away — the shape of an operation that never had to wait. */
    private fun TransferQueue.start(direction: TransferDirection, name: String = "") {
        activate(enqueue(direction, name))
    }

    @Test
    fun `an opened entry is the running one until it is closed`() {
        val queue = queue()
        assertFalse(queue.hasOpenEntry)

        queue.start(TransferDirection.Upload)
        assertTrue(queue.hasOpenEntry)

        queue.end(TransferStatus.Done)
        assertFalse(queue.hasOpenEntry)
    }

    @Test
    fun `progress lands on the running entry, bytes and time accumulate over the operation`() {
        val queue = queue()
        queue.start(TransferDirection.Upload)
        queue.step("a.txt", index = 1, count = 2, transferred = 4, total = 10)
        queue.fileFinished(10)
        clock = 3_000L
        queue.step("b.txt", index = 2, count = 2, transferred = 5, total = 20)

        val entry = queue.list.single()
        assertEquals("b.txt", entry.name)
        assertEquals(2, entry.fileIndex)
        assertEquals(2, entry.fileCount)
        assertEquals(5, entry.transferred)
        assertEquals(20, entry.total)
        assertEquals(15, entry.bytesDone, "the finished file's bytes plus what the current one moved")
        assertEquals(2_000, entry.elapsedMillis)
    }

    @Test
    fun `the single-line state follows the newest entry, not the newest failure`() {
        val queue = queue()
        queue.start(TransferDirection.Upload)
        queue.fail("a.txt", FileTransferFailure.Transfer)
        assertIs<TransferState.Failed>(queue.latest)

        queue.start(TransferDirection.Upload)
        queue.step("b.txt", 1, 1, 0, 10)
        val active = assertIs<TransferState.Active>(queue.latest)
        assertEquals("b.txt", active.name)

        queue.end(TransferStatus.Done)
        assertEquals(TransferState.Idle, queue.latest, "a finished transfer is nothing to report")
    }

    @Test
    fun `an empty queue has nothing to say`() {
        assertEquals(TransferState.Idle, queue().latest)
    }

    @Test
    fun `closing an already closed entry leaves its verdict alone`() {
        // The coordinator's blocks may close their own entry as failed and then return normally;
        // the generic "done" that follows must not overwrite the failure.
        val queue = queue()
        queue.start(TransferDirection.Download)
        queue.fail("r.txt", FileTransferFailure.DeleteSource)

        queue.end(TransferStatus.Done)

        val status = assertIs<TransferStatus.Failed>(queue.list.single().status)
        assertEquals(FileTransferFailure.DeleteSource, status.failure)
    }

    @Test
    fun `entries keep distinct ids in the order they were opened`() {
        val queue = queue()
        repeat(3) {
            queue.start(TransferDirection.Upload)
            queue.end(TransferStatus.Done)
        }
        val ids = queue.list.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `only the last few finished entries are kept, oldest evicted first`() {
        val queue = queue()
        val ids = mutableListOf<Long>()
        repeat(MAX_COMPLETED_TRANSFERS + 2) {
            queue.start(TransferDirection.Upload)
            queue.end(TransferStatus.Done)
            ids += queue.list.last().id
        }
        assertEquals(ids.takeLast(MAX_COMPLETED_TRANSFERS), queue.list.map { it.id })
    }

    @Test
    fun `dismissing by id refuses to drop a running entry`() {
        val queue = queue()
        queue.start(TransferDirection.Upload)
        val id = queue.list.single().id

        queue.dismiss(id)

        assertEquals(1, queue.list.size)
    }
}
