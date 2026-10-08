package app.skerry.ui.files

import app.skerry.ui.sftp.TransferDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransferProgressCadenceTest {
    @Test
    fun `a burst of blocks leaves the visible progress stable until the next interval`() {
        var clock = 0L
        val queue = TransferQueue { clock }
        queue.activate(queue.enqueue(TransferDirection.Upload, "large.bin"))
        queue.step("large.bin", 1, 1, 0, 1_000_000)
        val initial = queue.list.single()
        repeat(99) { index ->
            clock++
            queue.step("large.bin", 1, 1, (index + 1) * 100L, 1_000_000)
        }
        assertSame(initial, queue.list.single(), "per-block callbacks must not invalidate the UI")
        clock = 100L
        queue.step("large.bin", 1, 1, 10_000, 1_000_000)
        assertEquals(10_000L, queue.list.single().transferred)
    }

    @Test
    fun `failure keeps the last received bytes even between visible updates`() {
        val queue = TransferQueue { 0L }
        queue.activate(queue.enqueue(TransferDirection.Download, "partial.bin"))
        queue.step("partial.bin", 1, 1, 0, 100)
        queue.step("partial.bin", 1, 1, 42, 100)
        queue.fail("partial.bin", FileTransferFailure.Transfer)
        assertEquals(42L, queue.list.single().transferred)
        assertEquals(42L, queue.list.single().bytesDone)
        assertTrue(queue.list.single().status is TransferStatus.Failed)
    }

    @Test
    fun `completion and a new file publish immediately and do not leak pending bytes`() {
        val queue = TransferQueue { 0L }
        queue.activate(queue.enqueue(TransferDirection.Upload, "a"))
        queue.step("a", 1, 2, 0, 100)
        queue.step("a", 1, 2, 100, 100)
        assertEquals(100L, queue.list.single().transferred)
        queue.fileFinished(100)
        queue.step("b", 2, 2, 0, 200)
        assertEquals("b", queue.list.single().name)
        assertEquals(100L, queue.list.single().bytesDone)
        queue.step("b", 2, 2, 70, 200)
        queue.end(TransferStatus.Done)
        assertEquals(170L, queue.list.single().bytesDone)
    }

    @Test
    fun `a backwards clock cannot freeze visible progress`() {
        var clock = 200L
        val queue = TransferQueue { clock }
        queue.activate(queue.enqueue(TransferDirection.Upload, "a"))
        queue.step("a", 1, 1, 0, 100)
        clock = 100L
        queue.step("a", 1, 1, 50, 100)
        assertEquals(50L, queue.list.single().transferred)
        assertTrue(queue.list.single().elapsedMillis >= 0)
    }
}
