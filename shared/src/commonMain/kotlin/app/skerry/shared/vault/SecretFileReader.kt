package app.skerry.shared.vault

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * Outcome of reading a secret file ([SecretFileReader.read]). Failures are typed rather than a bare
 * null: a connection that can't start must say which of "you pointed at nothing", "the OS said no"
 * and "this ref belongs to another device" happened — with only a null, all three read as a generic
 * auth failure.
 */
sealed interface SecretFileResult {
    /** File read; [text] is its content decoded as UTF-8, verbatim (trailing newline included). */
    data class Ok(val text: String) : SecretFileResult {
        // Private key material: never let it reach a log or a crash report through toString.
        override fun toString(): String = "Ok(redacted)"
    }

    /** Nothing readable at the ref: no such file, or a directory in its place. */
    data object NotFound : SecretFileResult

    /** The file is there but the OS refused access (permissions, revoked Uri grant). */
    data object Denied : SecretFileResult

    /** Larger than the reader's limit — refused without reading it into memory. */
    data object TooLarge : SecretFileResult

    /** The ref means nothing to this reader (e.g. a `content://` Uri reaching the desktop). */
    data object Unsupported : SecretFileResult

    /** Read failed for another reason; [detail] is diagnostic text (no file content). */
    data class Failed(val detail: String?) : SecretFileResult
}

/**
 * Reads a key/certificate file referenced by a [CredentialSecret.KeyFile]. The ref is
 * platform-shaped — a filesystem path on desktop, a `content://` document Uri on Android — so the
 * interpretation lives in the platform implementation and callers stay ref-agnostic.
 *
 * Blocking on purpose (files are small and reads happen on a connect path already on an IO
 * dispatcher); callers on the UI thread must dispatch it themselves.
 */
fun interface SecretFileReader {
    fun read(ref: String): SecretFileResult

    /**
     * Whether [ref] can be read right now, *without* pulling its content into memory. The vault list
     * uses this for private keys: rendering a row must not load key material, it only needs to know
     * the file is still where the credential says it is. Defaults to a full read for implementations
     * that have no cheaper way to tell.
     */
    fun probe(ref: String): Boolean = read(ref) is SecretFileResult.Ok
}

/**
 * [SecretFileReader] over okio — desktop and Android alike for real filesystem paths (Android's
 * SAF-backed refs are handled by its own reader, which delegates here for plain paths).
 *
 * A leading `~` expands to [homeDir] (null: no expansion, and a `~` ref simply won't resolve) so a
 * profile can be written the way OpenSSH config writes it. Refs carrying a URI scheme are
 * [SecretFileResult.Unsupported] rather than being resolved as relative paths — that's a ref made
 * on another device, and "not found" would be a misleading thing to tell the user.
 *
 * [maxBytes] caps what may be pulled into memory: a key or certificate is a few kilobytes, and a
 * mistyped ref pointing at a disk image must not be slurped whole.
 */
class OkioSecretFileReader(
    private val fileSystem: FileSystem,
    private val homeDir: String?,
    private val maxBytes: Long = MAX_BYTES,
) : SecretFileReader {

    override fun read(ref: String): SecretFileResult {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return SecretFileResult.NotFound
        if (SCHEME.containsMatchIn(trimmed)) return SecretFileResult.Unsupported
        val path = expandHome(trimmed)?.toPath() ?: return SecretFileResult.NotFound
        val meta = try {
            fileSystem.metadataOrNull(path) ?: return SecretFileResult.NotFound
        } catch (e: IOException) {
            return SecretFileResult.Failed(e.message)
        }
        if (!meta.isRegularFile) return SecretFileResult.NotFound
        if ((meta.size ?: 0L) > maxBytes) return SecretFileResult.TooLarge
        return try {
            SecretFileResult.Ok(fileSystem.read(path) { readUtf8() })
        } catch (e: IOException) {
            // okio has no permission-denied type, and the OS text is localized, so matching it would
            // only work in English. Metadata just said the file is there and readable-sized; a read
            // that still fails is access, not absence.
            SecretFileResult.Denied
        }
    }

    /** Metadata only — the file is never opened, so no key material reaches memory. */
    override fun probe(ref: String): Boolean {
        val trimmed = ref.trim()
        if (trimmed.isEmpty() || SCHEME.containsMatchIn(trimmed)) return false
        val path = expandHome(trimmed)?.toPath() ?: return false
        val meta = runCatching { fileSystem.metadataOrNull(path) }.getOrNull() ?: return false
        return meta.isRegularFile && (meta.size ?: 0L) <= maxBytes
    }

    private fun expandHome(ref: String): String? = when {
        !ref.startsWith("~") -> ref
        homeDir == null -> null
        ref == "~" -> homeDir
        ref.startsWith("~/") -> homeDir.trimEnd('/') + ref.removePrefix("~")
        // `~user/...` — another account's home; we don't resolve it (no passwd lookup in common code).
        else -> null
    }

    companion object {
        /** 256 KiB — orders of magnitude above any key or certificate, still a hard stop. */
        const val MAX_BYTES: Long = 256L * 1024

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    }
}
