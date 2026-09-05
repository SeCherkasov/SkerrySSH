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
import kotlin.test.assertFailsWith
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

    /**
     * A server that predates the type. [refusal] is how it answers a batch carrying one: a real one
     * fails the type's validation (400 → PROTOCOL) or has no route for it at all (404).
     */
    private class OldServer(
        private val unknown: RecordType,
        private val refusal: SyncException.Kind = SyncException.Kind.PROTOCOL,
    ) : FakeSyncClient() {
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
            if (records.any { it.type == unknown.name }) {
                throw SyncException(refusal, "unknown record type: ${unknown.name}")
            }
            return super.push(session, records)
        }
    }

    /**
     * The other failures of that same batch are NOT "the server is just old" and must not be
     * swallowed as one: a 401 says the session is over, a 429 says to back off, a 5xx says the
     * server is broken. Hidden, a batch that will never be accepted looked exactly like an optional
     * one, and nothing anywhere said otherwise.
     */
    @Test
    fun `a real failure of the optional batch is not mistaken for an old server`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devC")
        vault.create(password.toCharArray())
        vault.put("h1", RecordType.HOST, "host".encodeToByteArray())
        vault.put("ca1", RecordType.TRUSTED_CA, "ca".encodeToByteArray())

        val client = OldServer(RecordType.TRUSTED_CA, refusal = SyncException.Kind.UNAUTHORIZED)

        val failure = assertFailsWith<SyncException> {
            SyncEngine(client, vault, InMemorySyncStateStore()).sync(session)
        }
        assertEquals(SyncException.Kind.UNAUTHORIZED, failure.kind)
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

    /**
     * The optional batch carries more than one type, and the server refuses a whole batch on the
     * first type it can't name. So a deployment that predates only the newest of them must still
     * receive the older ones — TEAM_PEER above all: it is the fingerprint every later seal is held
     * to (#319), and a second device without it seals to whatever the server answers.
     */
    @Test
    fun `one refused optional type does not silence the rest of that batch`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devD")
        vault.create(password.toCharArray())
        vault.put("h1", RecordType.HOST, "host".encodeToByteArray())
        vault.put("ca1", RecordType.TRUSTED_CA, "ca".encodeToByteArray())
        vault.put("p1", RecordType.TEAM_PEER, "pin".encodeToByteArray())
        vault.put("skerry.library.order", RecordType.LIBRARY_ORDER, "order".encodeToByteArray())

        val client = OldServer(RecordType.LIBRARY_ORDER)
        SyncEngine(client, vault, InMemorySyncStateStore()).sync(session)

        val pushedTypes = client.pushed.map { it.type }.toSet()
        assertTrue(RecordType.HOST.name in pushedTypes)
        assertTrue(RecordType.TRUSTED_CA.name in pushedTypes, "a CA must still reach a server that only lacks the order type")
        assertTrue(RecordType.TEAM_PEER.name in pushedTypes, "a verified peer pin must still reach the account's other devices")
        assertFalse(RecordType.LIBRARY_ORDER.name in pushedTypes, "the refused type must not be recorded as pushed")
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
