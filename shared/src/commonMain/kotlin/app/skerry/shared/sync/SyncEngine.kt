package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord

/** Где хранится курсор дельта-синхронизации (`lastSyncVersion`) на устройство/аккаунт. */
interface SyncStateStore {
    fun cursor(accountId: String): Long
    fun setCursor(accountId: String, cursor: Long)
}

/** In-memory курсор (тесты / эфемерные сессии). Файловая персистентность — отдельной реализацией. */
class InMemorySyncStateStore : SyncStateStore {
    private val cursors = mutableMapOf<String, Long>()
    override fun cursor(accountId: String): Long = cursors[accountId] ?: 0L
    override fun setCursor(accountId: String, cursor: Long) {
        cursors[accountId] = cursor
    }
}

/** Итог одного цикла синхронизации. */
data class SyncOutcome(val pulled: Int, val pushed: Int, val cursor: Long)

/**
 * Клиентский движок синхронизации (`docs/skerry-sync-design.md` §3). Гоняет дельту между
 * локальным [Vault] и сервером через [SyncClient], разрешая конфликты LWW внутри
 * [Vault.mergeRemote]. Работает на шифроблобах — payload не расшифровывает (zero-knowledge);
 * курсор хранит [SyncStateStore].
 *
 * Один цикл [sync]: pull дельты → merge в vault → push всех локальных записей → повторный pull
 * (забрать только что запушенное и параллельные чужие изменения, чтобы курсор не перепрыгнул их).
 * Требует разблокированного vault.
 */
class SyncEngine(
    private val client: SyncClient,
    private val vault: Vault,
    private val state: SyncStateStore = InMemorySyncStateStore(),
) {

    suspend fun sync(session: SyncSession): SyncOutcome {
        var cursor = state.cursor(session.accountId)
        var pulled = 0

        cursor = drainPull(session, cursor) { pulled += it }

        // Пушим все локальные записи: сервер по LWW примет только новее своих (push-all прост и
        // корректен для размеров vault MVP; точечный dirty-трекинг — оптимизация на будущее).
        val local = vault.records().map { it.toRemote() }
        if (local.isNotEmpty()) client.push(session, local)

        // Повторный pull: вытягивает наши же запушенные записи (merge идемпотентен) и любые
        // чужие изменения с serverSeq между первым pull и push — иначе курсор бы их проскочил.
        cursor = drainPull(session, cursor) { pulled += it }

        state.setCursor(session.accountId, cursor)
        return SyncOutcome(pulled = pulled, pushed = local.size, cursor = cursor)
    }

    /** Тянет дельту до опустошения (на случай будущей пагинации), сливая каждую страницу в vault. */
    private suspend fun drainPull(session: SyncSession, from: Long, onMerged: (Int) -> Unit): Long {
        var cursor = from
        while (true) {
            val page = client.pull(session, cursor)
            if (page.records.isNotEmpty()) {
                val merged = vault.mergeRemote(page.records.mapNotNull { it.toVaultRecord() })
                onMerged(merged.size)
            }
            // Компактим ПОСЛЕ merge: иначе только что слитый тромбстоун из этой же страницы тут же
            // вернулся бы в vault. Идемпотентно — список приходит на каждый pull, пока надгробие живо.
            if (page.compactedIds.isNotEmpty()) vault.compact(page.compactedIds)
            if (page.records.isEmpty()) return cursor
            if (page.cursor <= cursor) return page.cursor // защита от зацикливания, если курсор не растёт
            cursor = page.cursor
        }
    }

    private fun VaultRecord.toRemote() =
        RemoteRecord(id, type.name, version, updatedAt, deviceId, deleted, blob)

    /** `null` — незнакомый сервер-тип (пропускаем; сервер их валидирует, но клиент устойчив). */
    private fun RemoteRecord.toVaultRecord(): VaultRecord? {
        val recordType = RecordType.entries.firstOrNull { it.name == type } ?: return null
        return VaultRecord(id, recordType, version, updatedAt, deviceId, deleted, blob)
    }
}
