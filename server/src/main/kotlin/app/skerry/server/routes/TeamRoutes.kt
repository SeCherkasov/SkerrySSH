package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.db.TeamMemberRow
import app.skerry.server.db.TeamMemberStatus
import app.skerry.server.db.TeamRoles
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
import app.skerry.sync.wire.TeamRoleChangeRequest
import app.skerry.sync.wire.TeamsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Types synced in team scope: secrets and structure, excluding SETTINGS/KNOWN_HOST (per-account). */
private val TEAM_ALLOWED_TYPES = setOf("HOST", "GROUP", "IDENTITY", "CREDENTIAL", "SNIPPET", "TUNNEL")

/** Public X25519 key is exactly 32 bytes; a crypto_box_seal envelope is 48 bytes overhead + payload. */
private const val PUBLIC_KEY_BYTES = 32
private const val MAX_ENVELOPE_BYTES = 4096

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
        if (key.size != PUBLIC_KEY_BYTES) throw BadRequestException("publicKey must be $PUBLIC_KEY_BYTES bytes")
        services.teams.publishKey(principal.accountId, key, System.currentTimeMillis())
        call.respond(HttpStatusCode.OK)
    }

    get("/account/keys/{accountId}") {
        call.jwtPrincipal()
        val target = call.requiredPathId("accountId") ?: return@get
        if (target.length > MAX_ACCOUNT_ID) throw BadRequestException("accountId too long")
        val key = services.teams.publicKey(target)
        if (key == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no published key for account"))
            return@get
        }
        call.respond(AccountKeyResponse(target, key.b64()))
    }

    post("/teams") {
        val principal = call.jwtPrincipal()
        val req = call.receive<TeamCreateRequest>()
        if (req.teamId.isBlank() || anyTooLong(req.teamId)) throw BadRequestException("bad teamId")
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
        val members = services.teams.members(teamId).map {
            TeamMemberDto(it.accountId, it.role, it.status, it.createdAt)
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
        services.activity.record(principal.accountId, "team.remove", target, teamId = teamId)
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
        call.requireActiveMember(services, teamId, principal.accountId) ?: return@get
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val delta = services.teamRecords.delta(teamId, since)
        val cursor = delta.lastOrNull()?.serverSeq ?: since
        // Team scope has no compactedIds: tombstones are cleaned up by age, redelivery is idempotent.
        call.respond(RecordsResponse(delta.map { it.toDto() }, cursor, emptyList()))
    }

    put("/teams/{id}/records") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@put
        // Write is gated by role: viewer is active but canWrite=false -> 403.
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canWrite, "write role required",
        ) ?: return@put
        val req = call.receive<PushRequest>()
        val unknown = req.records.firstOrNull { it.type !in TEAM_ALLOWED_TYPES }
        if (unknown != null) throw BadRequestException("unknown record type: ${unknown.type}")

        val result = services.teamRecords.upsert(teamId, req.records.map { it.toIncoming() })
        val ids = req.records.joinToString(" ") { it.id }.take(200)
        services.activity.record(
            principal.accountId, "team.push", "${req.records.size} records · $ids", teamId = teamId,
        )
        if (result.changed) services.notifier.publishTeam(teamId, result.cursor)
        call.respond(PushResponse(result.records.map { it.toDto() }, result.cursor))
    }

    get("/teams/{id}/activity") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canViewAudit, "audit role required",
        ) ?: return@get
        val entries = services.activity.recentForTeam(teamId).map {
            TeamActivityDto(it.accountId, it.event, it.detail, it.createdAt)
        }
        call.respond(TeamActivityResponse(entries))
    }
}

/**
 * Returns [accountId]'s membership in the team if they're an active member and (when [capability]
 * is given) their role passes the check; otherwise responds 404 (not a member, don't reveal the
 * team) / 403 and returns null. The team owner also goes through capability checks (see [TeamRoles]).
 */
private suspend fun ApplicationCall.requireActiveMember(
    services: Services,
    teamId: String,
    accountId: String,
    capability: ((String) -> Boolean)? = null,
    forbidMessage: String = "insufficient role",
): TeamMemberRow? {
    val membership = services.teams.membership(teamId, accountId)
    if (membership == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("no such team"))
        return null
    }
    if (membership.status != TeamMemberStatus.ACTIVE) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("invite not accepted"))
        return null
    }
    if (capability != null && !capability(membership.role)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(forbidMessage))
        return null
    }
    return membership
}
