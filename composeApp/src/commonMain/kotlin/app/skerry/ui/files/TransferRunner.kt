package app.skerry.ui.files

import app.skerry.shared.files.FileBrowserException
import app.skerry.ui.sftp.TransferDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** One operation the channel has no room for yet: its queue entry, its work, its cleanup. */
private class PendingTransfer(
    val id: Long,
    val onFinally: suspend () -> Unit,
    val block: suspend () -> Unit,
)

/**
 * Runs transfer operations one at a time over the session's single SFTP channel, in the order they
 * were requested: one submitted while another is running waits its turn and starts when the channel
 * frees up.
 *
 * Waiting rather than dropping is the whole point (issue #317). Both mobile entry points hand over a
 * handle the user has already committed to — the SAF document "Save to…" created at the location
 * they chose, the full copy of a picked upload sitting in the app's cache — so an operation dropped
 * on the floor leaves an empty file where the user asked for their data, and a copy nothing ever
 * deletes.
 *
 * Every operation gets its [TransferQueue] entry when it is submitted, so a waiting one is visible
 * and can be taken back ([cancelWaiting]). [onFinally] runs exactly once per operation — after the
 * block on every exit path, or on its own if the operation never gets to run — and under
 * [NonCancellable], because releasing the picker's handle is what it is for and a dying scope is
 * when that matters most.
 *
 * Not thread-safe by design: operations are submitted from UI handlers and the coordinator's scope
 * is confined to the same thread, like [FilePaneController].
 */
internal class TransferRunner(private val scope: CoroutineScope, private val queue: TransferQueue) {

    private val pending = ArrayDeque<PendingTransfer>()
    private var running = false

    /**
     * Submits an operation. [direction] and [name] fill in its queue row before it starts — [name]
     * is what the operation is about, replaced by the file actually moving once it does.
     */
    fun submit(
        direction: TransferDirection,
        name: String,
        onFinally: suspend () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val id = queue.enqueue(direction, name)
        pending += PendingTransfer(id, onFinally, block)
        if (!running) startNext()
    }

    /**
     * Takes the waiting operation with entry [id] back off the queue and releases its handle. No-op
     * if [id] is not waiting — already running (not the user's to cancel), or already finished.
     * Leaves the row to the caller: this is the user dropping it, and dropping means it goes.
     */
    fun cancelWaiting(id: Long) {
        val op = pending.firstOrNull { it.id == id } ?: return
        pending.remove(op)
        release(op.onFinally)
    }

    /**
     * Releases every operation still waiting — the channel is going away, so their turn will never
     * come. The rows are closed as failed rather than dropped: the user asked for them.
     */
    fun releaseWaiting() {
        while (pending.isNotEmpty()) {
            val op = pending.removeFirst()
            queue.abandon(op.id, FileTransferFailure.SessionClosed)
            release(op.onFinally)
        }
    }

    /** Releases a handle no transfer will ever consume (a refused overwrite, say). */
    fun release(handle: suspend () -> Unit) {
        scope.launch(NonCancellable) { runCatching { handle() } }
    }

    /**
     * Starts the next operation, or goes idle when there is none. Anything thrown closes its entry
     * as [TransferStatus.Failed] (named after the step it stopped on) — an `Error` included, since
     * an entry nobody closes is one the queue keeps forever; [CancellationException] propagates. A
     * scope that has already died runs nothing: what is left is released instead, or the handles
     * would sit in a queue that never advances again.
     *
     * [CoroutineStart.ATOMIC] closes the gap between that check and the dispatch: a scope cancelled
     * in between would otherwise skip the body altogether, and with it the `finally` that releases
     * the operation's handle — the leak this class exists to prevent, one dispatch later.
     */
    private fun startNext() {
        if (!scope.isActive) {
            releaseWaiting()
            running = false
            return
        }
        val op = pending.removeFirstOrNull()
        if (op == null) {
            running = false
            return
        }
        running = true
        queue.activate(op.id)
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                op.block()
                // A block can finish normally having already closed its own entry as failed
                // (a move whose transfer went through but whose source delete didn't) — that
                // verdict wins, so the entry is only marked done while it is still open.
                if (queue.hasOpenEntry) queue.end(TransferStatus.Done)
            } catch (e: CancellationException) {
                // A cancelled operation is over too: leaving the entry Active would show a
                // progress bar that never moves again.
                queue.end(TransferStatus.Failed(FileTransferFailure.Transfer))
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Throwable, not Exception: a tree walk deep enough to overflow the stack arrives as
                // an Error, and an Exception-shaped handler lets it past — leaving the entry Active
                // for good, with a progress bar that never moves again and an idle auto-lock that
                // keeps deferring on it. The entry is closed and the error is not rethrown: the
                // stack has already unwound by the time this runs, and taking the window down is not
                // a better answer than a failed row. This closes the row, nothing more — it is not a
                // claim that the process is healthy after every Error.
                val failure = (e as? FileBrowserException)?.failure?.toTransferFailure() ?: FileTransferFailure.Transfer
                queue.end(TransferStatus.Failed(failure))
            } finally {
                withContext(NonCancellable) { runCatching { op.onFinally() } }
                startNext()
            }
        }
    }
}
