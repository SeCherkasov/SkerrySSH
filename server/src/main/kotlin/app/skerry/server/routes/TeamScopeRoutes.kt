package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.db.RekeyOutcome
import app.skerry.server.db.TeamMemberStatus
import app.skerry.server.db.TeamRoles
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.b64
import app.skerry.server.model.unb64
import app.skerry.sync.wire.TeamRekeyRequest
import app.skerry.sync.wire.TeamScopeCreateRequest
import app.skerry.sync.wire.TeamScopeDto
import app.skerry.sync.wire.TeamScopeGrantDto
import app.skerry.sync.wire.TeamScopeGrantRequest
import app.skerry.sync.wire.TeamScopeGrantsResponse
import app.skerry.sync.wire.TeamScopesResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Scopes: granular sharing inside a team. A scope has its own key, so a member without a grant can
 * neither fetch its records (this ACL) nor decrypt them (the key they never received) — the two
 * guarantees are independent, and only the second survives a compromised server.
 *
 * Handing a scope out (grant, rekey) requires **both** the manage-members role and a grant of one's
 * own. The role alone is not enough, and that is not a formality: an admin deliberately left out of
 * a scope could otherwise self-grant an ACL row and rekey the scope with a key of their own making.
 * Their signature over that envelope is genuine, so every real grantee would adopt it and everything
 * shared afterwards would be readable by them — the exact separation the feature exists to provide.
 * Deleting a scope needs the role only: when everyone holding the key is gone, a manager still has
 * to be able to clear the leftovers away.
 */
