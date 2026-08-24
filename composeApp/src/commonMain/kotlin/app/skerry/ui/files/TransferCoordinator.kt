package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.sftp.SftpClient
import app.skerry.ui.sftp.DownloadTarget
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.UploadSource
import app.skerry.ui.sync.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
}

/** Maps a browser failure onto the transfer bar's reason; I/O-level causes collapse to [FileTransferFailure.Transfer]. */
private fun FileBrowserFailure.toTransferFailure(): FileTransferFailure = when (this) {
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
     * blank when the failure happened before any file became active — the UI substitutes a
     * localized placeholder.
     */
    data class Failed(val name: String, val failure: FileTransferFailure) : TransferState
}

/**
 * Overwrite conflict awaiting confirmation. [names] are entries in the destination directory that
 * would be overwritten; [proceed] runs the deferred transfer once the user confirms.
 */
class OverwriteConflict(val names: List<String>, val proceed: () -> Unit)

/**
 * Coordinates file transfer between the [local] and [remote] panes over a single [SftpClient].
 * Transfer is always local-FS-to-SFTP, so it maps directly onto `SftpClient.download`/`upload`.
 * Takes the source pane's selection, transfers files in order into the destination pane's current
 * directory, updates [transfer] for the progress bar, then reloads the destination and clears the
 * source selection. On upload, directories in the selection are skipped; on download, a directory
 * is transferred recursively (tree walked via [sftp], local subdirectories recreated via
 * [localBrowser]). At most one transfer runs at a time (serialized via [busy]).
 */
