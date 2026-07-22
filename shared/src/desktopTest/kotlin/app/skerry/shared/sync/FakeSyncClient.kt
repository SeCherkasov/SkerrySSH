package app.skerry.shared.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * In-memory server stub for [SyncEngine] tests: pull returns [serverRecords] once (cursor 0->1),
 * push accumulates into [pushed]. Auth/pairing methods are unused by the engine and throw.
 */
internal class FakeSyncClient(var serverRecords: List<RemoteRecord> = emptyList()) : SyncClient {
    val pushed = mutableListOf<RemoteRecord>()
    override suspend fun pull(session: SyncSession, since: Long): RecordPage =
        if (since < 1) RecordPage(serverRecords, 1) else RecordPage(emptyList(), 1)
    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
        pushed += records
        return RecordPage(emptyList(), 1)
    }
    override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = error("unused")
    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = error("unused")
    override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = error("unused")
    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = error("unused")
    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = error("unused")
    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = error("unused")
    override suspend fun refresh(session: SyncSession): SyncSession = error("unused")
    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = error("unused")
    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = error("unused")
    override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()
    override suspend fun ping(): Boolean = true
    override suspend fun close() {}
}
