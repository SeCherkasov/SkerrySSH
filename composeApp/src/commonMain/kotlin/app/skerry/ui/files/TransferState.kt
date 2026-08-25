package app.skerry.ui.files

import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.ui.sftp.TransferDirection

/**
 * Typed, user-facing reason for a failed transfer; the UI maps it to a localized string. Raw
 * exception text is never shown — a [FileBrowserException] contributes only its
 * [FileBrowserFailure], anything else lands on [Transfer].
 */
enum class FileTransferFailure {
    /** The byte transfer itself failed (stream/SFTP/local I/O). */
    Transfer,

    /** Files reached the destination, but a source could not be removed after a move. */
    DeleteSource,

    /** A listing entry carried a name unsafe to use as a path component. */
    IllegalName,

    /** The picked upload source could not be opened. */
    OpenSource,

    /** The chosen download target could not be opened. */
    OpenTarget,

    /** The session closed while the operation was still waiting its turn on the channel. */
    SessionClosed,
}

/** Maps a browser failure onto the transfer bar's reason; I/O-level causes collapse to [FileTransferFailure.Transfer]. */
internal fun FileBrowserFailure.toTransferFailure(): FileTransferFailure = when (this) {
    FileBrowserFailure.IllegalName -> FileTransferFailure.IllegalName
    FileBrowserFailure.OpenSource -> FileTransferFailure.OpenSource
    FileBrowserFailure.OpenTarget -> FileTransferFailure.OpenTarget
    FileBrowserFailure.LocalIo, FileBrowserFailure.Sftp, FileBrowserFailure.TooLarge ->
        FileTransferFailure.Transfer
}

/** Cross-pane batch transfer state, for the bottom transfer bar. */
sealed interface TransferState {
    /** No transfer in progress. */
    data object Idle : TransferState

    /**
     * Transferring file [name] ([fileIndex] of [fileCount] in the batch), [transferred] of [total]
     * bytes ([total] = 0 if unknown).
     */
    data class Active(
        val name: String,
        val direction: TransferDirection,
        val fileIndex: Int,
        val fileCount: Int,
        val transferred: Long,
        val total: Long,
    ) : TransferState

    /**
     * Transfer of [name] failed; [failure] is the typed reason, localized by the UI. [name] is
     * empty when the failure happened before any file became active — the UI substitutes a
     * localized placeholder. [id] is the queue entry it came from, so a single-line view can clear
     * that row rather than every finished one.
     */
    data class Failed(val id: Long, val name: String, val failure: FileTransferFailure) : TransferState
}

/**
 * Overwrite conflict awaiting confirmation. [names] are entries in the destination directory that
 * would be overwritten; [proceed] runs the deferred transfer once the user confirms, [cancel] runs
 * instead when the user refuses — "no" is an answer too, and whatever [proceed] would have consumed
 * (a picked upload's staged copy) is nobody else's to release.
 */
class OverwriteConflict(
    val names: List<String>,
    val proceed: () -> Unit,
    val cancel: () -> Unit = {},
)
