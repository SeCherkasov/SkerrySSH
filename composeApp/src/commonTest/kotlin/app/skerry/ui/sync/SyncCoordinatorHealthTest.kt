package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MasterKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.VaultRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Server-reachability poller ([SyncCoordinator.serverReachable]) feeding the connectivity indicator.
 * Pings using the saved binding even while the vault is locked, reflects up/down, and falls back to
 * UNKNOWN when sync is not configured or disconnected.
 */
class SyncCoordinatorHealthTest {

    @Test
    fun configured_server_reports_reachable_even_while_vault_locked() = runBlocking {
        val client = FakePingClient(reachable = true)
        val store = configuredStore()
        // Vault is locked (StubVault.exportDataKey == null); health ping still proceeds.
        val sut = SyncCoordinator({ client }, StubCrypto(), StubVault(), configStore = store)
        withTimeout(3_000) { sut.serverReachable.first { it == ServerReachable.REACHABLE } }
        assertTrue(client.pings >= 1)
    }

    @Test
    fun configured_but_down_server_reports_unreachable() = runBlocking {
        val client = FakePingClient(reachable = false)
        val sut = SyncCoordinator({ client }, StubCrypto(), StubVault(), configStore = configuredStore())
        withTimeout(3_000) { sut.serverReachable.first { it == ServerReachable.UNREACHABLE } }
    }

    @Test
    fun unconfigured_stays_unknown_and_never_pings() = runBlocking {
        val client = FakePingClient(reachable = true)
        val sut = SyncCoordinator({ client }, StubCrypto(), StubVault()) // empty store means unconfigured
        delay(300)
        assertEquals(ServerReachable.UNKNOWN, sut.serverReachable.value)
        assertEquals(0, client.pings)
    }

    @Test
    fun disconnect_returns_indicator_to_unknown() = runBlocking {
        val client = FakePingClient(reachable = true)
        val sut = SyncCoordinator({ client }, StubCrypto(), StubVault(), configStore = configuredStore())
        withTimeout(3_000) { sut.serverReachable.first { it == ServerReachable.REACHABLE } }
        sut.disconnect()
        withTimeout(3_000) { sut.serverReachable.first { it == ServerReachable.UNKNOWN } }
    }

    private fun configuredStore() = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl = "https://sync.test", accountId = "maya", deviceId = "dev-1"))
    }
}

/**
 * Sync cursor reset by the coordinator. [SyncCoordinator.disconnect] resets the cursor so the next
 * connection does a full re-pull instead of resuming from the last session's tip. Re-enabling a
 * previously disabled type in [SyncCoordinator.setSyncSettings] also resets the cursor for backfill,
 * since the cursor advanced past other devices' records of that type while it was off.
 */
class SyncCoordinatorCursorTest {

    @Test
    fun disconnect_resets_sync_cursor_for_bound_account() = runBlocking {
        val state = InMemorySyncStateStore().apply { setCursor("maya", 42) }
        val sut = SyncCoordinator(
            { FakePingClient(reachable = true) }, StubCrypto(), StubVault(),
            configStore = boundStore(), syncState = state,
        )
        sut.disconnect()
        withTimeout(3_000) { while (state.cursor("maya") != 0L) delay(10) }
        assertEquals(0L, state.cursor("maya"))
    }

    @Test
    fun reenabling_a_type_resets_cursor_for_backfill() = runBlocking {
        val state = InMemorySyncStateStore().apply { setCursor("maya", 7) }
        val sut = SyncCoordinator(
            { FakePingClient(reachable = true) }, StubCrypto(), StubVault(),
            configStore = boundStore(), syncState = state,
        )
        // Disabling a type does not touch the cursor; it just stops sending/receiving that type.
        sut.setSyncSettings(SyncSettings(syncHosts = false))
        delay(150)
        assertEquals(7L, state.cursor("maya"))
        // Re-enabling triggers a full re-pull: cursor resets to catch up on missed records.
        sut.setSyncSettings(SyncSettings(syncHosts = true))
        withTimeout(3_000) { while (state.cursor("maya") != 0L) delay(10) }
        assertEquals(0L, state.cursor("maya"))
    }

