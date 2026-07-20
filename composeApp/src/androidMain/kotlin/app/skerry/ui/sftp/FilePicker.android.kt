package app.skerry.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android file picking via the Storage Access Framework. SAF returns a `content://` Uri, but sshj
 * only works with filesystem paths, so transfers go through a temp file in the private cache
 * (staging), with staging↔Uri copying encapsulated in the handle:
 * - download: create a document (`CreateDocument`), sshj writes to staging, [DownloadTarget.finalize]
 *   copies staging→Uri; on error [DownloadTarget.discard] cleans up staging and the empty document;
 * - upload: open a document (`OpenDocument`), copy Uri→staging immediately (the Uri may become
 *   unavailable later), sshj reads staging, [UploadSource.cleanup] deletes it.
 *
 * Launching the SAF dialogs is delegated to [SafBridge], populated by the Activity in `onCreate`.
 */
actual suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget? {
    val ctx = SafBridge.context() ?: return null
    val uri = SafBridge.createDocument(suggestedName) ?: return null
    val staging = File(stagingDir(ctx), "dl-${stagingStamp()}.tmp")
    return SafDownloadTarget(ctx, uri, suggestedName, staging)
}

actual suspend fun pickUploadSource(): UploadSource? {
    val ctx = SafBridge.context() ?: return null
    val uri = SafBridge.openDocument() ?: return null
    val name = queryDisplayName(ctx, uri) ?: "upload.bin"
    val staging = File(stagingDir(ctx), "ul-${stagingStamp()}.tmp")
    // Copy the selected document's content to staging immediately: Uri access grants are short-lived
    // and the transfer may start later. Catches [Exception] broadly (SAF Uris can throw
    // SecurityException/IllegalState on revoked access, not just IOException); treated as a failed
    // pick (null), staging removed. [CancellationException] is rethrown after cleanup.
    return try {
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw FileBrowserException(FileBrowserFailure.OpenSource)
        }
        SafUploadSource(name, staging)
    } catch (e: CancellationException) {
        staging.delete()
        throw e
    } catch (e: Exception) {
        staging.delete()
        null
    }
}

/** Download target over a SAF document: sshj writes to [staging], [finalize] copies it into [uri]. */
private class SafDownloadTarget(
    private val ctx: Context,
    private val uri: Uri,
    override val displayName: String,
    private val staging: File,
) : DownloadTarget {
    override val stagingPath: String = staging.absolutePath

    override suspend fun finalize(): Unit = withContext(Dispatchers.IO) {
        // staging is removed in finally so it never leaks even if openOutputStream/copyTo fails
        // (the controller calls discard on finalize failure to clean up the empty/partial SAF document).
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { output ->
                staging.inputStream().use { input -> input.copyTo(output) }
            } ?: throw FileBrowserException(FileBrowserFailure.OpenTarget)
        } finally {
            staging.delete()
        }
    }

    override suspend fun discard(): Unit = withContext(Dispatchers.IO) {
        staging.delete()
        // CreateDocument already created an empty document at the chosen location; remove it.
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
        Unit
    }
}

/** Upload source: the Uri's content is already copied to [staging], sshj reads from there. */
private class SafUploadSource(
    override val name: String,
    private val staging: File,
) : UploadSource {
    override val stagingPath: String = staging.absolutePath

    override suspend fun cleanup(): Unit = withContext(Dispatchers.IO) {
        staging.delete()
        Unit
    }
}

/** Private directory for transfer staging files, hidden from the user. */
private fun stagingDir(ctx: Context): File = File(ctx.cacheDir, "sftp").apply { mkdirs() }

/** Unique staging filename suffix, avoids collisions across concurrent transfers. */
private fun stagingStamp(): String = UUID.randomUUID().toString()

/**
 * Human-readable name of the selected document from `OpenableColumns.DISPLAY_NAME`. Any query
 * failure (revoked Uri, misbehaving provider) is swallowed to null; callers fall back to "upload.bin".
 */
private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
}.getOrNull()

/**
 * Bridge between top-level suspend pickers and Activity-level SAF. The Activity registers
 * `ActivityResultLauncher`s for Create/Open Document in `onCreate` and hands launch lambdas to
 * [install]; pickers invoke them and await the result via [CompletableDeferred].
 *
 * Picks are serialized by [lock] (a SAF dialog is modal, so a single [pending] suffices). Fields are
 * `@Volatile`: written from the picker's coroutine, read from `ActivityResultCallback` (Main) and
 * `onCreate`. On Activity recreation, [install] completes a stale pending with null so its awaiting
 * coroutine doesn't hang forever. Holds only `applicationContext` (no Activity leak). Launch lambdas
 * must be invoked on the main thread.
 */
object SafBridge {
    private val lock = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var launchCreate: ((String) -> Unit)? = null

    @Volatile
    private var launchCreateText: ((String) -> Unit)? = null

    @Volatile
    private var launchOpen: (() -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<Uri?>? = null

    /**
     * Called from `MainActivity.onCreate`: [launchCreate] (octet-stream, binary SFTP download) and
     * [launchCreateText] (text/plain, key/certificate .pub export) take a filename; [launchOpen] takes
     * none. The two create launchers differ only by MIME (fixed at contract registration), giving the
     * file manager the right icon/handler for text .pub exports.
     */
    fun install(context: Context, launchCreate: (String) -> Unit, launchCreateText: (String) -> Unit, launchOpen: () -> Unit) {
        // Release a pick started by the previous (destroyed) Activity, or its await would hang forever.
        pending?.complete(null)
        pending = null
        appContext = context.applicationContext
        this.launchCreate = launchCreate
        this.launchCreateText = launchCreateText
        this.launchOpen = launchOpen
    }

    fun context(): Context? = appContext

    /** Launch CreateDocument (octet-stream) with [suggestedName], await the chosen Uri (null on cancel). */
    suspend fun createDocument(suggestedName: String): Uri? = createVia(launchCreate, suggestedName)

    /** Launch CreateDocument (text/plain) with [suggestedName], for key/certificate text export. */
    suspend fun createTextDocument(suggestedName: String): Uri? = createVia(launchCreateText, suggestedName)

    private suspend fun createVia(launch: ((String) -> Unit)?, suggestedName: String): Uri? = lock.withLock {
        val fire = launch ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        fire(suggestedName)
        deferred.await()
    }

    /** Launch OpenDocument, await the chosen Uri (null on cancel). */
    suspend fun openDocument(): Uri? = lock.withLock {
        val launch = launchOpen ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        launch()
        deferred.await()
    }

    /** CreateDocument result callback (invoked by the Activity on the main thread). */
    fun onCreateResult(uri: Uri?) = completePending(uri)

    /** OpenDocument result callback (invoked by the Activity on the main thread). */
    fun onOpenResult(uri: Uri?) = completePending(uri)

    private fun completePending(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }
}