@Stable
class TransferCoordinator(
    private val sftp: SftpClient,
    val local: FilePaneController,
    private val localBrowser: FileContentBrowser,
    val remote: FilePaneController,
    private val remoteBrowser: FileContentBrowser,
    private val scope: CoroutineScope,
    // Wall clock behind the entries' elapsed time (hence the speed); injected so tests pin it.
    private val now: () -> Long = ::nowMillis,
) {
    private val transfers = TransferQueue(now)

    /** The transfer queue, oldest first: what is moving now plus the last few finished entries. */
    val queue: List<TransferEntry> get() = transfers.list

    /**
     * What a single-line view (the mobile Files card) shows: the state of the latest operation.
     * Derived from [queue], so the strip and the card can never disagree.
     */
    val transfer: TransferState get() = transfers.latest

    /**
     * Whether bytes are moving right now — a transfer, or an editor save ([openEditor]; the same
     * [editorWrites] lock session teardown waits on). Read by the vault's idle auto-lock, which
     * defers locking while it is true: a lock closes the session this runs on, and a half-written
     * file is not what a timeout should leave behind — a save is open-truncate-write, so cutting it
     * loses the file. [busy] itself stays private: it is the serialization latch, held across the
     * overwrite prompt too, which is the user being asked something, not work in flight.
     */
    val writeInFlight: Boolean get() = transfer is TransferState.Active || editorWrites.isLocked

    /**
     * Overwrite conflict awaiting confirmation: the destination directory already has entries
     * named [OverwriteConflict.names]. While non-null, the UI shows an "Overwrite?" dialog;
     * [resolveOverwrite] either runs the deferred transfer or cancels it.
     */
    var overwrite: OverwriteConflict? by mutableStateOf(null)
        private set

    /**
     * Serializes transfers: the check-and-set on [busy] isn't atomic, but is safe since
     * `uploadSelection`/`downloadSelection` are called from UI handlers on the main thread, same
     * as [FilePaneController].
     */
    private var busy = false

    /**
     * Held for the duration of an editor's write ([openEditor]). The session's teardown waits on it
     * ([awaitEditorWrites]) before closing the SFTP channel: an editor save runs on this scope and
     * outlives the editor UI, so closing the tab mid-save would otherwise cut the channel with the
     * remote file already truncated and the new content only half written.
     */
    private val editorWrites = Mutex()

    /** Suspends until no editor write is in flight. Called before the transport is closed. */
    suspend fun awaitEditorWrites() {
        editorWrites.withLock { }
    }

    /**
     * Uploads the local pane's selection into the remote pane's current directory. Files are
     * uploaded as-is; directories recursively (subtree recreated on the host), symmetric with
     * download. Symlinks/other are skipped. Progress/error go to [transfer]; serialized via [busy].
     */
    fun uploadSelection() {
        val items = local.selectedItems()
        if (items.isEmpty()) return
        confirmOverwrite(items, remote) { destDir ->
            launchExclusive {
                runUpload(items, destDir)
                remote.reloadNow()
                local.clearSelection()
            }
        }
    }

    /**
     * Downloads the remote pane's selection into the local pane's current directory. Files are
     * downloaded as-is; directories recursively: a tree-walk plan is built first
     * ([buildDownloadPlan]), local subdirectories are recreated ([ensureDir]), then files are
     * downloaded in order with a shared progress counter. Symlinks/other are skipped (never
     * followed). Progress/error go to [transfer]; serialized via [busy].
     */
    fun downloadSelection() {
        val items = remote.selectedItems()
        if (items.isEmpty()) return
        // Snapshot of the source directory at request time: while the Overwrite dialog is open,
        // pane navigation must not move the download sources to a different directory.
        val sourceDir = remote.path
        confirmOverwrite(items, local) { destDir ->
            launchExclusive {
                runDownload(items, destDir, sourceDir)
                local.reloadNow()
                remote.clearSelection()
            }
        }
    }

    /**
     * F6 Move: copies the active pane's selection to the other pane's directory and deletes the
     * sources after a successful transfer (cross-filesystem move = copy + delete). [fromLocal]
     * selects the direction: local pane active (upload to host) or remote (download to local).
     * Deletion runs only after a successful transfer; a transfer error leaves sources untouched.
     * Sources are removed recursively. Confirmation is the UI's responsibility.
     */
    fun moveSelection(fromLocal: Boolean) {
        if (fromLocal) {
            val items = local.selectedItems()
            if (items.isEmpty()) return
            confirmOverwrite(items, remote) { destDir ->
                launchExclusive {
                    runUpload(items, destDir)
                    val failed = deleteSources(items) { localBrowser.delete(it) }
                    remote.reloadNow()
                    local.reloadNow()
                    if (failed == null) local.clearSelection() else transfers.fail(failed.name, FileTransferFailure.DeleteSource)
                }
            }
        } else {
            val items = remote.selectedItems()
            if (items.isEmpty()) return
            // Snapshot of the source directory at request time (before the Overwrite dialog and
            // any suspend point): pane navigation could otherwise change remote.path between
            // confirmation/download and deletion. The same snapshot feeds runDownload and the
            // deletion path rebuild.
            val remoteDir = remote.path
            confirmOverwrite(items, local) { destDir ->
                launchExclusive {
                    runDownload(items, destDir, remoteDir)
                    // Deletes via a path rebuilt from the directory snapshot + a validated name, not
                    // server-controlled item.path.
                    val failed = deleteSources(items) { remoteBrowser.delete(it.copy(path = safeRemoteChild(it.name, remoteDir))) }
                    local.reloadNow()
                    remote.reloadNow()
                    if (failed == null) remote.clearSelection() else transfers.fail(failed.name, FileTransferFailure.DeleteSource)
                }
            }
        }
    }

    /**
     * Deletes source [items] after a successful transfer. A deletion failure doesn't lose data
     * (files already reached the destination) but leaves a partially-moved state; returns the item
     * that could not be removed, so the caller can name it on the queue entry. Null means all
     * sources were deleted. [CancellationException] propagates.
     */
    private suspend fun deleteSources(items: List<FileItem>, delete: suspend (FileItem) -> Unit): FileItem? {
        for (item in items) {
            try {
                delete(item)
            } catch (_: FileBrowserException) {
                return item
            }
        }
        return null
    }

    /**
     * Downloads remote file [item] into a native-picker target [target] (Android: SAF "Save to..."
     * document; desktop: chosen path). SFTP writes bytes to `target.stagingPath`; on success,
     * `target.finalize()` copies staging to the Uri; on error/cancel, `target.discard()`. Unlike
     * [downloadSelection], the target isn't tied to the local pane — this is the mobile Files
     * screen's download-out-of-sandbox path. Progress/error go to [transfer]; serialized via the
     * same [busy] (through [launchExclusive]). Directories are ignored (no recursive transfer here).
     * `discard()` is wrapped in [runCatching] so a cleanup failure doesn't mask the original error.
     */
    fun downloadToTarget(item: FileItem, target: DownloadTarget) {
        if (item.type != FileItemType.File) return
        launchExclusive {
            try {
                transfers.begin(TransferDirection.Download)
                transfers.step(target.displayName, 1, 1, 0, item.size)
                sftp.download(item.path, target.stagingPath) { transferred, total ->
                    transfers.step(target.displayName, 1, 1, transferred, total)
                }
                target.finalize()
            } catch (e: Exception) { // Includes CancellationException — staging is cleaned up either way.
                runCatching { target.discard() }
                throw e
            }
        }
    }

    /**
     * Fallback upload: uploads an arbitrary local [source] (from a native picker) into the remote
     * pane's current directory, for when the local pane has nothing selected. Remote name is
     * `source.name`. Progress/error go to [transfer]; `source.cleanup()` runs on completion
     * (success or error) and the remote pane reloads. Serialized via the same [busy] as
     * selection-based transfers.
     */
    fun uploadSource(source: UploadSource) {
        if (busy) return
        // Snapshot of the destination directory at request time: pane navigation while the
        // Overwrite dialog is open must not redirect the upload to a different directory (TOCTOU).
        val destDir = remote.path
        if (source.name in remote.currentEntryNames()) {
            overwrite = OverwriteConflict(listOf(source.name)) { runUploadSource(source, destDir) }
            return
        }
        runUploadSource(source, destDir)
    }

    private fun runUploadSource(source: UploadSource, destDir: String) {
        launchExclusive(onFinally = { runCatching { source.cleanup() } }) {
            // Opened first, like every other operation: whatever the block does afterwards, the
            // entry exists to carry the outcome.
            transfers.begin(TransferDirection.Upload)
            val target = childPath(destDir, source.name)
            transfers.step(source.name, 1, 1, 0, 0)
            sftp.upload(source.stagingPath, target) { transferred, total ->
                transfers.step(source.name, 1, 1, transferred, total)
            }
            remote.reloadNow()
        }
    }

    /**
     * Editor/viewer (F3/F4) over [item] of the [fromLocal] pane's source. The controller runs on the
     * coordinator's scope (the session's), so a save in flight survives the editor UI leaving
     * composition — a cancelled write would leave the file truncated. The pane reloads after each
     * successful save so the listing shows the new size/mtime.
     */
    fun openEditor(fromLocal: Boolean, item: FileItem, readOnly: Boolean): FileEditController? {
        val pane = if (fromLocal) local else remote
        // Path rebuilt from the pane's directory + a validated name, never the listing's own
        // `item.path`: on the remote side that value is server-controlled, and trusting it would let
        // a hostile listing redirect the read — and the save — to another file entirely. An entry
        // whose name isn't usable as a path component simply doesn't open.
        if (isUnsafeListingName(item.name)) return null
        return FileEditController(
            writeGuard = { write -> editorWrites.withLock { write() } },
            source = if (fromLocal) localBrowser else remoteBrowser,
            item = item.copy(path = childPath(pane.path, item.name)),
            readOnly = readOnly,
            scope = scope,
            onSaved = { pane.reloadNow() },
        ).also { it.open() }
    }

    /** Drops one finished queue entry ([id]); a transfer still running is left alone. */
    fun dismissTransfer(id: Long) {
        transfers.dismiss(id)
    }

    /** Drops every finished entry at once, leaving only what is still running. */
    fun dismissCompleted() {
        transfers.dismissCompleted()
    }

    /**
     * Checks top-level name conflicts between [items] and destination [dest] before starting a
     * transfer. No overlap: proceeds immediately. Overlap: raises the [overwrite] dialog, deferring
     * [proceed] until confirmed ([resolveOverwrite]). Only the top level is checked (nested-tree
     * merges aren't handled here). Silently no-ops if a transfer is already running ([busy]).
     *
     * [proceed] receives a snapshot of the destination directory taken here (when the dialog is
     * shown): the destination pane can be navigated while the dialog is open, so reading
     * `dest.path` at confirmation time would redirect the write elsewhere (TOCTOU) while the
     * conflict check still applied to the old directory.
     */
    private fun confirmOverwrite(items: List<FileItem>, dest: FilePaneController, proceed: (destDir: String) -> Unit) {
        if (busy) return
        val destDir = dest.path
        val existing = dest.currentEntryNames()
        val clash = items.map { it.name }.filter { it in existing }
        if (clash.isEmpty()) proceed(destDir) else overwrite = OverwriteConflict(clash) { proceed(destDir) }
    }

    /** User's answer to the overwrite dialog: true runs the deferred transfer, else cancels it. */
    fun resolveOverwrite(overwrite: Boolean) {
        val pending = this.overwrite ?: return
        this.overwrite = null
        if (overwrite) pending.proceed()
    }

    /**
     * Runs a transfer, serialized via [busy]: while one is active, new calls are ignored. Any
     * error moves the bar to [TransferState.Failed] (name taken from the current active step);
     * [CancellationException] propagates. [onFinally] is a completion hook (success/error/cancel)
     * for the caller's resource cleanup (staging files, etc.); runs before [busy] is cleared —
     * swallowing its own failures is the caller's responsibility (wrap in [runCatching]).
     */
    private fun launchExclusive(onFinally: suspend () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                // A block can finish normally having already closed its own entry as failed
                // (a move whose transfer went through but whose source delete didn't) — that
                // verdict wins, so the entry is only marked done while it is still open.
                if (transfers.hasOpenEntry) transfers.end(TransferStatus.Done)
            } catch (e: CancellationException) {
                // A cancelled operation is over too: leaving the entry Active would show a
                // progress bar that never moves again.
                transfers.end(TransferStatus.Failed(FileTransferFailure.Transfer))
                throw e
            } catch (e: Exception) {
                val failure = (e as? FileBrowserException)?.failure?.toTransferFailure() ?: FileTransferFailure.Transfer
                transfers.end(TransferStatus.Failed(failure))
            } finally {
                onFinally()
                busy = false
            }
        }
    }

    /**
     * Uploads [items] (files as-is, directories recursively via [buildUploadPlan]) into remote
     * [remoteDir], recreating the subtree on the host ([ensureDir]); ends in
     * [TransferState.Idle]. No serialization/post-actions — called inside an already-armed
     * [launchExclusive] block.
     */
    private suspend fun runUpload(items: List<FileItem>, remoteDir: String) {
        transfers.begin(TransferDirection.Upload)
        val plan = buildUploadPlan(localBrowser, items, remoteDir)
        // Directories are created in pre-order: parent always before children.
        plan.dirs.forEach { ensureDir(remoteBrowser, it) }
        plan.files.forEachIndexed { index, task ->
            transfers.step(task.name, index + 1, plan.files.size, 0, task.size)
            sftp.upload(task.localPath, task.remotePath) { transferred, total ->
                transfers.step(task.name, index + 1, plan.files.size, transferred, total)
            }
            transfers.fileFinished(task.size)
        }
    }

    /**
     * Downloads [items] (files as-is, directories recursively via [buildDownloadPlan]) from remote
     * [remoteDir] into local [localDir], recreating the subtree ([ensureDir]); ends in
     * [TransferState.Idle]. No serialization/post-actions — called inside an already-armed
     * [launchExclusive] block.
     */
    private suspend fun runDownload(items: List<FileItem>, localDir: String, remoteDir: String) {
        transfers.begin(TransferDirection.Download)
        val plan = buildDownloadPlan(sftp, items, localDir, remoteDir)
        // Directories are created in pre-order: parent always before children.
        plan.dirs.forEach { ensureDir(localBrowser, it) }
        plan.files.forEachIndexed { index, task ->
            transfers.step(task.name, index + 1, plan.files.size, 0, task.size)
            sftp.download(task.remotePath, task.localPath) { transferred, total ->
                transfers.step(task.name, index + 1, plan.files.size, transferred, total)
            }
            transfers.fileFinished(task.size)
        }
    }
}
