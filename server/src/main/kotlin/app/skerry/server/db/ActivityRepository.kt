package app.skerry.server.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** One row to append to the audit log (see [ActivityRepository.recordAll]). */
data class ActivityEvent(
    val accountId: String,
    val event: String,
    val detail: String,
    val deviceId: String? = null,
    val teamId: String? = null,
    val recordId: String? = null,
    val recordType: String? = null,
    val scopeId: String? = null,
    val durationSec: Long? = null,
)

/**
 * Metadata audit log: the admin console's recent activity plus each team's own history. Append-only
 * and bounded, so it can't grow without limit on a long-lived self-hosted instance. Contains no
 * record content — only the event, the device, ids, and a human-readable summary ([ActivityEvent.detail]).
 *
 * Retention is **per bucket**, not global: account-level rows keep the newest [maxRows], and every
 * team keeps the newest [teamMaxRows] of its own. That partition is what stops one team's traffic
 * from evicting another team's history — or the admin console's — which matters here because any
 * active member (a viewer included) can append to their team's bucket by reporting sessions. Within
 * one team's own bucket the window is still finite, as any bounded log's is; the endpoint that feeds
 * it is rate-limited per account so filling it takes sustained and plainly visible effort.
 */
class ActivityRepository(
    private val db: Database,
    private val maxRows: Int = 2_000,
    private val teamMaxRows: Int = 500,
) {

    suspend fun record(
        accountId: String,
        event: String,
        detail: String,
        deviceId: String? = null,
        teamId: String? = null,
        recordId: String? = null,
        recordType: String? = null,
        scopeId: String? = null,
        durationSec: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): Unit = recordAll(
        listOf(
            ActivityEvent(accountId, event, detail, deviceId, teamId, recordId, recordType, scopeId, durationSec),
        ),
        now,
    )

    /**
     * Appends [events] in **one** transaction: a batch describing a single action (one push, one
     * event per changed record) lands whole or not at all. Written row by row it could fail halfway,
     * and the client's retry is a no-op by LWW — the missing entries would never be written again,
     * leaving an audit trail that silently disagrees with the data.
     */
    suspend fun recordAll(events: List<ActivityEvent>, now: Long = System.currentTimeMillis()): Unit =
        dbTransaction(db) {
            if (events.isEmpty()) return@dbTransaction
            events.forEach { insert(it, now) }
            // Each bucket the batch touched, pruned once.
            events.map { it.teamId }.distinct().forEach { prune(it) }
        }

    /**
     * Records a client-reported session event, collapsing a repeat of the same (member, team, event,
     * record, space) inside [SESSION_DEDUP_MS]. Returns whether a row was written.
     *
     * A dropped link reopens a session as many times as it takes, and each attempt is a genuine
     * report — but writing them all would push the rest of the team's history out of the retention
     * window. The window is deliberately short: it swallows a reconnect storm, not a member coming
     * back to a host later. The space is part of the key because a record can move between spaces
     * (an unshare plus a share), and a session in its new space is a different fact.
     */
    suspend fun recordTeamSession(
        accountId: String,
        teamId: String,
        event: String,
        recordId: String,
        recordType: String?,
        scopeId: String,
        durationSec: Long?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = dbTransaction(db) {
        val last = ActivityLog.selectAll()
            .where {
                (ActivityLog.teamId eq teamId) and (ActivityLog.accountId eq accountId) and
                    (ActivityLog.event eq event) and (ActivityLog.recordId eq recordId) and
                    (ActivityLog.scopeId eq scopeId)
            }
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
        // Only a *recent* duplicate is dropped; a clock that jumped backwards must not wedge the
        // log shut, so a row stamped in the future is not treated as recent.
        if (last != null && now - last[ActivityLog.createdAt] in 0 until SESSION_DEDUP_MS) return@dbTransaction false
        insert(
            ActivityEvent(
                accountId = accountId,
                event = event,
                detail = "",
                teamId = teamId,
                recordId = recordId,
                recordType = recordType,
                scopeId = scopeId,
                durationSec = durationSec,
            ),
            now,
        )
        prune(teamId)
        true
    }

    /** Most recent events first (descending monotonic `seq`). */
    suspend fun recent(limit: Int = 50, offset: Long = 0): List<ActivityRow> = dbTransaction(db) {
        ActivityLog.selectAll()
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toRow() }
    }

    /** Most recent events for one team (team-scoped history for owner/admin members). */
    suspend fun recentForTeam(teamId: String, limit: Int = 100, offset: Long = 0): List<ActivityRow> =
        dbTransaction(db) {
            ActivityLog.selectAll()
                .where { ActivityLog.teamId eq teamId }
                .orderBy(ActivityLog.seq to SortOrder.DESC)
                .limit(limit).offset(offset)
                .map { it.toRow() }
        }

    /**
     * One account's own events, newest first — the account zone's log. Team-scoped rows are left
     * out: they belong to a team's history, which has its own endpoint and its own membership rules,
     * and picking them by "who acted" would show a member a slice of it filtered by nothing else.
     */
    suspend fun recentForAccount(accountId: String, limit: Int = 100, offset: Long = 0): List<ActivityRow> =
        dbTransaction(db) {
            ActivityLog.selectAll()
                .where { (ActivityLog.accountId eq accountId) and ActivityLog.teamId.isNull() }
                .orderBy(ActivityLog.seq to SortOrder.DESC)
                .limit(limit).offset(offset)
                .map { it.toRow() }
        }

    /** How many of them are retained, for an accurate "N of M" in the account zone. */
    suspend fun countForAccount(accountId: String): Long = dbTransaction(db) {
        ActivityLog.selectAll()
            .where { (ActivityLog.accountId eq accountId) and ActivityLog.teamId.isNull() }
            .count()
    }

    /** Retained events for one team, matching [recentForTeam] so a page and its total agree. */
    suspend fun countForTeam(teamId: String): Long = dbTransaction(db) {
        ActivityLog.selectAll().where { ActivityLog.teamId eq teamId }.count()
    }

    /** Total retained events (all buckets), for an accurate "N of M" in the console. */
    suspend fun count(): Long = dbTransaction(db) {
        ActivityLog.selectAll().count()
    }

    private fun insert(event: ActivityEvent, now: Long) {
        ActivityLog.insert {
            it[accountId] = event.accountId
            it[deviceId] = event.deviceId
            it[ActivityLog.event] = event.event
            it[detail] = event.detail
            it[teamId] = event.teamId
            it[recordId] = event.recordId
            it[recordType] = event.recordType
            it[scopeId] = event.scopeId
            it[durationSec] = event.durationSec
            it[createdAt] = now
        }
    }

    /**
     * Trims one bucket — a single team's rows, or the account-level ones ([teamId] null) — to its
     * cap, deleting the oldest beyond it. Gap-safe: the boundary is an actual `seq`, not arithmetic
     * on a row count.
     */
    private fun prune(teamId: String?) {
        val cap = if (teamId == null) maxRows else teamMaxRows
        fun bucket() =
            if (teamId == null) ActivityLog.selectAll().where { ActivityLog.teamId.isNull() }
            else ActivityLog.selectAll().where { ActivityLog.teamId eq teamId }
        // Cheap count-gate: only run the expensive OFFSET scan once the cap is actually exceeded,
        // not on every recorded event.
        if (bucket().count() <= cap) return
        val keepFrom = bucket()
            .orderBy(ActivityLog.seq to SortOrder.DESC)
            .limit(1).offset((cap - 1).toLong())
            .firstOrNull()?.get(ActivityLog.seq) ?: return
        if (teamId == null) {
            ActivityLog.deleteWhere { ActivityLog.teamId.isNull() and (seq lessEq keepFrom - 1) }
        } else {
            ActivityLog.deleteWhere { (ActivityLog.teamId eq teamId) and (seq lessEq keepFrom - 1) }
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRow() = ActivityRow(
        seq = this[ActivityLog.seq],
        accountId = this[ActivityLog.accountId],
        deviceId = this[ActivityLog.deviceId],
        event = this[ActivityLog.event],
        detail = this[ActivityLog.detail],
        createdAt = this[ActivityLog.createdAt],
        recordId = this[ActivityLog.recordId],
        recordType = this[ActivityLog.recordType],
        scopeId = this[ActivityLog.scopeId],
        durationSec = this[ActivityLog.durationSec],
    )

    companion object {
        /** How long a repeated session report of the same subject is treated as the same event. */
        const val SESSION_DEDUP_MS = 60_000L
    }
}
