package app.skerry.server.db

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Аудит-лог метаданных для админ-консоли. Append-only с удержанием последних [maxRows] событий,
 * чтобы лог не рос безгранично на долгоживущем self-hosted инстансе. Содержимого записей здесь
 * нет по определению — только событие, устройство и человекочитаемая сводка ([detail]).
 */
class ActivityRepository(private val db: Database, private val maxRows: Int = 2_000) {

    fun record(
        accountId: String,
        event: String,
        detail: String,
        deviceId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Unit = transaction(db) {
        ActivityLog.insert {
            it[ActivityLog.accountId] = accountId
            it[ActivityLog.deviceId] = deviceId
            it[ActivityLog.event] = event
            it[ActivityLog.detail] = detail
            it[createdAt] = now
        }
        prune()
    }

    /** Свежие события первыми (по убыванию монотонного `seq`). */
    fun recent(limit: Int = 50): List<ActivityRow> = transaction(db) {
        ActivityLog.selectAll()
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    /** Удаляет всё старше последних [maxRows] событий (gap-устойчиво: по реальному seq границы). */
    private fun prune() {
        // Дешёвый счётчик-гейт: дорогой OFFSET-скан только когда реально переросли кап, а не на
        // каждый записанный event (kotlin-ревью L).
        if (ActivityLog.selectAll().count() <= maxRows) return
        val keepFrom = ActivityLog.selectAll()
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(1).offset((maxRows - 1).toLong())
            .firstOrNull()?.get(ActivityLog.seq) ?: return
        ActivityLog.deleteWhere { seq lessEq keepFrom - 1 }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRow() = ActivityRow(
        seq = this[ActivityLog.seq],
        accountId = this[ActivityLog.accountId],
        deviceId = this[ActivityLog.deviceId],
        event = this[ActivityLog.event],
        detail = this[ActivityLog.detail],
        createdAt = this[ActivityLog.createdAt],
    )
}
