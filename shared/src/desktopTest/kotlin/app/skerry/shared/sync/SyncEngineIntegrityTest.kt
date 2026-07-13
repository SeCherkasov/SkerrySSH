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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Record integrity through [SyncEngine]: incoming LWW winners whose blob fails authentication
 * against their claimed metadata (a tampered/replayed record from a compromised server) must be
 * rejected by the vault's trial-decrypt, keep the local record intact, and surface in
 * [SyncOutcome.rejected] instead of being applied silently.
 */
class SyncEngineIntegrityTest {

    private val password = "correct horse battery staple"
    private val session = SyncSession("acct", "access", "refresh")

    private fun newVault(deviceId: String) = FileVault(
        path = Files.createTempDirectory("skerry-integrity-$deviceId").resolve("vault.json").toString().toPath(),
        crypto = IonspinVaultCrypto(),
        deviceId = deviceId,
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-07-13T00:00:00Z" },
    )

    @Test
    fun `tampered incoming record is rejected, counted, and does not clobber the local one`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        vault.put("h1", RecordType.HOST, "genuine".encodeToByteArray())

        // The server claims a fleet-wide overwrite of h1 with an undecryptable blob at version=MAX.
        val client = FakeSyncClient(
            serverRecords = listOf(
                RemoteRecord("h1", RecordType.HOST.name, Long.MAX_VALUE, "2026-07-13T00:00:00Z", "evil", false, ByteArray(64)),
            ),
        )
        val outcome = SyncEngine(client, vault, InMemorySyncStateStore()).sync(session)

        assertEquals(1, outcome.rejected, "the tampered record must surface in the outcome")
        assertEquals(0, outcome.pulled)
        assertEquals(1L, vault.records().first { it.id == "h1" }.version)
        assertContentEquals("genuine".encodeToByteArray(), vault.openPayload("h1"))
        // The genuine local record is still pushed as usual.
        assertTrue(client.pushed.any { it.id == "h1" })
    }
}
