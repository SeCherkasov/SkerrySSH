package app.skerry.shared.sync

import app.skerry.shared.io.PrivateConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-based delta-sync cursor store (desktop + Android, shared jvmShared): one line per key,
 * `urlencoded(key)=cursor`, in a private file (0600 via [PrivateConfig], 0700 directory). The key is
 * opaque here and belongs to the caller — the account vault files its cursor under a whole
 * `ServerLink`, a team space under that link and the space (see [SyncStateStore]). Survives
 * process restart — otherwise every launch would do a full re-pull of the whole history
 * `since 0` (LWW is idempotent, but that's wasted load and amplifies retransmission of old
 * tombstones). The cursor itself isn't secret (serverSeq), but it lives alongside the private
 * config and is written via the same atomic path.
 *
 * The in-memory cache is filled once at construction; [setCursor] updates it and atomically
 * rewrites the file. Access is synchronized — the coordinator calls [setCursor] from both the
 * manual and the WS live-pull loop. Reads are best-effort: a corrupt/missing file yields an
 * empty cursor map (0 for any key).
 */
class FileSyncStateStore(private val path: Path) : SyncStateStore {

    private val lock = Any()
    private val cursors: MutableMap<String, Long> = load()

    override fun cursor(key: String): Long = synchronized(lock) { cursors[key] ?: 0L }

    override fun setCursor(key: String, cursor: Long) {
        synchronized(lock) {
            cursors[key] = cursor
            persist()
        }
    }

    override fun keys(): Set<String> = synchronized(lock) { cursors.keys.toSet() }

    private fun load(): MutableMap<String, Long> {
        if (!Files.exists(path)) return mutableMapOf()
        return runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                // The key is URL-encoded -> newline, `=` and the link separator are escaped, so
                // line-by-line parsing is safe.
                val key = URLDecoder.decode(line.substring(0, i), Charsets.UTF_8)
                val value = line.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
                key to value
            }.toMap().toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun persist() {
        val text = buildString {
            cursors.forEach { (key, cursor) -> appendLine("${URLEncoder.encode(key, Charsets.UTF_8)}=$cursor") }
        }
        PrivateConfig.atomicWrite(path, text.encodeToByteArray())
    }
}
