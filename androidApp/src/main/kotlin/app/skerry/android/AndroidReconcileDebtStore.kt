package app.skerry.android

import app.skerry.shared.io.PrivateConfig
import app.skerry.ui.sync.ReconcileDebtStore
import app.skerry.ui.sync.ServerLink
import app.skerry.ui.sync.UnreadReconcileDebts
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files

/**
 * File-backed reconcile-debt store (Android): `sync-reconcile` in the app's private files dir. One line
 * per owed link, `urlencoded(serverUrl)=urlencoded(accountId)` — mirrors desktop `FileReconcileDebtStore`,
 * down to the write primitive: [PrivateConfig.atomicWrite] never rewrites the file in place, so a process
 * kill (the norm on Android) cannot leave a truncated file that reads as "this device owes nothing".
 *
 * Its own file rather than a key in `sync.json`: the config is erased by a disconnect and overwritten by
 * a connect to another server, and a debt must outlive both (issue #170).
 */
class AndroidReconcileDebtStore(private val file: File) : ReconcileDebtStore {

    private val unread = UnreadReconcileDebts(::read)

    override fun load(): Set<ServerLink> = unread.load()

    private fun read(): Set<ServerLink> {
        if (!file.exists()) return emptySet()
        // Per line, so one unparseable entry (a truncated percent escape) costs its own debt and no
        // more. Losing that one is already a silent resurrection for its link; letting it take every
        // intact line with it is the same failure on every link at once.
        return file.readLines().mapNotNull { line -> runCatching { parse(line) }.getOrNull() }.toSet()
    }

    override fun save(debts: Set<ServerLink>) {
        val all = unread.toWrite(debts)
        if (all.isEmpty()) {
            Files.deleteIfExists(file.toPath()) // throws on a real I/O failure — a retired debt must land
            return
        }
        val text = all.joinToString(separator = "") {
            "${URLEncoder.encode(it.serverUrl, "UTF-8")}=${URLEncoder.encode(it.accountId, "UTF-8")}\n"
        }
        PrivateConfig.atomicWrite(file.toPath(), text.encodeToByteArray())
    }

    private fun parse(line: String): ServerLink? {
        val i = line.indexOf('=')
        if (i <= 0) return null
        val url = URLDecoder.decode(line.substring(0, i), "UTF-8")
        val account = URLDecoder.decode(line.substring(i + 1), "UTF-8")
        return if (url.isEmpty() || account.isEmpty()) null else ServerLink(url, account)
    }
}
