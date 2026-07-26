package app.skerry.shared.team

import app.skerry.shared.vault.RecordType

/**
 * What a team event is, resolved from the server's event code. The code stays on the row ([UNKNOWN]
 * carries it) so a client older than the server it talks to shows the event instead of hiding it.
 */
enum class TeamActivityKind {
    TEAM_CREATE, TEAM_DELETE,
    MEMBER_INVITE, MEMBER_JOIN, MEMBER_REMOVE, MEMBER_ROLE,
    KEY_ROTATE,
    SCOPE_CREATE, SCOPE_DELETE, SCOPE_GRANT, SCOPE_REVOKE, SCOPE_KEY_ROTATE,

    /** One record shared into the space, edited there, or taken out of it. */
    RECORD_SHARE, RECORD_CHANGE, RECORD_REMOVE,

    /** A push too large to list record by record (a key rotation re-encrypts the whole space). */
    RECORDS_BULK,

    /** Client-reported: a session was opened on a shared host, or a recording of one was saved. */
    SESSION_OPEN, SESSION_RECORD,

    UNKNOWN,
    ;

    /** Which filter chip the event belongs to; [TeamActivityCategory.ALL] never filters anything out. */
    val category: TeamActivityCategory
        get() = when (this) {
            RECORD_SHARE, RECORD_CHANGE, RECORD_REMOVE, RECORDS_BULK -> TeamActivityCategory.RECORDS
            TEAM_CREATE, TEAM_DELETE, MEMBER_INVITE, MEMBER_JOIN, MEMBER_REMOVE, MEMBER_ROLE ->
                TeamActivityCategory.MEMBERS
            KEY_ROTATE, SCOPE_CREATE, SCOPE_DELETE, SCOPE_GRANT, SCOPE_REVOKE, SCOPE_KEY_ROTATE ->
                TeamActivityCategory.ACCESS
            SESSION_OPEN, SESSION_RECORD -> TeamActivityCategory.SESSIONS
            UNKNOWN -> TeamActivityCategory.ALL
        }

    /**
     * Whether the event was asserted by a member's client rather than observed by the server. The
     * server has no part in an SSH connection, so a session event is a collaboration signal, not
     * proof — the feed says so rather than presenting the two on equal footing.
     */
    val clientReported: Boolean get() = this == SESSION_OPEN || this == SESSION_RECORD
}

/** Filter chips over the feed. [ALL] keeps everything, including events this client can't name. */
enum class TeamActivityCategory { ALL, RECORDS, MEMBERS, ACCESS, SESSIONS }

/**
 * One readable line of the feed. [subject] is the record's name resolved from the member's own copy
 * of the share space, falling back to a short id when the name is gone ([subjectResolved] says
 * which) — an unshared record leaves a tombstone with no payload, and "someone removed something"
 * still belongs in an audit log. [detail] is the server's raw summary, kept for [TeamActivityKind
 * .UNKNOWN] and bulk rows.
 */
class TeamActivityRow(
    val kind: TeamActivityKind,
    /** Raw server event code — for [TeamActivityKind.UNKNOWN], the only thing there is to show. */
    val event: String,
    val actorAccountId: String,
    /** The actor is this account (any of its devices), so the UI can say "you". */
    val isSelf: Boolean,
    val createdAt: Long,
    val recordId: String? = null,
    val recordType: RecordType? = null,
    val subject: String? = null,
    val subjectResolved: Boolean = false,
    /** Name of the scope the event happened in; null for the team-wide space. */
    val scopeName: String? = null,
    val detail: String = "",
    val durationSec: Long? = null,
) {
    val clientReported: Boolean get() = kind.clientReported
}

/** Rows of one UTC day, newest first. [dayIndex] is whole days since the epoch (a grouping key). */
class TeamActivityDay(val dayIndex: Long, val rows: List<TeamActivityRow>)

/** Length of the id prefix standing in for a name that can no longer be resolved. */
private const val SHORT_ID_LENGTH = 8

private const val MILLIS_PER_DAY = 86_400_000L

