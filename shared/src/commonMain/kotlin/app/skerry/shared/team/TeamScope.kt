package app.skerry.shared.team

/**
 * Identity of a keyed share space inside a team: the team itself ([scopeId] empty — every active
 * member reads it) or one of its scopes (only members holding a grant read it).
 *
 * A scope is deliberately not a subsystem of its own: it has exactly what a team has — a key, an
 * epoch, sealed envelopes, a vault file, a sync cursor — so all of that machinery is parameterized
 * by this reference instead of duplicated. `scopeId` is client-generated and constrained to the same
 * charset as a teamId, because both end up in a file name.
 */
data class TeamScopeRef(val teamId: String, val scopeId: String = "") {

    val isTeamWide: Boolean get() = scopeId.isEmpty()

    /** Stable key for maps and sync cursors: `team-1` or `team-1/prod`. */
    val key: String get() = if (isTeamWide) teamId else "$teamId/$scopeId"

    /** Vault file name. `__` can't appear inside an id (see [isSafeId]), so the split is unambiguous. */
    val fileName: String get() = if (isTeamWide) "$teamId.vault" else "${teamId}__$scopeId.vault"

    companion object {
        /** Team and scope ids are client-generated: `[a-z0-9-]` only, or the file name becomes a path injection. */
        fun isSafeId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 64 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}

/** A team scope as the server reports it to one account. */
class TeamScopeSummary(
    val scopeId: String,
    /** Current scopeKey generation; a revoke-driven rotation bumps it (same contract as the team epoch). */
    val keyEpoch: Long,
    /** Number of accounts holding a grant (managers see it for every scope). */
    val memberCount: Int,
    /**
     * Our sealed scopeKey, or null when we hold no grant. Unlike an invite envelope this is NOT
     * cleared after adoption: it is the recovery path when the local TEAM record loses its scope map
     * (e.g. an older client of the same account rewrote that record without the `scopes` field).
     */
    val envelope: ByteArray?,
)

/** An account's grant on a scope (manager view). */
class TeamScopeGrantEntry(val accountId: String, val createdAt: Long)
