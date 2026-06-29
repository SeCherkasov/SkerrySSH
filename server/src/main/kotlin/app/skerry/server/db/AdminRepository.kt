package app.skerry.server.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Read- и destructive-операции, нужные только админ-консоли (`docs/skerry-sync-design.md` §3):
 * агрегаты по аккаунтам, реальные envelope'ы записей, безопасный purge tombstone'ов и каскадное
 * удаление аккаунта. Zero-knowledge сохраняется: наружу идут только метаданные и размеры
 * шифроблобов, к содержимому доступа нет по определению.
 */
class AdminRepository(private val db: Database) {

    data class AccountSummary(
        val id: String,
        val createdAt: Long,
        val syncSeq: Long,
        val devices: Int,
        val activeDevices: Int,
        val records: Int,
        val tombstones: Int,
        val storageBytes: Long,
        val lastSeenAt: Long?,
    )

    data class RecordEnvelope(
        val id: String,
        val type: String,
        val version: Long,
        val updatedAt: String,
        val deviceId: String,
        val deleted: Boolean,
        val blobBytes: Int,
        val serverSeq: Long,
        val previewHex: String,
    )

    private class DevAgg(var total: Int = 0, var active: Int = 0, var lastSeen: Long? = null)
    private class RecAgg(var total: Int = 0, var tombstones: Int = 0, var bytes: Long = 0)

    /**
     * Сводка по всем аккаунтам инстанса. Агрегаты считаются на стороне БД (три групповых запроса,
     * не N+1): устройства (всего/активных/последняя активность) и записи (всего/tombstone'ов/байт).
     * `NOT revoked` / `CASE WHEN deleted` переносимы между SQLite (0/1) и PostgreSQL (boolean).
     */
    fun accountSummaries(): List<AccountSummary> = transaction(db) {
        val devAgg = HashMap<String, DevAgg>()
        exec(
            """SELECT account_id,
                      COUNT(*) AS total,
                      SUM(CASE WHEN NOT revoked THEN 1 ELSE 0 END) AS active,
                      MAX(last_seen_at) AS last_seen
               FROM devices GROUP BY account_id""",
        ) { rs ->
            while (rs.next()) {
                val a = DevAgg(rs.getInt("total"), rs.getInt("active"))
                a.lastSeen = rs.getLong("last_seen").let { if (rs.wasNull()) null else it }
                devAgg[rs.getString("account_id")] = a
            }
        }

        val recAgg = HashMap<String, RecAgg>()
        exec(
            """SELECT account_id,
                      COUNT(*) AS total,
                      SUM(CASE WHEN deleted THEN 1 ELSE 0 END) AS tombstones,
                      COALESCE(SUM(LENGTH(blob)), 0) AS bytes
               FROM records GROUP BY account_id""",
        ) { rs ->
            while (rs.next()) {
                recAgg[rs.getString("account_id")] =
                    RecAgg(rs.getInt("total"), rs.getInt("tombstones"), rs.getLong("bytes"))
            }
        }

        Accounts.selectAll()
            .orderBy(Accounts.createdAt to SortOrder.ASC)
            .map { row ->
                val id = row[Accounts.id]
                val d = devAgg[id] ?: DevAgg()
                val r = recAgg[id] ?: RecAgg()
                AccountSummary(
                    id = id,
                    createdAt = row[Accounts.createdAt],
                    syncSeq = row[Accounts.syncSeq],
                    devices = d.total,
                    activeDevices = d.active,
                    records = r.total,
                    tombstones = r.tombstones,
                    storageBytes = r.bytes,
                    lastSeenAt = d.lastSeen,
                )
            }
    }

    /**
     * Реальные envelope'ы записей аккаунта (свежие по серверному курсору первыми, с верхней
     * границей [limit]). [RecordEnvelope.previewHex] — первые 16 байт настоящего шифротекста: это
     * непрозрачный шум, который наглядно доказывает, что без dataKey содержимое нечитаемо.
     */
    fun recordEnvelopes(accountId: String, limit: Int = 100): List<RecordEnvelope> = transaction(db) {
        Records.selectAll()
            .where { Records.accountId eq accountId }
            .orderBy(Records.serverSeq to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                val bytes = row[Records.blob].bytes
                RecordEnvelope(
                    id = row[Records.recordId],
                    type = row[Records.type],
                    version = row[Records.version],
                    updatedAt = row[Records.updatedAt],
                    deviceId = row[Records.deviceId],
                    deleted = row[Records.deleted],
                    blobBytes = bytes.size,
                    serverSeq = row[Records.serverSeq],
                    previewHex = bytes.take(16).joinToString(" ") { b -> "%02x".format(b) },
                )
            }
    }

    /**
     * Физически удаляет tombstone'ы аккаунта, БЕЗОПАСНО: только те, чей `serverSeq` уже ниже
     * watermark = минимума курсоров всех устройств аккаунта. Так каждое устройство уже дочиталось
     * до удаления и не воскресит запись на следующем pull. Устройство без курсора (никогда не
     * синхронизировалось) тянет watermark к 0 → ничего не чистим. Аккаунт без устройств — чистим всё
     * (воскрешать некому). Возвращает число удалённых надгробий.
     */
    fun purgeTombstones(accountId: String): Int = transaction(db) {
        val cursors = Devices.selectAll()
            .where { Devices.accountId eq accountId }
            .map { it[Devices.lastSyncVersion] ?: 0L }
        val watermark = if (cursors.isEmpty()) Long.MAX_VALUE else cursors.min()
        Records.deleteWhere {
            (Records.accountId eq accountId) and (deleted eq true) and (serverSeq lessEq watermark)
        }
    }

    /**
     * Каскадно удаляет аккаунт со всеми его записями, устройствами и pairing-сессиями в одной
     * транзакции. Аудит-лог НЕ трогаем — он живёт без FK на [Accounts] и должен пережить удаление
     * (см. [ActivityLog]). Возвращает false, если аккаунта нет.
     */
    fun deleteAccount(accountId: String): Boolean = transaction(db) {
        val exists = Accounts.selectAll().where { Accounts.id eq accountId }.any()
        if (!exists) return@transaction false
        Records.deleteWhere { Records.accountId eq accountId }
        Pairing.deleteWhere { Pairing.accountId eq accountId }
        Devices.deleteWhere { Devices.accountId eq accountId }
        Accounts.deleteWhere { Accounts.id eq accountId }
        true
    }
}
