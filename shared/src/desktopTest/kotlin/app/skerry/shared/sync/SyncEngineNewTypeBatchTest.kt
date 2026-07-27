package app.skerry.shared.sync

import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Record types added after a self-hosted server was deployed go out in their own push batch. The
 * server rejects a whole batch on an unknown type, so mixing them with hosts/keys would mean one
 * new type takes down all syncing until the operator upgrades the server.
 */
class SyncEngineNewTypeBatchTest {

    private val password = "correct horse battery staple"
    private val session = SyncSession("acct", "access", "refresh")

    private fun newVault(deviceId: String) = FileVault(
        path = Files.createTempDirectory("skerry-newtype-$deviceId").resolve("vault.json").toString().toPath(),
        crypto = IonspinVaultCrypto(),
        deviceId = deviceId,
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-07-27T00:00:00Z" },
    )

    /** A server that predates the type: it rejects any batch containing it, as the real one does. */
    private class OldServer(private val unknown: RecordType) : FakeSyncClient() {
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
            if (records.any { it.type == unknown.name }) error("unknown record type: ${unknown.name}")
            return super.push(session, records)
        }
    }

    @Test
    fun `a server that rejects the new type still receives everything else`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        vault.put("h1", RecordType.HOST, "host".encodeToByteArray())
        vault.put("ca1", RecordType.TRUSTED_CA, "ca".encodeToByteArray())

        val client = OldServer(RecordType.TRUSTED_CA)
        SyncEngine(client, vault, InMemorySyncStateStore()).sync(session)

        val pushedTypes = client.pushed.map { it.type }.toSet()
        assertTrue(RecordType.HOST.name in pushedTypes, "hosts must sync even when the CA type is refused")
        assertFalse(RecordType.TRUSTED_CA.name in pushedTypes, "the refused batch must not be recorded as pushed")
    }

    @Test
    fun `a server that knows the type receives it`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devB")
        vault.create(password.toCharArray())
        vault.put("ca1", RecordType.TRUSTED_CA, "ca".encodeToByteArray())

        val client = FakeSyncClient()
        val outcome = SyncEngine(client, vault, InMemorySyncStateStore()).sync(session)

        assertTrue(RecordType.TRUSTED_CA.name in client.pushed.map { it.type }.toSet())
        assertTrue(outcome.pushed >= 1)
    }
}
