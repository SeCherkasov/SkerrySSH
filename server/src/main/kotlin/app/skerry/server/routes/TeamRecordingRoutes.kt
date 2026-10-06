package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.db.RecordingChunkResult
import app.skerry.server.db.RecordingChunkSpec
import app.skerry.server.db.RecordingPolicyResult
import app.skerry.server.db.RecordingReservation
import app.skerry.server.db.RecordingReserveResult
import app.skerry.server.db.StoredRecording
import app.skerry.server.db.StoredRecordingPolicy
import app.skerry.server.db.TeamRecordingRepository
import app.skerry.server.db.TeamRoles
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.b64
import app.skerry.server.model.unb64
import app.skerry.sync.wire.RecordingBulkDeleteRequest
import app.skerry.sync.wire.RecordingBulkDeleteResponse
import app.skerry.sync.wire.RecordingListResponse
import app.skerry.sync.wire.RecordingMetadataDto
import app.skerry.sync.wire.RecordingPolicyDto
import app.skerry.sync.wire.RecordingReserveRequest
import app.skerry.sync.wire.RecordingWrapRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Dedicated immutable recording transport. No cast plaintext is accepted by these routes. */
fun Route.teamRecordingRoutes(services: Services) {
    recordingPolicyRead(services)
    recordingPolicyWrite(services)
    recordingReserve(services)
    recordingChunkUpload(services)
    recordingComplete(services)
    recordingList(services)
    recordingMetadata(services)
    recordingChunkDownload(services)
    recordingDelete(services)
    recordingBulkDelete(services)
    recordingWrap(services)
}

private fun Route.recordingPolicyRead(services: Services) {
    get("/teams/{id}/recording-policy") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@get
        val scopeId = call.scopeParam() ?: return@get
        call.requireActiveMember(services, teamId, actor) ?: return@get
        if (!call.requireScopeAccess(services, teamId, scopeId, actor)) return@get
        val policy = services.teamRecordings.policy(teamId, scopeId)
        if (policy == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("no recording policy"))
        else call.respond(policy.toDto())
    }
}

private fun Route.recordingPolicyWrite(services: Services) {
    put("/teams/{id}/recording-policy") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@put
        val scopeId = call.scopeParam() ?: return@put
        call.requireActiveMember(services, teamId, actor, { it == TeamRoles.OWNER }, "owner role required") ?: return@put
        if (!call.requireScopeAccess(services, teamId, scopeId, actor)) return@put
        val req = call.receive<RecordingPolicyDto>()
        val policy = StoredRecordingPolicy(req.revision, req.keyEpoch, req.retentionDays,
            req.ciphertext.unb64(), req.signature.unb64())
        when (services.teamRecordings.putPolicy(teamId, scopeId, actor, policy, System.currentTimeMillis())) {
            RecordingPolicyResult.UPDATED -> call.respond(HttpStatusCode.OK)
            RecordingPolicyResult.CONFLICT -> call.respond(HttpStatusCode.Conflict, ErrorResponse("stale or invalid policy"))
            RecordingPolicyResult.DENIED -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no such space"))
        }
    }
}

private fun Route.recordingReserve(services: Services) {
    post("/teams/{id}/recordings") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@post
        val scopeId = call.scopeParam() ?: return@post
        call.requireActiveMember(services, teamId, actor) ?: return@post
        if (!call.requireScopeAccess(services, teamId, scopeId, actor)) return@post
        val req = call.receive<RecordingReserveRequest>()
        if (!safeId(req.recordingId) || !safeId(req.hostId)) throw BadRequestException("bad recording or host id")
        val policy = services.teamRecordings.policy(teamId, scopeId)
        if (policy == null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("recording policy required"))
            return@post
        }
        val spec = RecordingReservation(teamId, scopeId, req.recordingId, req.hostId, actor,
            req.keyEpoch, req.wrappedKey.unb64(), req.manifest.unb64(),
            req.chunks.map { RecordingChunkSpec(it.index, it.length, it.sha256) }, req.durationSec,
            policy.retentionDays, req.wrapEpoch ?: req.keyEpoch)
        call.respondRecordingReservation(services.teamRecordings.reserve(spec, System.currentTimeMillis()))
    }
}

private fun Route.recordingChunkUpload(services: Services) {
    put("/teams/{id}/recordings/{rid}/chunks/{index}") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@put
        val rid = call.requiredPathId("rid") ?: return@put
        val index = call.parameters["index"]?.toIntOrNull() ?: throw BadRequestException("bad chunk index")
        call.requireActiveMember(services, teamId, actor) ?: return@put
        val length = call.recordingChunkLength() ?: return@put
        val bytes = readRecordingChunk(call.receiveChannel(), length)
        when (services.teamRecordings.putChunk(teamId, rid, actor, index, bytes)) {
            RecordingChunkResult.CREATED -> call.respond(HttpStatusCode.Created)
            RecordingChunkResult.SAME -> call.respond(HttpStatusCode.OK)
            RecordingChunkResult.CONFLICT -> call.respond(HttpStatusCode.Conflict, ErrorResponse("chunk conflict"))
            RecordingChunkResult.NOT_FOUND, RecordingChunkResult.DENIED ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no such recording"))
        }
    }
}

private fun Route.recordingComplete(services: Services) {
    post("/teams/{id}/recordings/{rid}/complete") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@post
        val rid = call.requiredPathId("rid") ?: return@post
        call.requireActiveMember(services, teamId, actor) ?: return@post
        if (services.teamRecordings.complete(teamId, rid, actor, System.currentTimeMillis())) call.respond(HttpStatusCode.OK)
        else call.respond(HttpStatusCode.Conflict, ErrorResponse("recording incomplete or inaccessible"))
    }
}

