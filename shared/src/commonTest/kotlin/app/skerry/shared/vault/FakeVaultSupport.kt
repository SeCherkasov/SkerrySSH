package app.skerry.shared.vault

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore

/**
 * In-memory [Vault] for store/migration tests: models put/openPayload/records/remove with
 * versions and tombstones like [FileVault], but without real encryption — payload is stored as-is.
 * Lifecycle (unlock/lock) and AAD are not exercised here.
 */
internal class FakeVault : Vault {
    private data class Entry(val record: VaultRecord, val payload: ByteArray)

    private val entries = mutableMapOf<String, Entry>()

    /** Flip to exercise the locked-vault path of a store; unlocked by default. */
    var locked: Boolean = false

    /**
     * Records that exist and are live but whose payload no longer decrypts — what an adopted
     * account key leaves behind (blobs sealed under the previous one).
     */
    val unreadable: MutableSet<String> = mutableSetOf()

    /** Nesting depth of [transaction] (0 = no transaction held) — lets tests assert atomic sequences. */
    private var transactionDepth = 0

    /** Whether the most recent [put] happened while a [transaction] was held. */
    var lastPutInTransaction: Boolean = false
        private set

    /**
     * How many record reads happened with no [transaction] held. A store that reads outside the
     * transaction and writes inside it still passes [lastPutInTransaction] while leaving the
     * check-then-write race wide open, which is the whole point of holding one.
     *
     * A counter and not a "was the last read in a transaction" flag: the last read of a
     * read-compute-write is the one right before the write, and that one sits inside the block in
     * every implementation — including the racy one, where the snapshot it recomputes from was taken
     * outside. Only counting the reads that escaped can tell the two apart.
     */
    var readsOutsideTransaction: Int = 0
        private set

    override fun <T> transaction(block: () -> T): T {
        transactionDepth++
        try {
            return block()
        } finally {
            transactionDepth--
        }
    }

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean get() = !locked
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun lock() = Unit
    override fun reset() { entries.clear() }

    override fun records(): List<VaultRecord> {
        if (transactionDepth == 0) readsOutsideTransaction++
        return entries.values.map { it.record }
    }

    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY

    override fun openPayload(id: String): ByteArray? {
        if (transactionDepth == 0) readsOutsideTransaction++
        return if (id in unreadable) null else entries[id]?.takeIf { !it.record.deleted }?.payload
    }

    override fun put(id: String, type: RecordType, payload: ByteArray) = putAtLeast(id, type, payload, minVersion = 0L)

    override fun putAtLeast(id: String, type: RecordType, payload: ByteArray, minVersion: Long) {
        lastPutInTransaction = transactionDepth > 0
        // [FileVault.store] refuses this, so the fake has to as well: an id belongs to one type for
        // its whole life, or a store handed a server-chosen id overwrites another store's record.
        val existing = entries[id]?.record
        require(existing == null || existing.type == type) { "record id already holds a ${existing?.type}" }
        val version = maxOf((entries[id]?.record?.version ?: 0L) + 1, minVersion)
        entries[id] = Entry(
            VaultRecord(id, type, version, "2026-06-12T00:00:00Z", "test-device", deleted = false, blob = SEALED),
            payload,
        )
    }

    override fun remove(id: String) {
        val existing = entries[id] ?: return
        entries[id] = existing.copy(
            record = existing.record.copy(version = existing.record.version + 1, deleted = true),
        )
    }

    /** Like [FileVault.compact]: physically forgets tombstones, leaving live records alone. */
    override fun compact(ids: List<String>) {
        ids.forEach { id -> if (entries[id]?.record?.deleted == true) entries.remove(id) }
    }

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    override fun verifyPassword(password: CharArray): Boolean = true
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false

    private companion object {
        val SEALED = "sealed".encodeToByteArray()
    }
}

/** In-memory [HostStore] for migration tests: upsert/remove by [Host.id], insertion order. */
internal class FakeHostStore : HostStore {
    private val hosts = LinkedHashMap<String, Host>()
    override fun all(): List<Host> = hosts.values.toList()
    override fun put(host: Host) { hosts[host.id] = host }
    override fun remove(id: String) { hosts.remove(id) }
    override fun reorder(transform: (List<Host>) -> List<Host>) {
        val updated = transform(hosts.values.toList())
        hosts.clear()
        updated.forEach { hosts[it.id] = it }
    }
}
