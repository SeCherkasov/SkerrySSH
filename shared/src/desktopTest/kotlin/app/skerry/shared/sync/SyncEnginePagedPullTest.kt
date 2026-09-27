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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server answers a pull in pages, so a device starting from zero walks several of them. Each
 * page must land, the walk must end on the empty one, and a setting that arrives on one page must
 * already gate what the pages after it bring.
 */
class SyncEnginePagedPullTest {

    private val password = "correct horse battery staple"
    private val session = SyncSession("acct", "access", "refresh")

    private fun newVault(deviceId: String) = FileVault(
        path = Files.createTempDirectory("skerry-paged-$deviceId").resolve("vault.json").toString().toPath(),
        crypto = IonspinVaultCrypto(),
        deviceId = deviceId,
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-09-27T00:00:00Z" },
    )

    /** Serves [records] in their order, [pageSize] at a time, with the last one's position as the cursor. */
    private class PagedServer(private val records: List<RemoteRecord>, private val pageSize: Int) : FakeSyncClient() {
        val pulledFrom = mutableListOf<Long>()

        override suspend fun pull(session: SyncSession, since: Long): RecordPage {
            pulledFrom += since
            val page = records.drop(since.toInt()).take(pageSize)
            return RecordPage(page, since + page.size)
        }

        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage =
            RecordPage(emptyList(), this.records.size.toLong())
    }

    @Test
    fun `a delta served in pages lands whole and the walk ends on the empty page`() = runBlocking {
        initializeVaultCrypto()
        val source = newVault("devA")
        source.create(password.toCharArray())
        repeat(5) { source.put("h$it", RecordType.HOST, "host $it".encodeToByteArray()) }
        val server = PagedServer(source.records().filter { it.type == RecordType.HOST }.map { it.toRemote() }, pageSize = 2)

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)
        SyncEngine(server, receiver, InMemorySyncStateStore()).sync(session)

        assertEquals((0 until 5).map { "h$it" }.toSet(), receiver.records().filter { it.type == RecordType.HOST }.map { it.id }.toSet())
        assertEquals(listOf(0L, 2L, 4L, 5L), server.pulledFrom.take(4), "one pull per page, then one that finds nothing")
    }

    @Test
    fun `a setting on one page gates the records of the pages after it`() = runBlocking {
        initializeVaultCrypto()
        val source = newVault("devA")
        source.create(password.toCharArray())
        source.put("h1", RecordType.HOST, "host".encodeToByteArray())
        SyncSettingsStore(source).save(SyncSettings(syncSnippets = false))
        source.put("s1", RecordType.SNIPPET, "snippet".encodeToByteArray())
        val byId = source.records().associateBy { it.id }
        val ordered = listOf(byId.getValue("h1"), byId.getValue(SyncSettingsStore.SETTINGS_ID), byId.getValue("s1"))
        val server = PagedServer(ordered.map { it.toRemote() }, pageSize = 1)

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)
        SyncEngine(server, receiver, InMemorySyncStateStore(), settings = { SyncSettingsStore(receiver).load() }).sync(session)

        assertTrue(receiver.records().any { it.id == "h1" })
        assertTrue(receiver.records().any { it.id == SyncSettingsStore.SETTINGS_ID })
        assertFalse(receiver.records().any { it.id == "s1" }, "a snippet after the page that turned snippets off must not merge")
    }
}