private fun Route.recordingList(services: Services) {
    get("/teams/{id}/recordings") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@get
        val scopeId = call.scopeParam() ?: return@get
        call.requireActiveMember(services, teamId, actor) ?: return@get
        if (!call.requireScopeAccess(services, teamId, scopeId, actor)) return@get
        val limit = call.limitParam(default = 50, max = 100)
        call.respond(RecordingListResponse(services.teamRecordings.listReady(teamId, scopeId, actor,
            limit, call.offsetParam()).map { it.toDto() }))
    }
}

private fun Route.recordingMetadata(services: Services) {
    get("/teams/{id}/recordings/{rid}") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@get
        val rid = call.requiredPathId("rid") ?: return@get
        call.requireActiveMember(services, teamId, actor) ?: return@get
        val row = services.teamRecordings.ready(teamId, rid, actor)
        if (row == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("no such recording"))
        else call.respond(row.toDto())
    }
}

private fun Route.recordingChunkDownload(services: Services) {
    get("/teams/{id}/recordings/{rid}/chunks/{index}") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@get
        val rid = call.requiredPathId("rid") ?: return@get
        val index = call.parameters["index"]?.toIntOrNull() ?: throw BadRequestException("bad chunk index")
        call.requireActiveMember(services, teamId, actor) ?: return@get
        val bytes = services.teamRecordings.chunk(teamId, rid, index, actor)
        if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("no such chunk"))
        else call.respondBytes(bytes, ContentType.Application.OctetStream)
    }
}

private fun Route.recordingDelete(services: Services) {
    delete("/teams/{id}/recordings/{rid}") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@delete
        val rid = call.requiredPathId("rid") ?: return@delete
        call.requireActiveMember(services, teamId, actor, { it == TeamRoles.OWNER }, "owner role required") ?: return@delete
        if (services.teamRecordings.delete(teamId, rid, actor)) call.respond(HttpStatusCode.OK)
        else call.respond(HttpStatusCode.NotFound, ErrorResponse("no such recording"))
    }
}

private fun Route.recordingBulkDelete(services: Services) {
    post("/teams/{id}/recordings/delete") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@post
        call.requireActiveMember(services, teamId, actor, { it == TeamRoles.OWNER }, "owner role required") ?: return@post
        val req = call.receive<RecordingBulkDeleteRequest>()
        if (req.recordingIds.size !in 1..100 || req.recordingIds.any { !safeId(it) }) {
            throw BadRequestException("bad recording ids")
        }
        val deleted = services.teamRecordings.deleteMany(teamId, req.recordingIds, actor)
        if (deleted == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("no such team"))
        else call.respond(RecordingBulkDeleteResponse(deleted))
    }
}

private fun Route.recordingWrap(services: Services) {
    put("/teams/{id}/recordings/{rid}/wrap") {
        val actor = call.jwtPrincipal().accountId
        val teamId = call.requiredPathId("id") ?: return@put
        val rid = call.requiredPathId("rid") ?: return@put
        call.requireActiveMember(services, teamId, actor,
            { it == TeamRoles.OWNER || it == TeamRoles.ADMIN }, "manager role required") ?: return@put
        val body = call.receive<RecordingWrapRequest>()
        val bytes = body.wrappedKey.unb64()
        if (services.teamRecordings.stageWrap(teamId, rid, actor, body.nextEpoch, bytes)) call.respond(HttpStatusCode.OK)
        else call.respond(HttpStatusCode.Conflict, ErrorResponse("wrap epoch or recording inaccessible"))
    }
}

private suspend fun ApplicationCall.respondRecordingReservation(result: RecordingReserveResult) {
    when (result) {
        RecordingReserveResult.CREATED -> respond(HttpStatusCode.Created)
        RecordingReserveResult.SAME -> respond(HttpStatusCode.OK)
        RecordingReserveResult.RETIRED -> respond(HttpStatusCode.Gone, ErrorResponse("recording retired"))
        RecordingReserveResult.CONFLICT, RecordingReserveResult.NO_POLICY ->
            respond(HttpStatusCode.Conflict, ErrorResponse("recording conflict or stale policy"))
        RecordingReserveResult.NO_HOST, RecordingReserveResult.DENIED ->
            respond(HttpStatusCode.NotFound, ErrorResponse("no such host"))
        RecordingReserveResult.TOO_LARGE -> respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("recording too large"))
    }
}

private suspend fun ApplicationCall.recordingChunkLength(): Int? {
    val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (length == null || length !in 40..TeamRecordingRepository.MAX_CHUNK_BYTES.toLong()) {
        respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("bad chunk length"))
        return null
    }
    if (request.contentType().withoutParameters() != ContentType.Application.OctetStream) {
        throw BadRequestException("octet-stream required")
    }
    return length.toInt()
}

private fun safeId(id: String): Boolean = id.length in 1..64 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

private fun StoredRecordingPolicy.toDto() = RecordingPolicyDto(revision, keyEpoch, retentionDays, ciphertext.b64(), signature.b64())

private fun StoredRecording.toDto() = RecordingMetadataDto(recordingId, teamId, scopeId, hostId, actorId, keyEpoch,
    wrappedKey.b64(), manifest.b64(), chunkCount, durationSec, createdAt, expiresAt,
    wrapEpoch, stagedWrappedKey?.b64(), stagedWrapEpoch)
