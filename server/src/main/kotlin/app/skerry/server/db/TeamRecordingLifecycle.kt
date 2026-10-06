package app.skerry.server.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Same lock order for reservation, completion, key rotation and cascades: team, then scope. */
internal fun JdbcTransaction.lockRecordingTeam(teamId: String): ResultRow? {
    val query = Teams.selectAll().where { Teams.id eq teamId }
    return (if (currentDialect is PostgreSQLDialect) query.forUpdate() else query).singleOrNull()
}

internal fun JdbcTransaction.lockRecordingScope(teamId: String, scopeId: String): ResultRow? {
    val query = TeamScopes.selectAll().where {
        (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId)
    }
    return (if (currentDialect is PostgreSQLDialect) query.forUpdate() else query).singleOrNull()
}

/** Called while holding the team lock, so no completion can enter after this barrier. */
internal fun recordingWrapsStaged(teamId: String, scopeId: String, epoch: Long, now: Long): Boolean =
    !TeamRecordings.select(TeamRecordings.recordingId).where {
        (TeamRecordings.teamId eq teamId) and (TeamRecordings.scopeId eq scopeId) and
            (TeamRecordings.ready eq true) and (TeamRecordings.expiresAt greater now) and
            (TeamRecordings.stagedWrapEpoch.isNull() or (TeamRecordings.stagedWrapEpoch neq epoch) or
                TeamRecordings.stagedWrappedKey.isNull())
    }.limit(1).any()
