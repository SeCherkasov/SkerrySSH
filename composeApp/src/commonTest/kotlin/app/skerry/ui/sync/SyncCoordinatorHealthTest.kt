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
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MasterKey
import app.skerry.shared.vault.RecordType
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
 * Поллер доступности сервера ([SyncCoordinator.serverReachable]): питает индикатор «сервер работает и
 * доступен» на главных экранах. Проверяем, что пинг идёт по сохранённой привязке (даже при залоченном
 * vault), отражает up/down и гаснет в UNKNOWN, когда sync не настроен / отвязан.
 */
class SyncCoordinatorHealthTest {

    @Test
    fun configured_server_reports_reachable_even_while_vault_locked() = runBlocking {
        val client = FakePingClient(reachable = true)
        val store = configuredStore()
        // vault залочен (StubVault.exportDataKey == null) — пинг health всё равно должен идти.
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
        val sut = SyncCoordinator({ client }, StubCrypto(), StubVault()) // пустой стор → не настроено
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
 * Сброс курсора синка координатором. [SyncCoordinator.disconnect] обнуляет курсор (следующее
 * подключение делает полный re-pull, а не продолжает с tip прошлой сессии), а повторное включение
 * ранее выключенного типа в [SyncCoordinator.setSyncSettings] — обнуляет курсор для backfill
 * (модель Termius: пока тип был OFF, курсор проскочил чужие записи этого типа).
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
        // Выключение типа НЕ трогает курсор — просто перестаём слать/принимать этот тип.
        sut.setSyncSettings(SyncSettings(syncHosts = false))
        delay(150)
        assertEquals(7L, state.cursor("maya"))
        // Повторное включение → полный re-pull: курсор обнуляется, чтобы подтянуть пропущенные записи.
        sut.setSyncSettings(SyncSettings(syncHosts = true))
        withTimeout(3_000) { while (state.cursor("maya") != 0L) delay(10) }
        assertEquals(0L, state.cursor("maya"))
    }

    private fun boundStore() = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl = "https://sync.test", accountId = "maya", deviceId = "dev-1"))
    }
}

/**
 * Cursor-guard live-pull: второй разрыватель петли push→WS→push. WS-сигнал несёт курсор аккаунта;
 * наш собственный push возвращается тем же сигналом с курсором, который мы уже знаем — без guard'а это
 * запускало бы лишний синк (а в паре с безусловным серверным publish — петлю). Тянем дельту ТОЛЬКО когда
 * присланный курсор обгоняет локальный (= реально появились чужие изменения).
 */
class SyncCoordinatorWatchGuardTest {

    @Test
    fun ws_signal_triggers_pull_only_when_cursor_advances() = runBlocking {
        val state = InMemorySyncStateStore().apply { setCursor("maya", 10) }
        val sut = SyncCoordinator(
            { FakePingClient(reachable = true) }, StubCrypto(), StubVault(),
            configStore = boundStore(), syncState = state,
        )
        // Равный курсор = эхо нашего же push'а: не тянем (иначе петля). Отставший — тем более.
        assertEquals(false, sut.signalAdvancesCursor("maya", 10))
        assertEquals(false, sut.signalAdvancesCursor("maya", 3))
        // Курсор обогнал локальный = появились чужие изменения: тянем дельту.
        assertEquals(true, sut.signalAdvancesCursor("maya", 11))
    }

    private fun boundStore() = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl = "https://sync.test", accountId = "maya", deviceId = "dev-1"))
    }
}

/** Клиент, у которого осмыслен только [ping]; остальное health-поллер не вызывает. */
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
    override fun changes(session: SyncSession): Flow<Long> = nope()
    private fun nope(): Nothing = throw NotImplementedError("health-поллер не должен это вызывать")
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
