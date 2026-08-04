package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sync.nowMillis

/** Where a queue entry stands: still moving bytes, finished, or stopped by a failure. */
sealed interface TransferStatus {
    data object Active : TransferStatus
    data object Done : TransferStatus
    data class Failed(val failure: FileTransferFailure) : TransferStatus
}

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
 * What the transfer strip lists: the running operation plus the last few finished ones. One entry
 * per operation, opened by the operation itself (it knows the direction) and closed once, by
 * whoever runs it — [TransferCoordinator.launchExclusive] does that for every exit path, so no
 * operation can leave an entry open.
 *
 * Transfers are serialized by the coordinator, so at most one entry is [TransferStatus.Active] and
 * it is always the newest; every lookup here relies on that. [now] is the wall clock behind the
 * entries' elapsed time (hence the speed), injected so tests can pin it.
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
            val last = entries.lastOrNull() ?: return TransferState.Idle
            return when (val status = last.status) {
                TransferStatus.Active ->
                    TransferState.Active(last.name, last.direction, last.fileIndex, last.fileCount, last.transferred, last.total)
                TransferStatus.Done -> TransferState.Idle
                is TransferStatus.Failed -> TransferState.Failed(last.name, status.failure)
            }
        }

    /** Whether the newest entry is still running — the caller's cue that it is theirs to close. */
    val hasOpenEntry: Boolean get() = entries.lastOrNull()?.status == TransferStatus.Active

    private var nextEntryId = 1L

    /** When the running operation started, and how many bytes its finished files already moved. */
    private var startedAt = 0L
    private var bytesDone = 0L

    /** Opens the entry of an operation about to start. */
    fun begin(direction: TransferDirection) {
        startedAt = now()
        bytesDone = 0
        entries += TransferEntry(
            id = nextEntryId++,
            direction = direction,
            name = "",
            fileIndex = 0,
            fileCount = 0,
            transferred = 0,
            total = 0,
            bytesDone = 0,
            elapsedMillis = 0,
            status = TransferStatus.Active,
        )
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
        // Oldest finished entries go first; the running one is never touched.
        while (entries.count { it.status != TransferStatus.Active } > MAX_COMPLETED_TRANSFERS) {
            entries.removeAt(entries.indexOfFirst { it.status != TransferStatus.Active })
        }
    }

    /** Drops one finished entry ([id]); a transfer still running is left alone. */
    fun dismiss(id: Long) {
        entries.removeAll { it.id == id && it.status != TransferStatus.Active }
    }

    /** Drops every finished entry at once, leaving only what is still running. */
    fun dismissCompleted() {
        entries.removeAll { it.status != TransferStatus.Active }
    }

    private fun updateActive(edit: (TransferEntry) -> TransferEntry) {
        val index = entries.indexOfLast { it.status == TransferStatus.Active }
        if (index >= 0) entries[index] = edit(entries[index])
    }
}
