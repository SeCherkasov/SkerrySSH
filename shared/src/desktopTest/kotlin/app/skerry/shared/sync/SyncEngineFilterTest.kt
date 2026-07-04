package app.skerry.shared.sync

import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.VaultRecord
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фильтрация «что синхронизировать» ([SyncSettings]) в [SyncEngine]: отключённый тип не пушится и не
 * принимается, а запись настроек ([RecordType.SETTINGS]) синкается всегда и применяется первой (её
 * выключение должно действовать в том же цикле, до записей подчинённых типов). Семантика OFF — без
 * удаления: локальные записи остаются, надгробий не рассылается.
 */
class SyncEngineFilterTest {

    private val password = "correct horse battery staple"

    /** In-memory заглушка сервера: pull отдаёт [serverRecords] один раз (курсор 0→1), push копит. */
    private class FakeSyncClient(var serverRecords: List<RemoteRecord> = emptyList()) : SyncClient {
        val pushed = mutableListOf<RemoteRecord>()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage =
            if (since < 1) RecordPage(serverRecords, 1) else RecordPage(emptyList(), 1)
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
            pushed += records
            return RecordPage(emptyList(), 1)
        }
        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = error("unused")
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = error("unused")
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

    private fun VaultRecord.toRemote() = RemoteRecord(id, type.name, version, updatedAt, deviceId, deleted, blob)

    private fun newVault(deviceId: String) = FileVault(
        path = Files.createTempDirectory("skerry-filter-$deviceId").resolve("vault.json").toString().toPath(),
        crypto = IonspinVaultCrypto(),
        deviceId = deviceId,
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-06-30T00:00:00Z" },
    )

    private val session = SyncSession("acct", "access", "refresh")

    @Test
    fun `disabled type is not pushed while settings and enabled types are`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        SyncSettingsStore(vault).save(SyncSettings(syncSnippets = false))
        vault.put("h1", RecordType.HOST, "host".encodeToByteArray())
        vault.put("s1", RecordType.SNIPPET, "snippet".encodeToByteArray())

        val client = FakeSyncClient()
        SyncEngine(client, vault, InMemorySyncStateStore(), settings = { SyncSettingsStore(vault).load() }).sync(session)

        val pushedTypes = client.pushed.map { it.type }.toSet()
        assertTrue(RecordType.HOST.name in pushedTypes, "enabled HOST must be pushed")
        assertTrue(RecordType.SETTINGS.name in pushedTypes, "SETTINGS must always be pushed")
        assertFalse(RecordType.SNIPPET.name in pushedTypes, "disabled SNIPPET must not be pushed")
    }

    @Test
    fun `incoming disabled type is not applied locally`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devB")
        vault.create(password.toCharArray())

        // Сервер отдаёт чужой сниппет; локально snippets выключены — он не должен осесть в vault.
        val client = FakeSyncClient(
            serverRecords = listOf(RemoteRecord("s9", RecordType.SNIPPET.name, 5, "2026-06-30T00:00:00Z", "devX", false, byteArrayOf(1, 2, 3))),
        )
        SyncEngine(client, vault, InMemorySyncStateStore(), settings = { SyncSettings(syncSnippets = false) }).sync(session)

        assertFalse(vault.records().any { it.id == "s9" }, "incoming disabled SNIPPET must not be merged")
    }

    @Test
    fun `incoming settings apply first and gate same-page records`() = runBlocking {
        initializeVaultCrypto()
        // Источник A готовит валидные шифроблобы под общим dataKey; приёмник B разворачивает тем же ключом.
        val source = newVault("devA")
        source.create(password.toCharArray())
        SyncSettingsStore(source).save(SyncSettings(syncSnippets = false))
        source.put("s1", RecordType.SNIPPET, "snippet".encodeToByteArray())
        val settingsRec = source.records().first { it.type == RecordType.SETTINGS }.toRemote()
        val snippetRec = source.records().first { it.type == RecordType.SNIPPET }.toRemote()

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)

        // Порядок страницы: сниппет ПЕРЕД настройками — движок обязан применить SETTINGS первыми и по
        // ним отфильтровать сниппет из этой же страницы (иначе он бы осел до того, как выключение учлось).
        val client = FakeSyncClient(serverRecords = listOf(snippetRec, settingsRec))
        SyncEngine(client, receiver, InMemorySyncStateStore(), settings = { SyncSettingsStore(receiver).load() }).sync(session)

        assertTrue(receiver.records().any { it.id == SyncSettingsStore.SETTINGS_ID }, "SETTINGS must be applied")
        assertFalse(receiver.records().any { it.id == "s1" }, "snippet gated by just-applied OFF setting must not merge")
    }
}