fun Route.teamScopeRoutes(services: Services) {

    get("/teams/{id}/scopes") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        val membership = call.requireActiveMember(services, teamId, principal.accountId) ?: return@get
        val scopes = services.teamScopes
            .scopesFor(teamId, principal.accountId, all = TeamRoles.canManageMembers(membership.role))
            .map { TeamScopeDto(it.scopeId, it.keyEpoch, it.memberCount, it.envelope?.b64()) }
        call.respond(TeamScopesResponse(scopes))
    }

    post("/teams/{id}/scopes") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        val req = call.receive<TeamScopeCreateRequest>()
        val scopeId = call.validScopeId(req.scopeId) ?: return@post
        val envelope = call.validEnvelope(req.envelope) ?: return@post
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@post
        if (!services.teamScopes.create(teamId, scopeId, principal.accountId, envelope, System.currentTimeMillis())) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("scope already exists"))
            return@post
        }
        services.activity.record(principal.accountId, "team.scope_create", scopeId, teamId = teamId)
        call.respond(HttpStatusCode.Created)
    }

    delete("/teams/{id}/scopes/{scopeId}") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@delete
        val scopeId = call.requiredPathId("scopeId")?.let { call.validScopeId(it) } ?: return@delete
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@delete
        val grantees = services.teamScopes.grants(teamId, scopeId).map { it.accountId }
        if (!services.teamScopes.delete(teamId, scopeId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such scope"))
            return@delete
        }
        services.activity.record(principal.accountId, "team.scope_delete", scopeId, teamId = teamId)
        grantees.forEach { services.notifier.publishMembership(it) }
        call.respond(HttpStatusCode.OK)
    }

    get("/teams/{id}/scopes/{scopeId}/grants") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        val scopeId = call.requiredPathId("scopeId")?.let { call.validScopeId(it) } ?: return@get
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@get
        val grants = services.teamScopes.grants(teamId, scopeId).map { TeamScopeGrantDto(it.accountId, it.createdAt) }
        call.respond(TeamScopeGrantsResponse(grants))
    }

    post("/teams/{id}/scopes/{scopeId}/grants") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        val scopeId = call.requiredPathId("scopeId")?.let { call.validScopeId(it) } ?: return@post
        val req = call.receive<TeamScopeGrantRequest>()
        if (req.accountId.isBlank() || req.accountId.length > MAX_ACCOUNT_ID) throw BadRequestException("bad accountId")
        val envelope = call.validEnvelope(req.envelope) ?: return@post
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@post
        if (!call.requireScopeHolder(services, teamId, scopeId, principal.accountId)) return@post
        // Only an accepted member of the team can hold a scope grant: an invitee has no teamKey yet,
        // and a stranger has no business in the team's ACL at all.
        val target = services.teams.membership(teamId, req.accountId)
        if (target == null || target.status != TeamMemberStatus.ACTIVE) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such active member"))
            return@post
        }
        if (!services.teamScopes.grant(teamId, scopeId, req.accountId, envelope, System.currentTimeMillis())) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such scope"))
            return@post
        }
        services.activity.record(principal.accountId, "team.scope_grant", "${req.accountId} · $scopeId", teamId = teamId)
        services.notifier.publishMembership(req.accountId)
        call.respond(HttpStatusCode.Created)
    }

    delete("/teams/{id}/scopes/{scopeId}/grants/{accountId}") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@delete
        val scopeId = call.requiredPathId("scopeId")?.let { call.validScopeId(it) } ?: return@delete
        val target = call.requiredPathId("accountId") ?: return@delete
        // Self-revoke (giving up access voluntarily) needs no management role; revoking someone else does.
        if (target != principal.accountId) {
            call.requireActiveMember(
                services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
            ) ?: return@delete
        } else {
            call.requireActiveMember(services, teamId, principal.accountId) ?: return@delete
        }
        if (!services.teamScopes.revoke(teamId, scopeId, target)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such grant"))
            return@delete
        }
        services.activity.record(principal.accountId, "team.scope_revoke", "$target · $scopeId", teamId = teamId)
        services.notifier.publishMembership(target)
        call.respond(HttpStatusCode.OK)
    }

    post("/teams/{id}/scopes/{scopeId}/rekey") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@post
        val scopeId = call.requiredPathId("scopeId")?.let { call.validScopeId(it) } ?: return@post
        val req = call.receive<TeamRekeyRequest>()
        call.requireActiveMember(
            services, teamId, principal.accountId, TeamRoles::canManageMembers, "manage-members role required",
        ) ?: return@post
        if (!call.requireScopeHolder(services, teamId, scopeId, principal.accountId)) return@post
        val envelopes = mutableMapOf<String, ByteArray>()
        for (e in req.envelopes) {
            envelopes[e.accountId] = call.validEnvelope(e.envelope) ?: return@post
        }
        // Monotonic epoch enforced as a compare-and-set inside the repository (see TeamScopeRepository.rekey).
        when (services.teamScopes.rekey(teamId, scopeId, req.newEpoch, envelopes)) {
            RekeyOutcome.NO_TEAM -> {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no such scope"))
                return@post
            }
            RekeyOutcome.EPOCH_CONFLICT -> {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("stale epoch (concurrent rotation); refetch and retry"))
                return@post
            }
            RekeyOutcome.OK -> Unit
        }
        services.activity.record(principal.accountId, "team.scope_rekey", "$scopeId · epoch ${req.newEpoch}", teamId = teamId)
        envelopes.keys.forEach { services.notifier.publishMembership(it) }
        call.respond(HttpStatusCode.OK)
    }
}

/**
 * Whoever hands a scope key out must already hold it. Responds 403 (the caller is a manager of this
 * team and already knows the scope exists — there is nothing left to hide), and refuses before any
 * ACL row or epoch is touched. See the class doc for the escalation this closes.
 */
private suspend fun ApplicationCall.requireScopeHolder(
    services: Services,
    teamId: String,
    scopeId: String,
    accountId: String,
): Boolean {
    if (services.teamScopes.hasGrant(teamId, scopeId, accountId)) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse("scope access required"))
    return false
}

/**
 * A scope id is client-generated and ends up in a vault file name on every member's device, so it is
 * held to the same charset as a team id (`[a-z0-9-]`). Rejected before any side effect.
 */
internal suspend fun ApplicationCall.validScopeId(value: String): String? {
    val ok = value.isNotEmpty() && value.length <= MAX_SCOPE_ID &&
        value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    if (!ok) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("bad scopeId"))
        return null
    }
    return value
}

private suspend fun ApplicationCall.validEnvelope(value: String): ByteArray? {
    val bytes = value.unb64()
    if (bytes.isEmpty() || bytes.size > MAX_ENVELOPE_BYTES) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("bad envelope"))
        return null
    }
    return bytes
}

internal const val MAX_SCOPE_ID = 64
