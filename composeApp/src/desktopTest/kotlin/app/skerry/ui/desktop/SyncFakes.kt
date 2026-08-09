package app.skerry.ui.desktop

import app.skerry.shared.sync.AccountSummary
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.ui.sync.SyncCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A sync coordinator that is wired but never reachable: enough for the forms that read its status,
 * and loud if one of them ever tries to talk to a server from a test.
 */
internal fun offlineCoordinator(): SyncCoordinator = SyncCoordinator(
    clientFactory = { UnreachableClient },
    crypto = IonspinVaultCrypto(),
    vault = EmptyVault,
    deviceIdProvider = { "dev-test" },
)

/**
 * [offlineCoordinator] for the length of [body], closed after it.
 *
 * The coordinator owns a scope and launches its watchers in `init`, so one built per test and left
 * behind outlives the test for the rest of the JVM. Built here rather than inside the composition:
 * a composable that constructs it would build another on every recomposition.
 */
internal fun withOfflineCoordinator(body: (SyncCoordinator) -> Unit) {
    val coordinator = offlineCoordinator()
    try {
        body(coordinator)
    } finally {
        // Never over the body's own failure: an exception thrown from `finally` replaces the one
        // already on its way out, and the assertion that actually failed would vanish from the log.
        runCatching { coordinator.close() }
    }
}

/** Every call is a bug in the test: these forms are driven only up to the point of submitting. */
internal object UnreachableClient : SyncClient {
    private fun no(): Nothing = error("the sync client was called; the form test should not submit")
    override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = no()
    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = no()
    override suspend fun changePassword(
        accountId: String,
        currentAuthKey: ByteArray,
        newAuthKey: ByteArray,
        newWrappedDataKey: ByteArray,
        device: DeviceInfo,
    ): SyncSession = no()
    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = no()
    override suspend fun pull(session: SyncSession, since: Long): RecordPage = no()
    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = no()
    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = no()
    override suspend fun accountSummary(session: SyncSession): AccountSummary = no()
    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = no()
    override suspend fun refresh(session: SyncSession): SyncSession = no()
    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = no()
    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = no()
    override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()
    override suspend fun ping(): Boolean = false
    override suspend fun close() = Unit
}

/** A vault with nothing in it; the form never reads past its unlocked state. */
internal object EmptyVault : Vault {
    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
    override fun lock() = Unit
    override fun reset() = Unit
    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    override fun verifyPassword(password: CharArray): Boolean = true
}
