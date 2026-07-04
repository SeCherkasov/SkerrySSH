package app.skerry.sync.wire

import kotlinx.serialization.Serializable

/**
 * Wire-контракт Teams (шеринг записей между аккаунтами, zero-knowledge).
 *
 * Сервер видит только метаданные (id команд, состав, роли) и шифроблобы:
 * - `publicKey` — публичная половина X25519-пары аккаунта (для запечатывания приглашений);
 * - `envelope` — sealed-конверт crypto_box_seal с teamKey+именем команды, открыть может
 *   только приглашённый; сервер и админ содержимое не читают;
 * - `blob` в командных записях — XChaCha20-Poly1305 под teamKey (сервер ключа не имеет).
 * Имя команды на сервере не хранится вовсе — оно едет в конверте и в командной записи метаданных.
 */

// --- ключи аккаунтов ---

@Serializable
data class PublishKeyRequest(val publicKey: String)

@Serializable
data class AccountKeyResponse(val accountId: String, val publicKey: String)

// --- команды и участники ---

@Serializable
data class TeamCreateRequest(val teamId: String)

/** Роли: `owner` управляет составом и удаляет команду; записи читают/пишут оба. */
@Serializable
data class TeamDto(
    val id: String,
    val ownerAccountId: String,
    val role: String,
    val status: String,
    val createdAt: Long,
    val memberCount: Int,
    /** Sealed-конверт приглашения для текущего аккаунта; null после принятия. */
    val envelope: String? = null,
)

@Serializable
data class TeamsResponse(val teams: List<TeamDto>)

@Serializable
data class TeamMemberDto(
    val accountId: String,
    val role: String,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class TeamMembersResponse(val members: List<TeamMemberDto>)

@Serializable
data class TeamInviteRequest(val accountId: String, val envelope: String)

// Командные записи переиспользуют RecordDto/RecordsResponse/PushRequest/PushResponse:
// формат идентичен, меняется только scope (`/teams/{id}/records` вместо `/vault/records`).
