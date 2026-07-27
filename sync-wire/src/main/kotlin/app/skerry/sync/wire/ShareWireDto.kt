package app.skerry.sync.wire

import kotlinx.serialization.Serializable

/**
 * Wire contract for shared terminal sessions (session sharing): a member opens a live session to
 * their team, other members watch it and — when the host allows it — type into it.
 *
 * The relay is zero-knowledge like the rest of the server. Terminal frames travel over a WebSocket
 * as opaque binary blobs sealed under the team key, and the only thing described here is the
 * directory of what is currently on offer:
 * - [ShareDto.meta] — the session's label (host name, user@host), sealed under the team key too, so
 *   the server never learns which machine is being shared;
 * - [ShareDto.hostAccountId] — who is sharing, which the server already knows from membership.
 */
@Serializable
data class ShareDto(
    val shareId: String,
    val hostAccountId: String,
    /** Sealed session label; base64 of a blob only the team's members can open. */
    val meta: String,
    val startedAt: Long,
    val viewers: Int,
)

@Serializable
data class SharesResponse(val shares: List<ShareDto>)