private val EVENT_KINDS = mapOf(
    "team.create" to TeamActivityKind.TEAM_CREATE,
    "team.delete" to TeamActivityKind.TEAM_DELETE,
    "team.invite" to TeamActivityKind.MEMBER_INVITE,
    "team.accept" to TeamActivityKind.MEMBER_JOIN,
    "team.remove" to TeamActivityKind.MEMBER_REMOVE,
    "team.role_change" to TeamActivityKind.MEMBER_ROLE,
    "team.rekey" to TeamActivityKind.KEY_ROTATE,
    "team.scope_create" to TeamActivityKind.SCOPE_CREATE,
    "team.scope_delete" to TeamActivityKind.SCOPE_DELETE,
    "team.scope_grant" to TeamActivityKind.SCOPE_GRANT,
    "team.scope_revoke" to TeamActivityKind.SCOPE_REVOKE,
    "team.scope_rekey" to TeamActivityKind.SCOPE_KEY_ROTATE,
    "team.record_share" to TeamActivityKind.RECORD_SHARE,
    "team.record_change" to TeamActivityKind.RECORD_CHANGE,
    "team.record_remove" to TeamActivityKind.RECORD_REMOVE,
    // Written by this server for an oversized push, and by any server older than per-record events.
    "team.push" to TeamActivityKind.RECORDS_BULK,
    "team.session_open" to TeamActivityKind.SESSION_OPEN,
    "team.session_record" to TeamActivityKind.SESSION_RECORD,
)

/**
 * Builds the members' activity feed out of the server's audit metadata: names the events, resolves
 * record and scope names locally (the server holds neither), and groups the result by UTC day,
 * newest first.
 *
 * The resolvers are the zero-knowledge seam. [resolveRecordName] is handed `(scopeId, recordId)` and
 * looks the record up in that share space's own vault — which is why the same id may be nameless for
 * one reader (a manager holding no grant on the scope) and named for another, and why a name never
 * has to travel through the server for this feed to be readable.
 *
 * [onlyRecordId] narrows the feed to one record's history ("who touched this host"); a bulk summary
 * covers a whole space and is dropped from it, since it says nothing about that record in particular.
 */
fun buildTeamActivityFeed(
    entries: List<TeamActivityEntry>,
    selfAccountId: String?,
    category: TeamActivityCategory = TeamActivityCategory.ALL,
    onlyRecordId: String? = null,
    resolveRecordName: (scopeId: String, recordId: String) -> String? = { _, _ -> null },
    resolveScopeName: (scopeId: String) -> String? = { null },
): List<TeamActivityDay> = entries
    .asSequence()
    .filter { onlyRecordId == null || it.recordId == onlyRecordId }
    .map { entry -> entry.toRow(selfAccountId, resolveRecordName, resolveScopeName) }
    .filter { category == TeamActivityCategory.ALL || it.kind.category == category }
    .toList()
    // Stable, so events written in the same millisecond (one push logs several) keep the server's
    // own order — its descending sequence is the only ordering they have.
    .sortedByDescending { it.createdAt }
    .groupBy { it.createdAt.floorDiv(MILLIS_PER_DAY) }
    .map { (day, rows) -> TeamActivityDay(day, rows) }
    .sortedByDescending { it.dayIndex }

private fun TeamActivityEntry.toRow(
    selfAccountId: String?,
    resolveRecordName: (String, String) -> String?,
    resolveScopeName: (String) -> String?,
): TeamActivityRow {
    val scope = scopeId.orEmpty()
    val name = recordId?.let { resolveRecordName(scope, it) }
    return TeamActivityRow(
        kind = EVENT_KINDS[event] ?: TeamActivityKind.UNKNOWN,
        event = event,
        actorAccountId = actorAccountId,
        isSelf = selfAccountId != null && actorAccountId == selfAccountId,
        createdAt = createdAt,
        recordId = recordId,
        recordType = recordType?.let { type -> RecordType.entries.firstOrNull { it.name == type } },
        subject = name ?: recordId?.take(SHORT_ID_LENGTH),
        subjectResolved = name != null,
        // The team-wide space has no name of its own; only a scope is worth pointing at.
        scopeName = scope.takeIf { it.isNotEmpty() }?.let { resolveScopeName(it) ?: it },
        detail = detail,
        durationSec = durationSec,
    )
}
