package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * Запись, пришедшая от клиента (ещё без серверного курсора).
 *
 * equals/hashCode переопределены вручную: автогенерация data-класса сравнивала бы [blob] по
 * ссылке, из-за чего две структурно одинаковые записи считались бы разными (security/kotlin-ревью).
 */
class IncomingRecord(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingRecord) return false
        return id == other.id && type == other.type && version == other.version &&
            updatedAt == other.updatedAt && deviceId == other.deviceId &&
            deleted == other.deleted && blob.contentEquals(other.blob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + deleted.hashCode()
        result = 31 * result + blob.contentHashCode()
        return result
    }
}

/**
 * Результат batch-upsert: победившие записи (в порядке входа) + новый курсор аккаунта. [changed] —
 * хоть одна запись выиграла LWW и курсор продвинулся; по нему PUT решает, слать ли WS-сигнал
 * (no-op push сигнал НЕ публикует, иначе live-sync уходит в петлю push→WS→push).
 */
data class UpsertResult(val records: List<StoredRecord>, val cursor: Long, val changed: Boolean)

/**
 * Зашифрованные записи vault. Содержит ядро разрешения конфликтов LWW
 * (`docs/skerry-sync-design.md` §3) и дельта-выборку по серверному курсору.
 *
 * [lockAccountRow] = true (PostgreSQL) берёт `SELECT … FOR UPDATE` на строку аккаунта, сериализуя
 * конкурентные upsert'ы и не давая двум транзакциям присвоить одинаковый `serverSeq`. Для SQLite
 * (pool=1, единственный писатель) блокировка не нужна.
 */
class RecordRepository(private val db: Database, private val lockAccountRow: Boolean = false) {

    /**
     * Batch upsert с LWW. Для каждой входящей записи принимается бóльшая по
     * (`version`, затем лексикографически `deviceId`); иначе остаётся серверная версия.
     * Возвращает победившее состояние каждой записи (с присвоенным `serverSeq`) и финальный
     * курсор — клиент по записям узнаёт, какие его изменения отвергнуты, по курсору двигает `since`.
     */
    suspend fun upsert(accountId: String, incoming: List<IncomingRecord>): UpsertResult = newSuspendedTransaction(Dispatchers.IO, db) {
        val accountQuery = Accounts.selectAll().where { Accounts.id eq accountId }
        // Курсор сравниваем с локально снятым seqBefore, НЕ с повторным чтением из БД: при
        // READ COMMITTED повторный SELECT увидел бы коммит конкурента и регрессировал курсор.
        val seqBefore = (if (lockAccountRow) accountQuery.forUpdate() else accountQuery).single()[Accounts.syncSeq]
        var seq = seqBefore

        val result = incoming.map { rec ->
            val existing = Records.selectAll()
                .where { (Records.accountId eq accountId) and (Records.recordId eq rec.id) }
                .singleOrNull()

            val wins = existing == null ||
                rec.version > existing[Records.version] ||
                (rec.version == existing[Records.version] && rec.deviceId > existing[Records.deviceId])

            if (wins) {
                seq += 1
                val newSeq = seq
                if (existing == null) {
                    Records.insert {
                        it[Records.accountId] = accountId
                        it[recordId] = rec.id
                        it[type] = rec.type
                        it[version] = rec.version
                        it[updatedAt] = rec.updatedAt
                        it[deviceId] = rec.deviceId
                        it[deleted] = rec.deleted
                        it[blob] = ExposedBlob(rec.blob)
                        it[serverSeq] = newSeq
                    }
                } else {
                    Records.update({ (Records.accountId eq accountId) and (Records.recordId eq rec.id) }) {
                        it[type] = rec.type
                        it[version] = rec.version
                        it[updatedAt] = rec.updatedAt
                        it[deviceId] = rec.deviceId
                        it[deleted] = rec.deleted
                        it[blob] = ExposedBlob(rec.blob)
                        it[serverSeq] = newSeq
                    }
                }
                StoredRecord(rec.id, rec.type, rec.version, rec.updatedAt, rec.deviceId, rec.deleted, rec.blob, newSeq)
            } else {
                existing.toStoredRecord()
            }
        }

        val changed = seq != seqBefore
        if (changed) {
            Accounts.update({ Accounts.id eq accountId }) { it[syncSeq] = seq }
        }
        UpsertResult(result, seq, changed)
    }

    /**
     * id надгробий аккаунта, уже распространённых на ВСЕ устройства — общий критерий
     * [propagatedTombstones] по [tombstoneWatermark] (тот же, что у [AdminRepository.purgeTombstones]).
     * Клиент по этому списку физически забывает тромбстоуны ([app.skerry.shared.vault.Vault.compact])
     * и перестаёт их пушить — иначе re-push воскрешал бы их на сервере после purge ("крот").
     */
    suspend fun compactedTombstoneIds(accountId: String): List<String> = newSuspendedTransaction(Dispatchers.IO, db) {
        Records.selectAll()
            .where { propagatedTombstones(accountId, tombstoneWatermark(accountId)) }
            .map { it[Records.recordId] }
    }

    /** Дельта: записи с `serverSeq > since`, по возрастанию курсора. */
    suspend fun delta(accountId: String, since: Long): List<StoredRecord> = newSuspendedTransaction(Dispatchers.IO, db) {
        Records.selectAll()
            .where { (Records.accountId eq accountId) and (Records.serverSeq greater since) }
            .orderBy(Records.serverSeq to SortOrder.ASC)
            .map { it.toStoredRecord() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toStoredRecord() = StoredRecord(
        id = this[Records.recordId],
        type = this[Records.type],
        version = this[Records.version],
        updatedAt = this[Records.updatedAt],
        deviceId = this[Records.deviceId],
        deleted = this[Records.deleted],
        blob = this[Records.blob].bytes,
        serverSeq = this[Records.serverSeq],
    )
}
