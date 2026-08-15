package app.skerry.ui.vault

import android.provider.DocumentsContract
import app.skerry.ui.sftp.SafBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Writes an exported file via Storage Access Framework, reusing [SafBridge]: `CreateDocument` yields
 * a `content://` Uri, then [content] is written there as UTF-8. The user picks where it lands, which
 * is the point — an exported private key is meant to be moved to another device — and also the limit
 * of what this can promise: a document in Downloads is readable by anything holding that Uri. The
 * decision to hand the key over is re-authenticated at the call site, not here.
 *
 * A closed picker is [ExportOutcome.Cancelled]; an IO/Uri failure is reported as
 * [ExportOutcome.Failed] rather than thrown, and the partially created document is deleted so a
 * truncated key is not left behind looking like a whole one. That delete is best-effort: SAF hands
 * back the final Uri up front, so unlike the desktop writer there is no temp file to discard, and if
 * the delete fails too a partial document can survive at the name the user picked.
 */
internal actual suspend fun exportTextFile(suggestedName: String, content: String): ExportOutcome {
    val ctx = SafBridge.context() ?: return ExportOutcome.Failed
    val uri = SafBridge.createDocument(suggestedName) ?: return ExportOutcome.Cancelled
    // NonCancellable, as on desktop: the document already exists at the Uri the user picked, so the
    // write (or the cleanup delete, and the zeroing) runs to completion even if the screen is torn
    // down mid-write. Delivery of the result to a cancelled caller is not this block's to promise —
    // the key-export path hoists its own NonCancellable around the call; see exportPrivateKey.
    return withContext(Dispatchers.IO + NonCancellable) {
        // Zeroed after the write, as on desktop: the String behind it can't be, but this copy can,
        // and a phone is the likelier of the two to have its heap dumped.
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("no output stream for $uri")
                ExportOutcome.Saved
            }.getOrElse {
                runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                ExportOutcome.Failed
            }
        } finally {
            bytes.fill(0)
        }
    }
}
