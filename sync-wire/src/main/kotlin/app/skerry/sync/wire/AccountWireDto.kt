package app.skerry.sync.wire

import kotlinx.serialization.Serializable

/**
 * The account zone's own view of itself (`GET /account/summary`) — read by the web console and by
 * the app (Teams → Server card). Metadata only: counts, sizes and the instance version, never a
 * record's content.
 *
 * [records] counts tombstones too ([tombstones] is how many of them are); [lastSeenAt] is the most
 * recent activity across the account's devices, null for an account that never synced. An instance
 * older than [serverVersion] omits it, and the client reads it as unknown.
 */
@Serializable
data class AccountSummaryResponse(
    val accountId: String,
    val createdAt: Long,
    val syncSeq: Long,
    val devices: Int,
    val activeDevices: Int,
    val records: Int,
    val tombstones: Int,
    val storageBytes: Long,
    val lastSeenAt: Long?,
    val serverVersion: String = "",
)
