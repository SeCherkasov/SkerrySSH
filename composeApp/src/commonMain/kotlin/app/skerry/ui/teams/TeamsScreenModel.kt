package app.skerry.ui.teams

import app.skerry.shared.team.TeamActivityDay
import app.skerry.shared.team.TeamActivityKind
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole

/**
 * One row of the member table: the member, the scopes they hold, and whether this viewer may act on
 * them. Built away from the composables so the ordering and the permission rules can be tested.
 */
data class TeamMemberRowUi(
    val member: TeamMember,
    /** The team's owner — never removable, and their role can't be changed. */
    val isOwner: Boolean,
    /** Names of the share spaces this member holds a grant on, in the team's own scope order. */
    val scopes: List<String>,
    /**
     * Whether [scopes] is the whole answer. False when an access list couldn't be read: an empty
     * list then means "unknown", not "holds nothing", and the table must not present it as a fact.
     */
    val scopesKnown: Boolean,
    /** The viewer may change this member's role or remove them (mirrors the server ACL). */
    val manageable: Boolean,
)

/**
 * Rows of the member table: active members first (owner, then admins, then editors, then viewers,
 * alphabetically inside a rank), invitees last — an invite is a pending state, not a seat.
 *
 * [scopeGrants] is `scopeId -> accounts holding it`, as the scope access lists report it; a viewer
 * who may not read those lists simply passes an empty map and the column stays empty.
 * [grantsComplete] is false when at least one of those lists failed to load — see
 * [TeamMemberRowUi.scopesKnown].
 */
fun teamMemberRows(
    team: TeamUi,
    members: List<TeamMember>,
    scopeGrants: Map<String, Set<String>>,
    canManage: Boolean,
    grantsComplete: Boolean = true,
): List<TeamMemberRowUi> {
    val scopeNames = team.scopes.associate { it.id to it.name }
    return members
        .sortedWith(compareBy({ it.status == TeamMemberStatus.INVITED }, { it.role.rank }, { it.accountId }))
        .map { member ->
            TeamMemberRowUi(
                member = member,
                isOwner = member.accountId == team.ownerAccountId,
                // Ordered by the team's scope list, not by the map: the same member must not get a
                // different chip order on every reread.
                scopes = team.scopes.mapNotNull { scope ->
                    scopeNames[scope.id]?.takeIf { member.accountId in scopeGrants[scope.id].orEmpty() }
                },
                scopesKnown = grantsComplete,
                manageable = canManage &&
                    member.accountId != team.ownerAccountId &&
                    canModifyMember(team.role, member.role),
            )
        }
}

/**
 * How many devices this team is reachable on: the paired devices of its active members. An invitee
 * has not adopted the team key yet, so their devices are not the team's.
 *
 * Null when the answer isn't knowable rather than a total that is silently short — a server that
 * doesn't report the per-member count, and an empty member list. Empty means the list hasn't landed
 * (first frame, no session, a failed call): a team the screen can draw always has at least its
 * owner, so "no active members" is never a fact about the team.
 */
fun teamDeviceCount(members: List<TeamMember>): Int? {
    val active = members.filter { it.status == TeamMemberStatus.ACTIVE }
    if (active.isEmpty() || active.any { it.devices == null }) return null
    return active.sumOf { it.devices ?: 0 }
}

/** Sort rank of a role, highest privilege first. */
private val TeamRole.rank: Int
    get() = when (this) {
        TeamRole.OWNER -> 0
        TeamRole.ADMIN -> 1
        TeamRole.EDITOR -> 2
        TeamRole.VIEWER -> 3
    }

/**
 * How a member's last activity reads in the table. The words are localized by the view; this decides
 * which of them applies and formats the time (UTC, like the rest of the Teams timestamps).
 */
sealed interface LastSeen {
    /** The server doesn't report it, or the account has never signed in on any device. */
    data object Never : LastSeen

    /** Within the last minute — including a clock a few seconds ahead of ours. */
    data object Now : LastSeen

    data class Today(val time: String) : LastSeen
    data class Yesterday(val time: String) : LastSeen

    /** Anything older: the full stamp, since "5 days ago" answers no question a table row asks. */
    data class Earlier(val stamp: String) : LastSeen
}

private const val JUST_NOW_MILLIS = 60_000L

/** Classifies [at] (epoch millis, null = never seen) against [now]. */
fun lastSeen(at: Long?, now: Long): LastSeen {
    if (at == null) return LastSeen.Never
    // A device clock ahead of ours is routine; a member "last seen in 4 seconds" is not a thing.
    if (at >= now - JUST_NOW_MILLIS) return LastSeen.Now
    val stamp = formatEpochUtc(at)
    val time = stamp.substringAfter(' ')
    return when (now.floorDiv(MILLIS_PER_DAY) - at.floorDiv(MILLIS_PER_DAY)) {
        0L -> LastSeen.Today(time)
        1L -> LastSeen.Yesterday(time)
        else -> LastSeen.Earlier(stamp)
    }
}

/**
 * How long ago the team last finished a sync, bucketed for the header pill. A unit rather than a
 * formatted string so the thresholds can be tested; the wording is [syncedAgoText]'s job.
 */
sealed interface SyncedAgo {
    data class Seconds(val value: Long) : SyncedAgo
    data class Minutes(val value: Long) : SyncedAgo
    data class Hours(val value: Long) : SyncedAgo
    data class Days(val value: Long) : SyncedAgo
}

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60 * SECOND_MS
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** Buckets [elapsedMs]; a negative value (a server clock ahead of ours) reads as zero seconds. */
fun syncedAgo(elapsedMs: Long): SyncedAgo {
    val elapsed = elapsedMs.coerceAtLeast(0)
    return when {
        elapsed < MINUTE_MS -> SyncedAgo.Seconds(elapsed / SECOND_MS)
        elapsed < HOUR_MS -> SyncedAgo.Minutes(elapsed / MINUTE_MS)
        elapsed < DAY_MS -> SyncedAgo.Hours(elapsed / HOUR_MS)
        else -> SyncedAgo.Days(elapsed / DAY_MS)
    }
}

/**
 * When the team's keys were last rotated, from the feed the screen already holds — a rotation is an
 * audited event, so there is nothing to ask the server for. Null for a team that was never rekeyed
 * (or whose feed this member may not read).
 */
fun lastRekeyAt(feed: List<TeamActivityDay>): Long? = feed
    .flatMap { it.rows }
    .filter { it.kind == TeamActivityKind.KEY_ROTATE || it.kind == TeamActivityKind.SCOPE_KEY_ROTATE }
    .maxOfOrNull { it.createdAt }
