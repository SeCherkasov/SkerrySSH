package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.metrics.SyncScope
import app.skerry.server.metrics.TeamDenial
import app.skerry.server.accountId
import app.skerry.server.db.ActivityEvent
import app.skerry.server.db.AppliedTeamRecord
import app.skerry.server.db.RekeyOutcome
import app.skerry.server.db.TeamMemberRow
import app.skerry.server.db.TeamRecordChange
import app.skerry.server.db.TeamMemberStatus
import app.skerry.server.db.TeamRoles
import app.skerry.server.RateLimits
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.b64
import app.skerry.server.model.toDto
import app.skerry.server.model.toIncoming
import app.skerry.server.model.unb64
import app.skerry.sync.wire.AccountKeyResponse
import app.skerry.sync.wire.PublishKeyRequest
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.PushResponse
import app.skerry.sync.wire.RecordsResponse
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamDto
import app.skerry.sync.wire.TeamInviteRequest
import app.skerry.sync.wire.TeamMemberDto
import app.skerry.sync.wire.TeamActivityDto
import app.skerry.sync.wire.TeamActivityResponse
import app.skerry.sync.wire.TeamMembersResponse
import app.skerry.sync.wire.TeamRekeyRequest
import app.skerry.sync.wire.TeamRoleChangeRequest
import app.skerry.sync.wire.TeamSessionEventRequest
import app.skerry.sync.wire.TeamsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Types synced in team scope: secrets and structure, excluding SETTINGS/KNOWN_HOST (per-account). */
private val TEAM_ALLOWED_TYPES = setOf("HOST", "GROUP", "IDENTITY", "CREDENTIAL", "SNIPPET", "RUNBOOK", "TUNNEL")

/** Public X25519 key is exactly 32 bytes; a crypto_box_seal envelope is 48 bytes overhead + payload. */
private const val PUBLIC_KEY_BYTES = 32
internal const val MAX_ENVELOPE_BYTES = 4096

/**
 * Teams: account keys, team membership, and team-scoped records. Zero-knowledge: the server
 * stores only metadata (membership, roles) and ciphertext (invite envelopes, records under
 * teamKey). ACL (granular roles owner>admin>editor>viewer, see [TeamRoles]): owner deletes the
 * team; owner/admin manage membership and roles; owner/admin/editor write records; all active
 * members read.
 */
