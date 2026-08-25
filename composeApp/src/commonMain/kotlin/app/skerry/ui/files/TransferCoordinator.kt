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
import kotlin.coroutines.cancellation.CancellationException

/** Top-level names an operation still on the queue is going to create in directory [dir]. */
private class PlannedWrite(val dir: String, val names: Set<String>)

/**
 * Coordinates file transfer between the [local] and [remote] panes over a single [SftpClient].
 * Transfer is always local-FS-to-SFTP, so it maps directly onto `SftpClient.download`/`upload`.
 * Takes the source pane's selection, transfers files in order into the destination pane's current
 * directory, updates [transfer] for the progress bar, then reloads the destination and clears the
 * source selection. On upload, directories in the selection are skipped; on download, a directory
 * is transferred recursively (tree walked via [sftp], local subdirectories recreated via
 * [localBrowser]). At most one transfer runs at a time; the rest wait their turn in
 * [TransferRunner], visible on the queue as they wait.
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

    /**
     * Runs the operations, one at a time and in order. Everything the coordinator starts goes
     * through it, so no requested operation is ever dropped without a row saying what became of it.
     */
    private val runner = TransferRunner(scope, transfers)

    /** The transfer queue, oldest first: what is waiting and moving now, plus the last few finished. */
    val queue: List<TransferEntry> get() = transfers.list

    /**
     * What a single-line view (the mobile Files card) shows: the state of the latest operation.
     * Derived from [queue], so the strip and the card can never disagree.
     */
    val transfer: TransferState get() = transfers.latest

    /**
     * Whether this session still owes the user file work — a transfer running or waiting its turn,
     * or an editor save ([openEditor]; the same [editorWrites] lock session teardown waits on).
     * Read by the vault's idle auto-lock, which defers locking while it is true: a lock closes the
     * session this runs on, and a half-written file is not what a timeout should leave behind — a
     * save is open-truncate-write, so cutting it loses the file. A queued operation counts for the
     * same reason: locking would close the channel its turn never came on. A question put to the
     * user — an open "Overwrite?" dialog — does not: nothing has been requested yet.
     */
    val writeInFlight: Boolean get() = transfers.hasWork || editorWrites.isLocked

    /**
     * Overwrite conflict awaiting confirmation: the destination directory already has entries
     * named [OverwriteConflict.names]. While non-null, the UI shows an "Overwrite?" dialog;
     * [resolveOverwrite] either runs the deferred transfer or cancels it. Head of [conflicts].
     */
    var overwrite: OverwriteConflict? by mutableStateOf(null)
        private set

    /**
     * Conflicts still to be answered, in the order they were raised. Normally at most one — the
     * dialog is modal — but the native file picker draws over it, so a second picked upload can
     * reach the coordinator while the first is still being asked about. Replacing the pending
     * conflict would drop the staged copy the first one holds, which is the defect this queue
     * exists to avoid, so they are answered one after another instead.
     */
    private val conflicts = ArrayDeque<OverwriteConflict>()

    /**
     * Top-level names an operation that has not finished yet will write, and where. The overwrite
     * check reads the destination pane's *listing*, which only catches up when the pane reloads —
     * after an operation ends. While transfers were serialized by a latch there was never a second
     * operation to be stale about; a queue makes it stale by construction, and the two would
     * silently overwrite each other. Worst case is a move: the second one's source is deleted after
     * the transfer, so the file it overwrote exists nowhere. Entries are added when an operation is
     * submitted and dropped when it leaves, however it leaves.
     */
    private val plannedWrites = mutableListOf<PlannedWrite>()

    /** Names an operation still on the queue will write into [dir]. */
    private fun planned(dir: String): Set<String> =
        plannedWrites.filter { it.dir == dir }.flatMapTo(mutableSetOf()) { it.names }

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
     * download. Symlinks/other are skipped. Progress/error go to [transfer]; runs when the channel
     * is free, waits on the queue until then.
     */
    fun uploadSelection() {
        val items = local.selectedItems()
        if (items.isEmpty()) return
        confirmOverwrite(items, remote) { destDir ->
            submit(TransferDirection.Upload, items.first().name, destDir, items.map { it.name }) {
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
     * followed). Progress/error go to [transfer]; runs when the channel is free, waits on the queue
     * until then.
     */
    fun downloadSelection() {
        val items = remote.selectedItems()
        if (items.isEmpty()) return
        // Snapshot of the source directory at request time: while the Overwrite dialog is open or
        // the operation waits its turn, pane navigation must not move the download sources to a
        // different directory.
        val sourceDir = remote.path
        confirmOverwrite(items, local) { destDir ->
            submit(TransferDirection.Download, items.first().name, destDir, items.map { it.name }) {
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
                submit(TransferDirection.Upload, items.first().name, destDir, items.map { it.name }) {
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
                submit(TransferDirection.Download, items.first().name, destDir, items.map { it.name }) {
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
     * screen's download-out-of-sandbox path. Progress/error go to [transfer]; it takes its turn on
     * the queue like every other operation. Directories are ignored (no recursive transfer here).
     *
     * The target is discarded on every path that doesn't move it — a failed transfer, a failed
     * `finalize()`, a cancelled session, an operation dropped before its turn came. On Android that
     * is not housekeeping: the picker has already created the document at the location the user
     * chose, so leaving it is leaving them an empty file where they asked for their data.
     * `discard()` is wrapped in [runCatching] so a cleanup failure doesn't mask the original error.
     */
    fun downloadToTarget(item: FileItem, target: DownloadTarget) {
        if (item.type != FileItemType.File) return
        var moved = false
        submit(
            direction = TransferDirection.Download,
            name = target.displayName,
            // The target is a path the platform picker owns, not a directory this app writes into,
            // so there is nothing for another queued operation to clash with.
            destDir = "",
            writes = emptyList(),
            onFinally = { if (!moved) runCatching { target.discard() } },
        ) {
            transfers.step(target.displayName, 1, 1, 0, item.size)
            sftp.download(item.path, target.stagingPath) { transferred, total ->
                transfers.step(target.displayName, 1, 1, transferred, total)
            }
            target.finalize()
            moved = true
        }
    }

    /**
     * Fallback upload: uploads an arbitrary local [source] (from a native picker) into the remote
     * pane's current directory, for when the local pane has nothing selected. Remote name is
     * `source.name`. Progress/error go to [transfer]; it takes its turn on the queue like every
     * other operation, and the remote pane reloads afterwards.
     *
     * `source.cleanup()` runs on every path the source leaves by — a finished or failed transfer, a
     * refused overwrite, an operation dropped before its turn came. The picker has already copied
     * the whole file into the app's cache by the time this is called (the Uri grant is short-lived),
     * so a path that forgets to release it leaks a full copy of whatever the user picked.
     */
    fun uploadSource(source: UploadSource) {
        // The name is whatever the picker's provider reported — on Android an
        // OpenableColumns.DISPLAY_NAME from an app we do not control — and it becomes a path
        // component on the host, under the user's own credentials. A name that would climb out of
        // the destination directory never gets that far, and never reaches the conflict check
        // either: no listing entry can match it, so nothing would have been asked.
        if (isUnsafeListingName(source.name)) {
            refuse(source, FileTransferFailure.IllegalName)
            return
        }
        // Snapshot of the destination directory at request time: pane navigation while the Overwrite
        // dialog is open, or while the upload waits its turn, must not redirect it (TOCTOU).
        val destDir = remote.path
        if (source.name in remote.currentEntryNames() + planned(destDir)) {
            ask(
                OverwriteConflict(
                    names = listOf(source.name),
                    proceed = { runUploadSource(source, destDir) },
                    cancel = { runner.release { source.cleanup() } },
                ),
            )
            return
        }
        runUploadSource(source, destDir)
    }

    /**
     * Turns a picked source away with a row that says why, and releases its staged copy. The row is
     * the point: this is the one path where the app refuses something the user already answered a
     * picker for, and #317 is what happens when such a refusal is silent.
     */
    private fun refuse(source: UploadSource, failure: FileTransferFailure) {
        transfers.abandon(transfers.enqueue(TransferDirection.Upload, source.name), failure)
        runner.release { source.cleanup() }
    }

    private fun runUploadSource(source: UploadSource, destDir: String) {
        submit(
            direction = TransferDirection.Upload,
            name = source.name,
            destDir = destDir,
            writes = listOf(source.name),
            onFinally = { runCatching { source.cleanup() } },
        ) {
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

    /**
     * Drops one queue entry ([id]); a transfer still running is left alone. An entry still waiting
     * for its turn is cancelled by dropping it, and whatever it was holding — a picked upload's
     * staged copy, a "Save to…" document — is released with it.
     */
    fun dismissTransfer(id: Long) {
        runner.cancelWaiting(id)
        transfers.dismiss(id)
    }

    /**
     * Releases every operation the session still owes an answer or a turn to: the channel is
     * closing, so neither will come. Their rows are closed as failed rather than dropped — the user
     * asked for them — and their handles go back to the platform. Called by the session teardown
     * before the SFTP channel is closed.
     *
     * The unanswered questions go first and matter most: a picked upload stopped at the "Overwrite?"
     * dialog has no queue row yet, and its staged copy is a byte-for-byte copy of whatever the user
     * picked sitting in the app's cache. Nothing else ever comes back for it.
     */
    fun releaseQueued() {
        while (conflicts.isNotEmpty()) conflicts.removeFirst().cancel()
        overwrite = null
        runner.releaseWaiting()
    }

    /**
     * Checks top-level name conflicts between [items] and destination [dest] before starting a
     * transfer. No overlap: proceeds immediately. Overlap: raises the [overwrite] dialog, deferring
     * [proceed] until confirmed ([resolveOverwrite]). Only the top level is checked (nested-tree
     * merges aren't handled here).
     *
     * [proceed] receives a snapshot of the destination directory taken here (when the dialog is
     * shown): the destination pane can be navigated while the dialog is open, so reading
     * `dest.path` at confirmation time would redirect the write elsewhere (TOCTOU) while the
     * conflict check still applied to the old directory.
     *
     * What an operation already on the queue is going to write counts as existing ([planned]): the
     * pane's listing does not know about it yet, and without that the two would overwrite each
     * other with nothing asked.
     */
    private fun confirmOverwrite(items: List<FileItem>, dest: FilePaneController, proceed: (destDir: String) -> Unit) {
        val destDir = dest.path
        val existing = dest.currentEntryNames() + planned(destDir)
        val clash = items.map { it.name }.filter { it in existing }
        if (clash.isEmpty()) proceed(destDir) else ask(OverwriteConflict(clash, proceed = { proceed(destDir) }))
    }

    /** Puts a conflict to the user, behind any that are already waiting for an answer. */
    private fun ask(conflict: OverwriteConflict) {
        conflicts += conflict
        if (overwrite == null) overwrite = conflict
    }

    /**
     * User's answer to the overwrite dialog: true runs the deferred transfer, false releases what
     * it would have consumed. The next unanswered conflict, if any, takes the dialog's place.
     */
    fun resolveOverwrite(overwrite: Boolean) {
        val pending = conflicts.removeFirstOrNull() ?: return
        this.overwrite = conflicts.firstOrNull()
        if (overwrite) pending.proceed() else pending.cancel()
    }

    /**
     * Hands an operation to [runner]: it opens the queue entry ([direction] and [name] label it
     * until the transfer names the file it is on), runs [block] when the channel is free, and
     * closes the entry however it ends. [destDir] and [writes] are the top-level names it will
     * create, claimed for as long as it is on the queue so the next operation's overwrite check can
     * see them ([planned]). [onFinally] releases whatever the operation was holding and runs
     * exactly once, whether or not [block] ever got to run — swallowing its own failures is the
     * caller's responsibility (wrap in [runCatching]).
     */
    private fun submit(
        direction: TransferDirection,
        name: String,
        destDir: String,
        writes: List<String>,
        onFinally: suspend () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val plan = PlannedWrite(destDir, writes.toSet())
        plannedWrites += plan
        runner.submit(direction, name, onFinally = { plannedWrites.remove(plan); onFinally() }, block = block)
    }

    /**
     * Uploads [items] (files as-is, directories recursively via [buildUploadPlan]) into remote
     * [remoteDir], recreating the subtree on the host ([ensureDir]); ends in
     * [TransferState.Idle]. No serialization/post-actions — called inside a block [runner] has
     * already opened the queue entry for.
     */
    private suspend fun runUpload(items: List<FileItem>, remoteDir: String) {
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
     * [TransferState.Idle]. No serialization/post-actions — called inside a block [runner] has
     * already opened the queue entry for.
     */
    private suspend fun runDownload(items: List<FileItem>, localDir: String, remoteDir: String) {
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
