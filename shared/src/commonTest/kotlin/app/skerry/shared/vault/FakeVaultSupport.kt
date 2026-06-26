package app.skerry.shared.vault

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore

/**
 * In-memory [Vault] для тестов сторов/миграции: моделирует put/openPayload/records/remove с
 * версиями и tombstone как [FileVault], но без реального шифрования — payload хранится как есть.
 * Жизненный цикл (unlock/lock) и AAD здесь не проверяются.
 */
internal class FakeVault : Vault {
    private data class Entry(val record: VaultRecord, val payload: ByteArray)

    private val entries = mutableMapOf<String, Entry>()

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun lock() = Unit
    override fun reset() { entries.clear() }

    override fun records(): List<VaultRecord> = entries.values.map { it.record }

    override fun openPayload(id: String): ByteArray? =
        entries[id]?.takeIf { !it.record.deleted }?.payload

    override fun put(id: String, type: RecordType, payload: ByteArray) {
        val version = (entries[id]?.record?.version ?: 0L) + 1
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

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null

    private companion object {
        val SEALED = "sealed".encodeToByteArray()
    }
}

/** In-memory [HostStore] для тестов миграции: upsert/remove по [Host.id], порядок вставки. */
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