fun Route.teamRoutes(services: Services) {
    put("/account/key") {
        val principal = call.jwtPrincipal()
        val req = call.receive<PublishKeyRequest>()
        val key = req.publicKey.unb64()
        val signKey = req.signPublicKey.unb64()
        if (key.size != PUBLIC_KEY_BYTES) throw BadRequestException("publicKey must be $PUBLIC_KEY_BYTES bytes")
        if (signKey.size != PUBLIC_KEY_BYTES) throw BadRequestException("signPublicKey must be $PUBLIC_KEY_BYTES bytes")
        services.teams.publishKey(principal.accountId, key, signKey, System.currentTimeMillis())
        call.respond(HttpStatusCode.OK)
    }

    get("/account/keys/{accountId}") {
        call.jwtPrincipal()
        val target = call.requiredPathId("accountId") ?: return@get
        if (target.length > MAX_ACCOUNT_ID) throw BadRequestException("accountId too long")
        val keys = services.teams.accountKeys(target)
        // A legacy row without a signing key is unusable for signed invites; report it as unpublished
        // (the owner republishes both keys on next login), so invitees never adopt a half-identity.
        if (keys?.signPublicKey == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no published key for account"))
            return@get
        }
        call.respond(AccountKeyResponse(target, keys.publicKey.b64(), keys.signPublicKey.b64()))
    }

    post("/teams") {
        val principal = call.jwtPrincipal()
        val req = call.receive<TeamCreateRequest>()
        // Same charset and length as a scope id, and for the same reason: the id becomes a vault
        // file name on every member's device, so a client refuses anything else (TeamScopeRef
        // .isSafeId) — and anything past varchar(64) fails the insert as a 500 rather than a 400.
        if (!isSafeTeamId(req.teamId)) throw BadRequestException("bad teamId")
        if (!services.teams.create(req.teamId, principal.accountId, System.currentTimeMillis())) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("team already exists"))
            return@post
        }
        services.activity.record(principal.accountId, "team.create", req.teamId, teamId = req.teamId)
        call.respond(HttpStatusCode.Created)
    }

    get("/teams") {
        val principal = call.jwtPrincipal()
        val teams = services.teams.teamsFor(principal.accountId).map { view ->
            TeamDto(
                id = view.team.id,
                ownerAccountId = view.team.ownerAccountId,
                role = view.role,
                status = view.status,
                createdAt = view.team.createdAt,
                memberCount = view.memberCount,
                envelope = view.envelope?.b64(),
                keyEpoch = view.team.keyEpoch,
                keyEnvelope = view.keyEnvelope?.b64(),
            )
        }
        call.respond(TeamsResponse(teams))
    }

    delete("/teams/{id}") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@delete
        val members = services.teams.members(teamId)
        call.requireActiveMember(
            services, teamId, principal.accountId, { it == TeamRoles.OWNER }, "owner role required",
        ) ?: return@delete
        services.teams.deleteTeam(teamId)
        services.activity.record(principal.accountId, "team.delete", teamId, teamId = teamId)
        // Notify all former members that membership changed so they re-read the team list.
        members.forEach { services.notifier.publishMembership(it.accountId) }
        call.respond(HttpStatusCode.OK)
    }

    get("/teams/{id}/members") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        call.requireActiveMember(services, teamId, principal.accountId) ?: return@get
        val rows = services.teams.members(teamId)
        val devices = services.teams.devicesByAccount(rows.map { it.accountId })
        val members = rows.map {
            val d = devices[it.accountId]
            TeamMemberDto(
                accountId = it.accountId,
                role = it.role,
                status = it.status,
                createdAt = it.createdAt,
                lastSeenAt = d?.lastSeenAt,
                // Only for a member who actually joined: an invite is addressed by e-mail and costs
                // the inviter nothing, so reporting a stranger's device count would make the members
                // list a probe. The screen sums active members anyway.
                devices = if (it.status == TeamMemberStatus.ACTIVE) d?.active ?: 0 else null,
            )
        }
        call.respond(TeamMembersResponse(members))
    }

    post("/teams/{id}/members") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        val req = call.receive<TeamInviteRequest>()
        if (req.accountId.isBlank() || req.accountId.length > MAX_ACCOUNT_ID) throw BadRequestException("bad accountId")
        val envelope = req.envelope.unb64()
        if (envelope.isEmpty() || envelope.size > MAX_ENVELOPE_BYTES) throw BadRequestException("bad envelope")
        val membership = call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@post
        // Anti-escalation: can't invite with a role above the actor's own rights (e.g. admin -> admin/owner).
        if (!TeamRoles.canAssign(membership.role, req.role)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot assign role '${req.role}'"))
            return@post
        }
        if (services.accounts.find(req.accountId) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
            return@post
        }
        if (!services.teams.invite(teamId, req.accountId, req.role, envelope, principal.accountId, System.currentTimeMillis())) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("already a member or invited"))
            return@post
        }
        services.activity.record(principal.accountId, "team.invite", "${req.accountId} · ${req.role}", teamId = teamId)
        services.notifier.publishMembership(req.accountId)
        call.respond(HttpStatusCode.Created)
    }

    put("/teams/{id}/members/{accountId}/role") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@put
        val target = call.requiredPathId("accountId") ?: return@put
        val req = call.receive<TeamRoleChangeRequest>()
        val actor = call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@put
        val targetMember = services.teams.membership(teamId, target)
        if (targetMember == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such member"))
            return@put
        }
        // Anti-escalation: the actor must have rights over both the target's current role and the new one.
        if (!TeamRoles.canModifyMember(actor.role, targetMember.role) || !TeamRoles.canAssign(actor.role, req.role)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot set role '${req.role}'"))
            return@put
        }
        if (!services.teams.updateRole(teamId, target, req.role)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such member (owner role is fixed)"))
            return@put
        }
        services.activity.record(principal.accountId, "team.role_change", "$target → ${req.role}", teamId = teamId)
        services.notifier.publishMembership(target)
        call.respond(HttpStatusCode.OK)
    }

    post("/teams/{id}/rekey") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        val req = call.receive<TeamRekeyRequest>()
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@post
        val members = services.teams.members(teamId).associateBy { it.accountId }
        val envelopes = mutableMapOf<String, ByteArray>()
        for (e in req.envelopes) {
            val blob = e.envelope.unb64()
            if (blob.isEmpty() || blob.size > MAX_ENVELOPE_BYTES) throw BadRequestException("bad envelope")
            if (e.accountId !in members) throw BadRequestException("not a member: ${e.accountId}")
            envelopes[e.accountId] = blob
        }
        // Monotonic epoch: exactly one step past the current one, enforced as an atomic compare-and-set
        // inside rekey (guards a stale replay and concurrent double-rotations racing to the same epoch —
        // a route-level read-then-write here would be a TOCTOU, see TeamRepository.rekey).
        when (services.teams.rekey(teamId, req.newEpoch, envelopes)) {
            RekeyOutcome.NO_TEAM -> {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no such team"))
                return@post
            }
            RekeyOutcome.EPOCH_CONFLICT -> {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("stale epoch (concurrent rotation); refetch and retry"))
                return@post
            }
            RekeyOutcome.OK -> Unit
        }
        services.activity.record(principal.accountId, "team.rekey", "epoch ${req.newEpoch}", teamId = teamId)
        // Every re-keyed member re-reads the team to adopt the new key.
        envelopes.keys.forEach { services.notifier.publishMembership(it) }
        call.respond(HttpStatusCode.OK)
    }

    delete("/teams/{id}/members/{accountId}") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@delete
        val target = call.requiredPathId("accountId") ?: return@delete
        // A member can remove themselves (leave/decline invite), or a manager with rights over the
        // target's role can remove them (owner: anyone; admin: only editor/viewer).
        if (target != principal.accountId) {
            val actor = call.requireActiveMember(
                services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
            ) ?: return@delete
            val targetMember = services.teams.membership(teamId, target)
            if (targetMember != null && !TeamRoles.canModifyMember(actor.role, targetMember.role)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot remove this member"))
                return@delete
            }
        }
        if (!services.teams.removeMember(teamId, target)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such member (owner cannot be removed)"))
            return@delete
        }
        // Leaving the team takes every scope grant with it — otherwise a former member would keep
        // pulling a scope's records through an ACL row nobody thinks to clean up. The client rotates
        // the affected scope keys (the removed member still holds their copies).
        val lostScopes = services.teamScopes.revokeAll(teamId, target)
        val scopeNote = if (lostScopes.isEmpty()) "" else " · scopes ${lostScopes.joinToString(",")}"
        services.activity.record(principal.accountId, "team.remove", "$target$scopeNote", teamId = teamId)
        services.notifier.publishMembership(target)
        call.respond(HttpStatusCode.OK)
    }

    post("/teams/{id}/accept") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        if (!services.teams.accept(teamId, principal.accountId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no pending invite"))
            return@post
        }
        services.activity.record(principal.accountId, "team.accept", "accepted invite", teamId = teamId)
        call.respond(HttpStatusCode.OK)
    }

    get("/teams/{id}/records") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        val scopeId = call.scopeParam() ?: return@get
        call.requireActiveMember(services, teamId, principal.accountId) ?: return@get
        if (!call.requireScopeAccess(services, teamId, scopeId, principal.accountId)) return@get
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val delta = services.teamRecords.delta(teamId, scopeId, since)
        services.metrics.recordsPulled(SyncScope.TEAM, delta.records.size)
        // Team scope has no compactedIds: tombstones are cleaned up by age, redelivery is idempotent.
        call.respond(RecordsResponse(delta.records.map { it.toDto() }, delta.cursor, emptyList()))
    }

    put("/teams/{id}/records") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@put
        val scopeId = call.scopeParam() ?: return@put
        // Write is gated by role: viewer is active but canWrite=false -> 403.
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canWrite, "write role required",
        ) ?: return@put
        if (!call.requireScopeAccess(services, teamId, scopeId, principal.accountId)) return@put
        val req = call.receive<PushRequest>()
        val unknown = req.records.firstOrNull { it.type !in TEAM_ALLOWED_TYPES }
        if (unknown != null) throw BadRequestException("unknown record type: ${unknown.type}")

        val incoming = req.records.map { it.toIncoming() }
        services.metrics.recordsReceived(SyncScope.TEAM, incoming.size, incoming.sumOf { it.blob.size.toLong() })
        val result = services.teamRecords.upsert(teamId, scopeId, incoming)
        // The records are committed; the audit trail is written after them and must not undo that.
        // A failure here is logged and swallowed on purpose: answering 500 would have the client
        // retry a push that LWW now treats as a no-op, so `applied` would come back empty and the
        // entries would never be written anyway — the same audit gap, plus a failed sync on top.
        try {
            logRecordChanges(services, principal.accountId, teamId, scopeId, result.applied)
        } catch (e: Exception) {
            call.application.environment.log.error("team audit write failed for ${logSafe(teamId)} (records are committed)", e)
        }
        if (result.changed) services.notifier.publishTeam(teamId, result.cursor)
        call.respond(PushResponse(result.records.map { it.toDto() }, result.cursor))
    }

    /**
     * The team's audit log (owner/admin). Rows are **not** filtered by the reader's scope grants: a
     * manager sees that a record of some type changed in some scope, exactly as they already see the
     * scope itself and its member count. What a grant gates is the records' contents — a manager
     * without one still cannot read a name (the feed resolves those locally, and their vault has no
     * key for that space) let alone a payload. Filtering the log by grant instead would leave the
     * team's owner unable to audit their own team.
     */
    get("/teams/{id}/activity") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canViewAudit, "audit role required",
        ) ?: return@get
        val limit = call.limitParam(default = 100, max = 500)
        val entries = services.activity.recentForTeam(teamId, limit, call.offsetParam()).map {
            TeamActivityDto(
                actorAccountId = it.accountId,
                event = it.event,
                detail = it.detail,
                createdAt = it.createdAt,
                recordId = it.recordId,
                recordType = it.recordType,
                scopeId = it.scopeId,
                durationSec = it.durationSec,
            )
        }
        call.respond(TeamActivityResponse(entries, services.activity.countForTeam(teamId)))
    }

    /**
     * A member reporting that they opened a session on a shared record, or saved a recording of one.
     * Any active member may report (connecting is not a privileged act); reading the feed back is
     * still owner/admin only.
     *
     * The record must be one the team actually holds **and** one the caller can see: the space comes
     * from the stored row, so a report can't file itself under a scope of the reporter's choosing,
     * and a member without the grant gets the same 404 as anywhere else rather than a confirmation
     * that the record exists.
     */
    // Rate-limited per account (see RateLimits.TEAM_SESSION_EVENTS): unlike every other write here
    // this one needs no role at all, and it appends to a log with a bounded retention window.
    rateLimit(RateLimits.TEAM_SESSION_EVENTS) {
      post("/teams/{id}/session-events") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        val req = call.receive<TeamSessionEventRequest>()
        // Membership first, as everywhere else in this file: a caller who is not a member gets the
        // same "no such team" 404 whatever they sent, rather than a 400 confirming the route exists.
        call.requireActiveMember(services, teamId, principal.accountId) ?: return@post
        if (req.recordId.isBlank() || anyTooLong(req.recordId)) throw BadRequestException("bad recordId")
        val event = SESSION_EVENTS[req.kind] ?: throw BadRequestException("unknown kind: ${req.kind}")
        val duration = req.durationSec?.coerceIn(0, MAX_SESSION_DURATION_SEC)
        val location = services.teamRecords.locate(teamId, req.recordId)
        if (location == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such record"))
            return@post
        }
        if (!call.requireScopeAccess(services, teamId, location.scopeId, principal.accountId)) return@post
        services.activity.recordTeamSession(
            accountId = principal.accountId,
            teamId = teamId,
            event = event,
            recordId = req.recordId,
            recordType = location.type,
            scopeId = location.scopeId,
            durationSec = duration,
        )
        // Created even when the report was collapsed as a duplicate: from the client's side the
        // report landed, and a retry loop over "already known" would be pointless traffic.
        call.respond(HttpStatusCode.Created)
      }
    }
}

