package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
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
import app.skerry.sync.wire.TeamMembersResponse
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

/** Синкуемые в team-scope типы: секреты и структура, без SETTINGS/KNOWN_HOST (они per-account). */
private val TEAM_ALLOWED_TYPES = setOf("HOST", "GROUP", "IDENTITY", "CREDENTIAL", "SNIPPET", "TUNNEL")

/** Публичный X25519-ключ — ровно 32 байта; конверт crypto_box_seal — 48 байт оверхеда + payload. */
private const val PUBLIC_KEY_BYTES = 32
private const val MAX_ENVELOPE_BYTES = 4096

/**
 * Teams: ключи аккаунтов, состав команд и team-scoped записи. Zero-knowledge: сервер хранит
 * только метаданные (состав, роли) и шифроблобы (конверты приглашений, записи под teamKey).
 * ACL: `owner` управляет составом и жизнью команды; записи читают и пишут все активные участники.
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
        services.activity.record(principal.accountId, "team.create", req.teamId)
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
        if (call.rejectUnlessOwner(services, teamId, principal.accountId)) return@delete
        services.teams.deleteTeam(teamId)
        services.activity.record(principal.accountId, "team.delete", teamId)
        // Всем бывшим участникам: состав изменился — пусть перечитают список команд.
        members.forEach { services.notifier.publishMembership(it.accountId) }
        call.respond(HttpStatusCode.OK)
    }

    get("/teams/{id}/members") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        if (call.rejectUnlessActiveMember(services, teamId, principal.accountId)) return@get
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
        if (call.rejectUnlessOwner(services, teamId, principal.accountId)) return@post
        if (services.accounts.find(req.accountId) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
            return@post
        }
        if (!services.teams.invite(teamId, req.accountId, envelope, principal.accountId, System.currentTimeMillis())) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("already a member or invited"))
            return@post
        }
        services.activity.record(principal.accountId, "team.invite", "$teamId · ${req.accountId}")
        services.notifier.publishMembership(req.accountId)
        call.respond(HttpStatusCode.Created)
    }

    delete("/teams/{id}/members/{accountId}") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@delete
        val target = call.requiredPathId("accountId") ?: return@delete
        // Удалить может владелец (любого) или сам участник себя (выход/отклонение приглашения).
        if (target != principal.accountId &&
            call.rejectUnlessOwner(services, teamId, principal.accountId)
        ) {
            return@delete
        }
        if (!services.teams.removeMember(teamId, target)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such member (owner cannot be removed)"))
            return@delete
        }
        services.activity.record(principal.accountId, "team.remove", "$teamId · $target")
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
        services.activity.record(principal.accountId, "team.accept", teamId)
        call.respond(HttpStatusCode.OK)
    }

    get("/teams/{id}/records") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        if (call.rejectUnlessActiveMember(services, teamId, principal.accountId)) return@get
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val delta = services.teamRecords.delta(teamId, since)
        val cursor = delta.lastOrNull()?.serverSeq ?: since
        // Team-scope без compactedIds: тромбстоуны чистятся по возрасту, повторная доставка идемпотентна.
        call.respond(RecordsResponse(delta.map { it.toDto() }, cursor, emptyList()))
    }

    put("/teams/{id}/records") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@put
        if (call.rejectUnlessActiveMember(services, teamId, principal.accountId)) return@put
        val req = call.receive<PushRequest>()
        val unknown = req.records.firstOrNull { it.type !in TEAM_ALLOWED_TYPES }
        if (unknown != null) throw BadRequestException("unknown record type: ${unknown.type}")

        val result = services.teamRecords.upsert(teamId, req.records.map { it.toIncoming() })
        services.activity.record(
            principal.accountId, "team.push", "$teamId · ${req.records.size} records · cursor ${result.cursor}",
        )
        if (result.changed) services.notifier.publishTeam(teamId, result.cursor)
        call.respond(PushResponse(result.records.map { it.toDto() }, result.cursor))
    }
}

/** 403/404, если [accountId] — не владелец команды. true = ответ уже отправлен. */
private suspend fun ApplicationCall.rejectUnlessOwner(services: Services, teamId: String, accountId: String): Boolean {
    val membership = services.teams.membership(teamId, accountId)
    if (membership == null) {
        // Не раскрываем существование чужих команд: не участник видит 404, не 403.
        respond(HttpStatusCode.NotFound, ErrorResponse("no such team"))
        return true
    }
    if (membership.role != TeamRoles.OWNER) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("owner role required"))
        return true
    }
    return false
}

/** 404/403, если [accountId] — не активный участник команды. true = ответ уже отправлен. */
private suspend fun ApplicationCall.rejectUnlessActiveMember(services: Services, teamId: String, accountId: String): Boolean {
    val membership = services.teams.membership(teamId, accountId)
    if (membership == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("no such team"))
        return true
    }
    if (membership.status != TeamMemberStatus.ACTIVE) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("invite not accepted"))
        return true
    }
    return false
}
