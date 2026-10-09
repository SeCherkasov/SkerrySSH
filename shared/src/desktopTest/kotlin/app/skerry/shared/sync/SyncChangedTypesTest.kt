package app.skerry.shared.sync

import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncChangedTypesTest {
    @Test
    fun `changed types include applied tombstones but exclude filtered rejected and idempotent records`() = runBlocking {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val fs = FakeFileSystem()
        fun vault(name: String) = FileVault("/$name".toPath(), crypto, name, fs, now = { "2026-10-08T12:00:00Z" })
        val source = vault("source")
        source.createWithDataKey(crypto.newDataKey())
        source.put("host", RecordType.HOST, byteArrayOf(1))
        source.put("snippet", RecordType.SNIPPET, byteArrayOf(2))
        source.put("tunnel", RecordType.TUNNEL, byteArrayOf(3))
        val receiver = vault("receiver")
        receiver.createWithDataKey(source.exportDataKey()!!)
        receiver.mergeRemote(source.records().filter { it.type == RecordType.HOST })
        source.remove("tunnel")
        val records = source.records().map { it.toRemote() } + RemoteRecord(
            "forged", RecordType.CREDENTIAL.name, 1, "2026-10-08T12:00:00Z", "evil", false, ByteArray(64),
        )
        val client = FakeSyncClient(serverRecords = records)
        val state = InMemorySyncStateStore()
        val engine = SyncEngine(client, receiver, state, settings = { SyncSettings(syncSnippets = false) })
        val session = SyncSession("account", "access", "refresh")
        val outcome = engine.sync(session)
        assertEquals(setOf(RecordType.TUNNEL), outcome.changedTypes)
        assertEquals(1, outcome.pulled)
        assertEquals(1, outcome.rejected)
        assertEquals(emptySet(), engine.sync(session).changedTypes)
        source.lock()
        receiver.lock()
    }
}
