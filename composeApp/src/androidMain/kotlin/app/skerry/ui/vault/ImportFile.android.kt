package app.skerry.ui.vault

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
actual suspend fun importTextFile(maxBytes: Int): String? {
    val ctx = SafBridge.context() ?: return null
    val uri = SafBridge.openDocument() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                val collected = ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (collected.size() + read > maxBytes) return@use null
                    collected.write(buffer, 0, read)
                }
                collected.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }
}
