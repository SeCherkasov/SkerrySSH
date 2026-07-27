package app.skerry.ui.vault

import android.content.Context
import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SecretFileResult
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Android [SecretFileReader]: `content://` refs go through the SAF provider, everything else falls
 * through to [delegate] (the okio reader) — a path inside the app's own storage, or one the user
 * has direct access to, still works.
 *
 * The stream is read against a hard [maxBytes] ceiling rather than a declared size: a provider can
 * report anything (or nothing), and a mistyped pick must not be pulled into memory whole. Same rule
 * as [importTextFile].
 */
class AndroidSecretFileReader(
    private val context: Context,
    private val delegate: SecretFileReader,
    private val maxBytes: Int = MAX_BYTES,
) : SecretFileReader {

    override fun read(ref: String): SecretFileResult {
        val trimmed = ref.trim()
        if (!trimmed.startsWith(CONTENT_SCHEME)) return delegate.read(trimmed)
        val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull()
            ?: return SecretFileResult.Failed("malformed Uri")
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return SecretFileResult.NotFound
            stream.use { input ->
                val buffer = ByteArray(64 * 1024)
                val collected = ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (collected.size() + read > maxBytes) return SecretFileResult.TooLarge
                    collected.write(buffer, 0, read)
                }
                SecretFileResult.Ok(collected.toString(Charsets.UTF_8.name()))
            }
        } catch (e: SecurityException) {
            // The persisted grant was revoked (the user cleared it, or the provider's app was
            // reinstalled): the file may well still be there, so this is not "not found".
            SecretFileResult.Denied
        } catch (e: FileNotFoundException) {
            SecretFileResult.NotFound
        } catch (e: Exception) {
            // Providers throw a zoo of their own runtime exceptions; none of them should take the
            // connection attempt down with them.
            SecretFileResult.Failed(e.message)
        }
    }

    /**
     * Opens the document and closes it immediately: enough to tell "readable" from "the grant is
     * gone" without draining a private key into memory. Plain paths go to [delegate], which answers
     * from metadata alone.
     */
    override fun probe(ref: String): Boolean {
        val trimmed = ref.trim()
        if (!trimmed.startsWith(CONTENT_SCHEME)) return delegate.probe(trimmed)
        val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull() ?: return false
        return runCatching { context.contentResolver.openInputStream(uri)?.use { true } }.getOrNull() == true
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"

        /** 256 KiB — matches the okio reader's ceiling. */
        const val MAX_BYTES = 256 * 1024
    }
}
