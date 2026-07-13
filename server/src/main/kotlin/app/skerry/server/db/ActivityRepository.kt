package app.skerry.server.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Metadata audit log for the admin console. Append-only, retains the last [maxRows] events so
 * the log doesn't grow unbounded on a long-lived self-hosted instance. Contains no record
 * content — only event, device, and a human-readable summary ([detail]).
 */
class ActivityRepository(private val db: Database, private val maxRows: Int = 2_000) {

    suspend fun record(
        accountId: String,
        event: String,
        detail: String,
        deviceId: String? = null,
        teamId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Unit = dbTransaction(db) {
        ActivityLog.insert {
            it[ActivityLog.accountId] = accountId
            it[ActivityLog.deviceId] = deviceId
            it[ActivityLog.event] = event
            it[ActivityLog.detail] = detail
            it[ActivityLog.teamId] = teamId
            it[createdAt] = now
        }
        prune()
    }

    /** Most recent events first (descending monotonic `seq`). */
    suspend fun recent(limit: Int = 50): List<ActivityRow> = dbTransaction(db) {
        ActivityLog.selectAll()
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    /** Most recent events for one team (team-scoped history for owner/admin members). */
    suspend fun recentForTeam(teamId: String, limit: Int = 100): List<ActivityRow> =
        dbTransaction(db) {
            ActivityLog.selectAll()
                .where { ActivityLog.teamId eq teamId }
                .orderBy(ActivityLog.seq to SortOrder.DESC)
                .limit(limit)
                .map { it.toRow() }
        }

    /** Total retained events (≤ maxRows), for an accurate "N of M" in the console. */
    suspend fun count(): Long = dbTransaction(db) {
        ActivityLog.selectAll().count()
    }

    /** Deletes everything older than the last [maxRows] events (gap-safe: bounded by actual seq). */
    private fun prune() {
        // Cheap count-gate: only run the expensive OFFSET scan once the cap is actually
        // exceeded, not on every recorded event.
        if (ActivityLog.selectAll().count() <= maxRows) return
        val keepFrom = ActivityLog.selectAll()
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(1).offset((maxRows - 1).toLong())
            .firstOrNull()?.get(ActivityLog.seq) ?: return
        ActivityLog.deleteWhere { seq lessEq keepFrom - 1 }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRow() = ActivityRow(
        seq = this[ActivityLog.seq],
        accountId = this[ActivityLog.accountId],
        deviceId = this[ActivityLog.deviceId],
        event = this[ActivityLog.event],
        detail = this[ActivityLog.detail],
        createdAt = this[ActivityLog.createdAt],
    )
}
