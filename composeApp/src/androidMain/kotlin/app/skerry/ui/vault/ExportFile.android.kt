package app.skerry.ui.vault

import android.provider.DocumentsContract
import app.skerry.ui.sftp.SafBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a public key/certificate via Storage Access Framework, reusing [SafBridge]: `CreateDocument`
 * yields a `content://` Uri, then [content] is written there as UTF-8. Only public material is
 * exported; private keys never leave the app.
 *
 * Returns `false` if the user cancels the picker (Uri == null) or the write fails; any IO/Uri
 * failure is swallowed rather than thrown. On write failure the partially created document is
 * deleted to avoid leaving an empty file behind.
 */
actual suspend fun exportTextFile(suggestedName: String, content: String): Boolean {
    val ctx = SafBridge.context() ?: return false
    val uri = SafBridge.createTextDocument(suggestedName) ?: return false
    return withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: error("no output stream for $uri")
            true
        }.getOrElse {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            false
        }
    }
}
