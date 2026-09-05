package app.skerry.shared.sync

import app.skerry.server.config.ServerConfig
import app.skerry.shared.vault.RecordType
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The server's allow-list of record types is a hand-written mirror of [RecordType] across a module
 * boundary, and a type missing from it is not a loud failure: the push is refused with 400, which
 * [SyncEngine] reads as `Kind.PROTOCOL` and — for an optional type — swallows as "this server is
 * just older". The record then never leaves the device and nothing anywhere says so.
 *
 * So the correspondence is asserted against the real route rather than trusted: every type the
 * client can sync must be accepted, and the one type that never syncs must still be refused, or the
 * exclusion below is an accident rather than a decision.
 */
class ServerRecordTypeAllowListTest {

    private val accountId = "types@example.com"

    /**
      * Derived, not restated: the client's own answer for what never leaves the device (today only
      * TERMINAL_HISTORY — per-host, large, sensitive). Naming it here would make a second type added
      * to [SyncSettings] fail this test against the server, which would be the wrong culprit.
      */
    private val neverSynced = RecordType.entries.filterNot { SyncSettings().shouldSync(it) }.toSet()

    @Test
    fun `the server accepts every record type the client can sync and refuses the one it cannot`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-types-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = startTestServer(config, port)
        val client = KtorSyncClient("http://localhost:$port")
        try {
            val session = client.register(accountId, ByteArray(32) { 1 }, ByteArray(48), DeviceInfo("dev", "Laptop"))

            // One batch, because the route validates every type before it applies any of them: a type
            // missing from the allow-list refuses the whole push, and `push` throws right here. The
            // echoed page is the weaker second half of the oracle — it catches a server that accepts
            // a type and then drops it.
            val synced = RecordType.entries.filter { it !in neverSynced }
            val page = client.push(session, synced.map { record(it) })
            assertEquals(synced.map { it.name }.toSet(), page.records.map { it.type }.toSet())

            neverSynced.forEach { type ->
                val refused = assertFailsWith<SyncException> { client.push(session, listOf(record(type))) }
                assertEquals(
                    SyncException.Kind.PROTOCOL,
                    refused.kind,
                    "$type is excluded from sync on the client, so the server must refuse it too",
                )
            }
        } finally {
            client.close()
            server.stop(100, 100)
            Files.deleteIfExists(dbFile)
        }
    }

    private fun record(type: RecordType) = RemoteRecord(
        id = "id-${type.name}",
        type = type.name,
        version = 1,
        updatedAt = "2026-06-29T00:00:00Z",
        deviceId = "dev",
        deleted = false,
        blob = "ciphertext".encodeToByteArray(),
    )
}
