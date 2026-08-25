package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sync.nowMillis

/** Where a queue entry stands: waiting its turn, moving bytes, finished, or stopped by a failure. */
sealed interface TransferStatus {
    /** Requested while the channel was busy; starts when the operation ahead of it ends. */
    data object Waiting : TransferStatus
    data object Active : TransferStatus
    data object Done : TransferStatus
    data class Failed(val failure: FileTransferFailure) : TransferStatus
}

/** Whether the operation is over — its row is history, and the user's to clear. */
val TransferStatus.isFinished: Boolean
    get() = this == TransferStatus.Done || this is TransferStatus.Failed

/**
 * One line of the transfer queue: a single operation (F5/F6, a picked upload, a download to a
 * target), which may cover several files. [name] is the file currently moving ([fileIndex] of
 * [fileCount]), [transferred] of [total] its bytes ([total] = 0 when the source doesn't report a
 * size). [bytesDone] and [elapsedMillis] count the whole operation and are what the speed is read
 * off ([app.skerry.ui.sftp.transferSpeed]).
 */
data class TransferEntry(
    val id: Long,
    val direction: TransferDirection,
    val name: String,
    val fileIndex: Int,
    val fileCount: Int,
    val transferred: Long,
    val total: Long,
    val bytesDone: Long,
    val elapsedMillis: Long,
    val status: TransferStatus,
)

/**
 * How many finished entries the queue keeps. The strip is a live view of what is moving, with just
 * enough history to see how the last transfers ended — not a transfer log.
 */
const val MAX_COMPLETED_TRANSFERS = 3

/**
 * What the transfer strip lists: what is waiting, what is running, and the last few finished ones.
 * One entry per operation, opened when the operation is requested ([enqueue]) and closed once, by
 * whoever runs it — [TransferRunner] does that for every exit path, so no operation can leave an
 * entry open.
 *
 * [TransferRunner] runs operations one at a time, so at most one entry is [TransferStatus.Active];
 * every lookup here relies on that. Waiting entries sit *after* it — they were requested later —
 * which is why the running one is looked up rather than read off the end. [now] is the wall clock
 * behind the entries' elapsed time (hence the speed), injected so tests can pin it.
 */
@Stable
class TransferQueue(private val now: () -> Long = ::nowMillis) {

    private val entries = mutableStateListOf<TransferEntry>()

    /** The queue, oldest first. */
    val list: List<TransferEntry> get() = entries

    /**
     * State of the *latest* operation, for a single-line view (the mobile Files card). Reading the
     * latest entry rather than the latest failing one is the point — a transfer that failed and was
     * then retried successfully must stop showing an error, or the user retries something that
     * already went through.
     */
    val latest: TransferState
        get() {
            entries.lastOrNull { it.status == TransferStatus.Active }?.let {
                return TransferState.Active(it.name, it.direction, it.fileIndex, it.fileCount, it.transferred, it.total)
            }
            // Nothing is moving: what is left to report is how the last operation ended. That can
            // be one that never ran — an entry abandoned by [abandon] outlives the transfer it was
            // queued behind, and its verdict is the newer one.
            val last = entries.lastOrNull { it.status.isFinished } ?: return TransferState.Idle
            val failed = last.status as? TransferStatus.Failed ?: return TransferState.Idle
            return TransferState.Failed(last.id, last.name, failed.failure)
        }

    /** Whether an entry is still running — the caller's cue that it is theirs to close. */
    val hasOpenEntry: Boolean get() = entries.any { it.status == TransferStatus.Active }

    /** Whether any operation is running or still waiting for its turn on the channel. */
    val hasWork: Boolean get() = entries.any { !it.status.isFinished }

    private var nextEntryId = 1L

    /** When the running operation started, and how many bytes its finished files already moved. */
    private var startedAt = 0L
    private var bytesDone = 0L

    /**
     * Opens the entry of a requested operation and returns its id. The entry starts [Waiting]:
     * every operation goes through the queue, whether or not it has to wait there. [name] is what
     * the operation is about — the picked file, or the first of a selection — so a waiting row is
     * not a blank line; [step] replaces it with the file actually moving once the transfer starts.
     */
    fun enqueue(direction: TransferDirection, name: String): Long {
        val id = nextEntryId++
        entries += TransferEntry(
            id = id,
            direction = direction,
            name = name,
            fileIndex = 0,
            fileCount = 0,
            transferred = 0,
            total = 0,
            bytesDone = 0,
            elapsedMillis = 0,
            status = TransferStatus.Waiting,
        )
        return id
    }

    /**
     * Starts the entry [id]: from here its bytes and its elapsed time count, so a spell in the
     * queue never shows up as a slow transfer.
     */
    fun activate(id: Long) {
        startedAt = now()
        bytesDone = 0
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = TransferStatus.Active)
    }

    /**
     * Closes a waiting entry as failed, for [failure] — the channel went away before its turn came,
     * or the operation was refused before it could take one. Dropping the row instead would be the
     * silent no-op the queue exists to avoid.
     */
    fun abandon(id: Long, failure: FileTransferFailure) {
        val index = entries.indexOfFirst { it.id == id && it.status == TransferStatus.Waiting }
        if (index < 0) return
        entries[index] = entries[index].copy(status = TransferStatus.Failed(failure))
        trim()
    }

    /** Progress of the file the running operation is on ([index] of [count] in the operation). */
    fun step(name: String, index: Int, count: Int, transferred: Long, total: Long) {
        updateActive {
            it.copy(
                name = name,
                fileIndex = index,
                fileCount = count,
                transferred = transferred,
                total = total,
                bytesDone = bytesDone + transferred,
                elapsedMillis = now() - startedAt,
            )
        }
    }

    /**
     * Carries a finished file's bytes into the operation's running total (the speed reads it).
     * The entry is updated here too: a source that reports no progress callbacks would otherwise
     * leave the total at whatever the last callback said — nothing, for the whole operation.
     */
    fun fileFinished(bytes: Long) {
        bytesDone += bytes
        updateActive { it.copy(bytesDone = bytesDone, elapsedMillis = now() - startedAt) }
    }

    /** Closes the running operation as failed, naming the item it stopped on. */
    fun fail(name: String, failure: FileTransferFailure) {
        end(TransferStatus.Failed(failure), name)
    }

    /** Closes the running entry (if one is still open) and trims the finished ones. */
    fun end(status: TransferStatus, name: String? = null) {
        updateActive { it.copy(status = status, name = name ?: it.name, elapsedMillis = now() - startedAt) }
        trim()
    }

    /** Oldest finished entries go first; what is running or still waiting is never touched. */
    private fun trim() {
        while (entries.count { it.status.isFinished } > MAX_COMPLETED_TRANSFERS) {
            entries.removeAt(entries.indexOfFirst { it.status.isFinished })
        }
    }

    /**
     * Drops entry [id]; a transfer still running is left alone. A waiting one goes — that is how
     * the user cancels it, and [TransferRunner.cancelWaiting] releases what it was holding.
     */
    fun dismiss(id: Long) {
        entries.removeAll { it.id == id && it.status != TransferStatus.Active }
    }

    private fun updateActive(edit: (TransferEntry) -> TransferEntry) {
        val index = entries.indexOfLast { it.status == TransferStatus.Active }
        if (index >= 0) entries[index] = edit(entries[index])
    }
}
