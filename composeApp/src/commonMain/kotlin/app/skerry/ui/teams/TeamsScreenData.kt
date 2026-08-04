package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.skerry.shared.sync.AccountSummary
import app.skerry.shared.team.TeamActivityDay
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.buildTeamActivityFeed
import app.skerry.shared.vault.RecordType
import app.skerry.ui.app.LocalSharedSessions
import app.skerry.ui.app.LocalSync
import app.skerry.ui.sync.serverHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// What the desktop and the phone Teams screens both need to show: the activity feed and the numbers
// behind the summary cards. Kept in one place so the two platforms can't drift into reporting
// different counts for the same team.

/**
 * Who holds which scope, as far as this device could find out: [byScope] is `scopeId -> accounts`,
 * and [complete] is false when at least one access list failed to load, so the member table can say
 * "unknown" instead of "nobody".
 */
internal data class ScopeGrants(val byScope: Map<String, Set<String>>, val complete: Boolean)

/**
 * Scope access lists of the team. Manager-only on the server, so anyone else gets an empty (and
 * complete) answer rather than a failed call and an error banner over a column they can't see.
 */
@Composable
internal fun scopeGrants(tc: TeamsCoordinator, team: TeamUi, tick: Int, canManage: Boolean): ScopeGrants? {
    // Null until the first answer arrives: during the load the table must claim neither "holds
    // nothing" nor "unknown" — both would be a statement about access it hasn't read yet.
    val grants by produceState<ScopeGrants?>(null, team.id, team.scopes, tick, canManage) {
        value = if (!canManage) {
            ScopeGrants(emptyMap(), true)
        } else {
            // One round-trip per scope, run together: sequentially, a team with five scopes would
            // leave the member table's scope column five round-trips behind after every operation.
            val read = coroutineScope {
                team.scopes.map { scope -> async { scope.id to tc.scopeGrants(team.id, scope.id) } }.awaitAll()
            }
            ScopeGrants(
                byScope = read.mapNotNull { (scopeId, accounts) -> accounts?.let { scopeId to it.toSet() } }.toMap(),
                complete = read.none { (_, accounts) -> accounts == null },
            )
        }
    }
    return grants
}

/** The team's activity feed, for members allowed to read it; an empty list for everyone else. */
@Composable
internal fun teamFeed(tc: TeamsCoordinator, team: TeamUi, tick: Int, canAudit: Boolean): List<TeamActivityDay> {
    val entries by produceState(emptyList<TeamActivityEntry>(), team.id, tick, canAudit) {
        value = if (canAudit) tc.teamActivity(team.id) else emptyList()
    }
    val recordNames by produceState(emptyMap<String, Map<String, String>>(), team.id, tick, canAudit) {
        value = if (canAudit) withContext(Dispatchers.Default) { tc.sharedRecordNames(team.id) } else emptyMap()
    }
    val scopeNames = remember(team.scopes) { team.scopes.associate { it.id to it.name } }
    return remember(entries, recordNames, scopeNames) {
        buildTeamActivityFeed(
            entries = entries,
            selfAccountId = tc.selfAccountId(),
            resolveRecordName = { scope, recordId -> recordNames[scope]?.get(recordId) },
            resolveScopeName = { scope -> scopeNames[scope] },
        )
    }
}

/** What the Server card knows: where this account syncs, and what the instance reports about itself. */
private data class ServerFacts(val endpoint: String?, val summary: AccountSummary?)

/** Counts and server facts behind the three summary cards. */
@Composable
internal fun teamCards(tc: TeamsCoordinator, team: TeamUi, scopeId: String, tick: Int, feed: List<TeamActivityDay>): TeamCards {
    val sync = LocalSync.current
    val shares = LocalSharedSessions.current
    val counts = remember(team.id, scopeId, tick) {
        listOf(RecordType.HOST, RecordType.SNIPPET, RecordType.RUNBOOK)
            .associateWith { tc.sharedRecordIds(team.id, it).size }
    }
    // savedConfig re-reads the config file, so it belongs here beside the network call rather than
    // in the composition — read straight from the body it would touch disk on every recomposition.
    val server by produceState<ServerFacts?>(null, sync, tick) {
        value = sync?.let { coordinator ->
            ServerFacts(
                endpoint = withContext(Dispatchers.Default) { serverHost(coordinator.savedConfig?.serverUrl) },
                summary = coordinator.serverSummary(),
            )
        }
    }
    return TeamCards(
        items = counts.values.sum(),
        lastRekeyAt = lastRekeyAt(feed),
        endpoint = server?.endpoint,
        serverVersion = server?.summary?.serverVersion,
        devices = server?.summary?.devices,
        hosts = counts[RecordType.HOST] ?: 0,
        snippets = counts[RecordType.SNIPPET] ?: 0,
        runbooks = counts[RecordType.RUNBOOK] ?: 0,
        liveSessions = shares?.shares?.count { it.teamId == team.id } ?: 0,
    )
}
