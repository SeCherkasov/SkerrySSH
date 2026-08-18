package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore

/**
 * In-memory [HostStore] with the upsert/remove-by-id semantics of the file-backed one — the catalog
 * every test that needs hosts seeds, rather than a fresh anonymous object per file.
 */
internal class FakeHostStore(initial: List<Host> = emptyList()) : HostStore {
    constructor(vararg initial: Host) : this(initial.toList())

    private val entries = initial.toMutableList()

    override fun all(): List<Host> = entries.toList()

    override fun put(host: Host) {
        val index = entries.indexOfFirst { it.id == host.id }
        if (index >= 0) entries[index] = host else entries += host
    }

    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }

    override fun reorder(transform: (List<Host>) -> List<Host>) {
        val updated = transform(entries.toList())
        // The precondition VaultHostStore enforces: a fake that accepts anything lets a transform
        // that drops or duplicates a profile pass every test and throw in production.
        require(updated.size == entries.size && updated.map { it.id }.toSet() == entries.map { it.id }.toSet()) {
            "reorder must preserve the id set (had ${entries.size}, got ${updated.size})"
        }
        entries.clear()
        entries += updated
    }
}

/** A catalog controller over [hosts], with ids handed out in order. */
internal fun hostCatalogOf(hosts: List<Host>): HostManagerController {
    var seq = 0
    return HostManagerController(FakeHostStore(hosts)) { "gen-${seq++}" }
}

/** Same, for the call sites that name their hosts inline. */
internal fun hostCatalogOf(vararg hosts: Host): HostManagerController = hostCatalogOf(hosts.toList())