/** Reportable session kinds (wire value -> audit event). */
private val SESSION_EVENTS = mapOf("open" to "team.session_open", "record" to "team.session_record")

/** A reported recording longer than this is clamped: a bogus number must not read as fact. */
private const val MAX_SESSION_DURATION_SEC = 30L * 24 * 3600

/**
 * How many changed records a push may report one by one. Past it the push gets a single summary
 * event: a key rotation re-encrypts and re-pushes the whole space at once, and spelling out every
 * record would flood the feed with "changed" rows that say nothing about anyone's intent (and would
 * push the rest of the team's history out of retention).
 */
internal const val TEAM_RECORD_EVENT_LIMIT = 10

/**
 * Audit trail of a push: one event per record that actually changed, so the feed can say who touched
 * which host. Only [applied] records are logged — a client re-pushes all of its records on every
 * sync cycle, and the ones that lost LWW changed nothing.
 *
 * [detail] keeps the human-readable summary for readers with no structured fields (the admin
 * console, an older client); the record's name is not among them and never leaves the members'
 * devices.
 */
private suspend fun logRecordChanges(
    services: Services,
    accountId: String,
    teamId: String,
    scopeId: String,
    applied: List<AppliedTeamRecord>,
) {
    if (applied.isEmpty()) return
    val where = if (scopeId.isEmpty()) "" else " · scope $scopeId"
    if (applied.size > TEAM_RECORD_EVENT_LIMIT) {
        services.activity.record(
            accountId, "team.push", "${applied.size} records$where", teamId = teamId, scopeId = scopeId,
        )
        return
    }
    // One transaction for the whole batch: these rows describe a single push, and half of them is
    // worse than none — the client's retry is a no-op by LWW, so a partial write could never be
    // completed later (see ActivityRepository.recordAll).
    services.activity.recordAll(
        applied.map { entry ->
            val event = when (entry.change) {
                TeamRecordChange.SHARED -> "team.record_share"
                TeamRecordChange.CHANGED -> "team.record_change"
                TeamRecordChange.REMOVED -> "team.record_remove"
            }
            ActivityEvent(
                accountId = accountId,
                event = event,
                detail = "${entry.record.type} ${entry.record.id}$where",
                teamId = teamId,
                recordId = entry.record.id,
                recordType = entry.record.type,
                scopeId = scopeId,
            )
        },
    )
}

