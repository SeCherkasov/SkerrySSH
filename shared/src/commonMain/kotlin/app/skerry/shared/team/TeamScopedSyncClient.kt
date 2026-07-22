package app.skerry.shared.team

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import kotlinx.coroutines.flow.Flow

/**
 * Adapts team scope to [SyncClient]: pull/push close over `/teams/{id}/records`, letting team
 * records run through the same [app.skerry.shared.sync.SyncEngine] (LWW, paging, cursor) as the
 * account vault. The rest of the contract is deliberately unsupported — the engine never calls it.
 */
class TeamScopedSyncClient(
    private val teams: TeamClient,
    private val teamId: String,
) : SyncClient {

    override suspend fun pull(session: SyncSession, since: Long): RecordPage =
        teams.pullTeam(session, teamId, since)

    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage =
        teams.pushTeam(session, teamId, records)

    override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = unsupported()
    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = unsupported()
    override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = unsupported()
    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = unsupported()
    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = unsupported()
    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = unsupported()
    override suspend fun refresh(session: SyncSession): SyncSession = unsupported()
    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = unsupported()
    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = unsupported()
    override fun changes(session: SyncSession): Flow<SyncSignal> = unsupported()
    override suspend fun ping(): Boolean = unsupported()
    override suspend fun close() = Unit // transport is owned by the caller (shared HTTP client)

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("team-scoped client only supports pull/push")
}
