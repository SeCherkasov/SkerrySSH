package app.skerry.server.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * In-memory bus for "account has changes" WS push (`docs/skerry-sync-design.md` §3: push with no
 * payload). Carries only accountId and the new cursor, no data. Single self-hosted instance model;
 * horizontal scaling would need an external broker.
 */
class ChangeNotifier {
    /**
     * [channel]: `acc:{accountId}` — account vault cursor; `team:{teamId}` — team record cursor;
     * `member:{accountId}` — membership/invites changed (cursor is meaningless, 0).
     */
    data class Change(val channel: String, val cursor: Long)

    data class TeamChange(val teamId: String, val cursor: Long)

    // replay=0 + DROP_OLDEST: cursor notifications are idempotent, so a slow subscriber drops old
    // events instead of blocking the publisher (publish is called directly from the PUT
    // /vault/records handler, which must not wait on a WS client; kotlin-review HIGH-2).
    private val flow = MutableSharedFlow<Change>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Non-suspend: tryEmit never blocks the publisher (buffer with DROP_OLDEST). */
    fun publish(accountId: String, cursor: Long) {
        flow.tryEmit(Change("acc:$accountId", cursor))
    }

    /** Signals "team has records up to cursor" for active members' WS sessions. */
    fun publishTeam(teamId: String, cursor: Long) {
        flow.tryEmit(Change("team:$teamId", cursor))
    }

    /** Signals "account's team membership/invites changed"; client re-reads the team list. */
    fun publishMembership(accountId: String) {
        flow.tryEmit(Change("member:$accountId", 0))
    }

    /** Cursor stream for a specific account (one WS session). */
    fun forAccount(accountId: String): Flow<Long> =
        flow.filter { it.channel == "acc:$accountId" }.map { it.cursor }

    /** All team signals; filtering by membership happens on the WS session side (membership changes). */
    fun teamChanges(): Flow<TeamChange> =
        flow.filter { it.channel.startsWith("team:") }
            .map { TeamChange(it.channel.removePrefix("team:"), it.cursor) }

    /** Membership-change signals for a specific account. */
    fun forMembership(accountId: String): Flow<Unit> =
        flow.filter { it.channel == "member:$accountId" }.map { }

    /**
     * Number of active bus subscribers (all accounts). Observability for WS session tests: closing
     * the socket client-side should release the subscription rather than hold collect until the next publish.
     */
    val subscriptions: StateFlow<Int> get() = flow.subscriptionCount
}