    private fun boundStore() = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl = "https://sync.test", accountId = "maya", deviceId = "dev-1"))
    }
}

/**
 * Cursor guard for live-pull, breaking the push -> WS -> push loop. A WS signal carries the account
 * cursor; our own push echoes back the same cursor we already know, which without a guard would
 * trigger a redundant sync (or a loop with unconditional server publish). Pulls a delta only when the
 * signaled cursor advances past the local one, i.e. another device actually made changes.
 */
class SyncCoordinatorWatchGuardTest {

    @Test
    fun ws_signal_triggers_pull_only_when_cursor_advances() = runBlocking {
        val state = InMemorySyncStateStore().apply { setCursor("maya", 10) }
        val sut = SyncCoordinator(
            { FakePingClient(reachable = true) }, StubCrypto(), StubVault(),
            configStore = boundStore(), syncState = state,
        )
        // Equal cursor is an echo of our own push; skip (otherwise a loop). A lagging cursor is skipped too.
        assertEquals(false, sut.signalAdvancesCursor("maya", 10))
        assertEquals(false, sut.signalAdvancesCursor("maya", 3))
        // Cursor ahead of local means other devices made changes: pull the delta.
        assertEquals(true, sut.signalAdvancesCursor("maya", 11))
    }

    private fun boundStore() = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl = "https://sync.test", accountId = "maya", deviceId = "dev-1"))
    }
}

/** Client where only [ping] is meaningful; the health poller never calls the rest. */
private class FakePingClient(@Volatile var reachable: Boolean) : SyncClient {
    @Volatile var pings = 0
    override suspend fun ping(): Boolean { pings++; return reachable }
    override suspend fun close() {}
    override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
    override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
    override suspend fun refresh(session: SyncSession): SyncSession = nope()
    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
    override fun changes(session: SyncSession): Flow<SyncSignal> = nope()
    private fun nope(): Nothing = throw NotImplementedError("the health poller should not call this")
}

private class StubCrypto : VaultCrypto {
    override fun newSalt(): ByteArray = no()
    override fun deriveSyncSalt(accountId: String): ByteArray = no()
    override fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey = no()
    override fun newDataKey(): DataKey = no()
    override fun deriveAuthKey(masterKey: MasterKey): ByteArray = no()
    override fun newTransferKey(): ByteArray = no()
    override fun sealDataKeyForTransfer(dataKey: DataKey, transferKey: ByteArray): ByteArray = no()
    override fun openTransferredDataKey(transferKey: ByteArray, envelope: ByteArray): DataKey? = no()
    override fun wrapDataKey(masterKey: MasterKey, dataKey: DataKey): ByteArray = no()
    override fun unwrapDataKey(masterKey: MasterKey, wrapped: ByteArray): DataKey? = no()
    override fun seal(dataKey: DataKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray = no()
    override fun open(dataKey: DataKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray? = no()
    override fun newSharingKeyPair(): SharingKeyPair = no()
    override fun sharingKeyPairFromBytes(publicKey: ByteArray, secretKey: ByteArray): SharingKeyPair = no()
    override fun sealForRecipient(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray = no()
    override fun openSealedEnvelope(keyPair: SharingKeyPair, envelope: ByteArray): ByteArray? = no()
    private fun no(): Nothing = throw NotImplementedError()
}

private class StubVault : Vault {
    override fun exists(): Boolean = false
    override val isUnlocked: Boolean = false
    override fun create(password: CharArray) = no()
    override fun unlock(password: CharArray): UnlockResult = no()
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = no()
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = no()
    override fun lock() {}
    override fun reset() {}
    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): List<VaultRecord> = emptyList()
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) {}
    override fun remove(id: String) {}
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = false
    override fun verifyPassword(password: CharArray): Boolean = false
    private fun no(): Nothing = throw NotImplementedError()
}