/**
 * Returns [accountId]'s membership in the team if they're an active member and (when [capability]
 * is given) their role passes the check; otherwise responds 404 (not a member, don't reveal the
 * team) / 403 and returns null. The team owner also goes through capability checks (see [TeamRoles]).
 */
internal suspend fun ApplicationCall.requireActiveMember(
    services: Services,
    teamId: String,
    accountId: String,
    capability: ((String) -> Boolean)? = null,
    forbidMessage: String = "insufficient role",
): TeamMemberRow? {
    val membership = services.teams.membership(teamId, accountId)
    if (membership == null) {
        services.metrics.teamAuthzDenied(TeamDenial.NOT_MEMBER)
        respond(HttpStatusCode.NotFound, ErrorResponse("no such team"))
        return null
    }
    if (membership.status != TeamMemberStatus.ACTIVE) {
        services.metrics.teamAuthzDenied(TeamDenial.NOT_ACCEPTED)
        respond(HttpStatusCode.Forbidden, ErrorResponse("invite not accepted"))
        return null
    }
    if (capability != null && !capability(membership.role)) {
        services.metrics.teamAuthzDenied(TeamDenial.ROLE)
        respond(HttpStatusCode.Forbidden, ErrorResponse(forbidMessage))
        return null
    }
    return membership
}

/**
 * `?scope=` on the record endpoints: empty/absent means the team-wide space. Validated here so a
 * malformed id is a 400 before any lookup.
 */
private suspend fun ApplicationCall.scopeParam(): String? {
    val raw = request.queryParameters["scope"] ?: return ""
    if (raw.isEmpty()) return ""
    return validScopeId(raw)
}

/**
 * Granular read/write ACL: a scoped space is served only to accounts holding a grant. Responds 404
 * (not 403) on a missing grant — same reasoning as for a team the caller isn't a member of: the
 * existence of a scope is itself information about how the team is organised. Team-wide records
 * (empty [scopeId]) are open to every active member, as before.
 */
private suspend fun ApplicationCall.requireScopeAccess(
    services: Services,
    teamId: String,
    scopeId: String,
    accountId: String,
): Boolean {
    if (scopeId.isEmpty()) return true
    if (services.teamScopes.hasGrant(teamId, scopeId, accountId)) return true
    services.metrics.teamAuthzDenied(TeamDenial.SCOPE)
    respond(HttpStatusCode.NotFound, ErrorResponse("no such scope"))
    return false
}
