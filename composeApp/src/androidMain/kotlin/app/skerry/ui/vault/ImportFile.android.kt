package app.skerry.ui.vault

import android.provider.OpenableColumns
import app.skerry.ui.sftp.SafBridge
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a text file via Storage Access Framework, reusing [SafBridge]: `OpenDocument` yields a
 * `content://` Uri, which is then read as UTF-8.
 *
 * The stream is read with a hard [maxBytes] ceiling rather than trusting a declared size: a
 * `content://` provider can report anything (or nothing), so the limit is enforced while reading and
 * an oversized file yields `null` instead of an out-of-memory crash.
 */
actual suspend fun importTextFile(maxBytes: Int): ImportedFile? {
    val ctx = SafBridge.context() ?: return null
    val uri = SafBridge.openDocument() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            // Display name for labels: a content:// Uri has no path to take a file name from, so it
            // is queried; providers may omit the column, hence the last-segment fallback.
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: ""
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                val collected = ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (collected.size() + read > maxBytes) return@use null
                    collected.write(buffer, 0, read)
                }
                ImportedFile(name, collected.toString(Charsets.UTF_8.name()))
            }
        }.getOrNull()
    }
}
