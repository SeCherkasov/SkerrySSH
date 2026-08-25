package app.skerry.ui.files

import app.skerry.ui.sftp.TransferDirection
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * How an operation's queue entry is closed, whatever the operation died of.
 *
 * The queue is the only place a user can see that something they asked for is over. An entry left
 * Active is worse than a failed one: the strip shows a progress bar that will never move again, the
 * vault's idle auto-lock keeps deferring on it ([TransferCoordinator.writeInFlight]), and nothing
 * ever comes back to close it. A tree walk deep enough to overflow the stack throws
 * `StackOverflowError`, which is an `Error` and not an `Exception`, so an `Exception`-shaped
 * handler lets it past (#306). Closing the row is all this claims: the stack has unwound by the
 * time the handler runs, so the row is honest, but nothing here says the JVM is well after an
 * `Error` of any kind.
 */
class TransferRunnerTest {

    @Test
    fun `an operation that dies on an Error still closes its entry`() = runTest {
        val queue = TransferQueue { 0L }
        val runner = TransferRunner(scope(), queue)

        runner.submit(TransferDirection.Download, "tree") { throw StackOverflowError() }
        advanceUntilIdle()

        assertEquals(TransferStatus.Failed(FileTransferFailure.Transfer), queue.list.single().status)
        assertFalse(queue.hasWork, "the queue still owes work it will never do")
    }

    @Test
    fun `the operation behind it still gets its turn`() = runTest {
        // The failing entry is closed by the same `finally` that starts the next one; a handler that
        // lets the Error past leaves the entry open, and the queue believes one is still running.
        val queue = TransferQueue { 0L }
        val runner = TransferRunner(scope(), queue)

        runner.submit(TransferDirection.Download, "tree") { throw StackOverflowError() }
        runner.submit(TransferDirection.Upload, "after") { }
        advanceUntilIdle()

        assertEquals(
            listOf(TransferStatus.Failed(FileTransferFailure.Transfer), TransferStatus.Done),
            queue.list.map { it.status },
        )
    }
}
