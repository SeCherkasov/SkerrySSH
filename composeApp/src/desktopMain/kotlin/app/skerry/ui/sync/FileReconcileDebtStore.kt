package app.skerry.ui.sync

import app.skerry.shared.io.PrivateConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-backed reconcile-debt store (desktop): `sync-reconcile` alongside `sync.json`, mode 0600 via
 * [PrivateConfig]. One line per owed link, `urlencoded(serverUrl)=urlencoded(accountId)` — the same
 * line-based, dependency-free shape as [FileSyncConfigStore], and URL-encoding keeps a server URL with
 * an `=` or a newline in it on one parseable line.
 *
 * Its own file rather than a key in `sync.json` on purpose: the config is erased by a disconnect and
 * overwritten by a connect to another server, and a debt must outlive both (issue #170).
 *
 * Reads are best-effort ([ReconcileDebtStore.load]); an empty set is written as a deleted file, so a
 * device that owes nothing carries no file at all.
 */
class FileReconcileDebtStore(private val path: Path) : ReconcileDebtStore {

    override fun load(): Set<ServerLink> {
        if (!Files.exists(path)) return emptySet()
        return runCatching {
            // Per line, so one unparseable entry (a truncated percent escape, which [decode] throws on)
            // costs its own debt and no more. Losing that one is already a silent resurrection for its
            // link; letting it take every intact line with it is the same failure on every link at once.
            Files.readAllLines(path).mapNotNull { line -> runCatching { parse(line) }.getOrNull() }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun parse(line: String): ServerLink? {
        val i = line.indexOf('=')
        if (i <= 0) return null
        val url = decode(line.substring(0, i))
        val account = decode(line.substring(i + 1))
        return if (url.isEmpty() || account.isEmpty()) null else ServerLink(url, account)
    }

    override fun save(debts: Set<ServerLink>) {
        if (debts.isEmpty()) {
            Files.deleteIfExists(path)
            return
        }
        val text = debts.joinToString(separator = "") { "${encode(it.serverUrl)}=${encode(it.accountId)}\n" }
        PrivateConfig.atomicWrite(path, text.encodeToByteArray())
    }

    private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8)
}
