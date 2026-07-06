package app.skerry.shared.team

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSettings
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end sharing round trip: members A and B hold per-team vaults with a shared teamKey
 * (obtained via invitation) and sync records through the team-scope server using the same
 * [SyncEngine] as the account vault. The server (an in-memory LWW stub here) only sees
 * ciphertext; decryption happens locally on merge into the team vault.
 */
class TeamSyncRoundTripTest {

    /** Mini team-scope LWW server with the same semantics as TeamRecordRepository. */
    private class FakeTeamServer : TeamClient {
        private val records = linkedMapOf<String, Pair<RemoteRecord, Long>>() // id -> (record, seq)
        private var seq = 0L

        override suspend fun pullTeam(session: SyncSession, teamId: String, since: Long): RecordPage {
            val page = records.values.filter { it.second > since }.sortedBy { it.second }
            return RecordPage(page.map { it.first }, page.lastOrNull()?.second ?: since)
        }

        override suspend fun pushTeam(session: SyncSession, teamId: String, records: List<RemoteRecord>): RecordPage {
            val result = records.map { rec ->
                val existing = this.records[rec.id]?.first
                val wins = existing == null || rec.version > existing.version ||
                    (rec.version == existing.version && rec.deviceId > existing.deviceId)
                if (wins) {
                    seq += 1
                    this.records[rec.id] = rec to seq
                    rec
                } else {
                    existing
                }
            }
            return RecordPage(result, seq)
        }

        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray) = error("unused")
        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): ByteArray? = error("unused")
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = error("unused")
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
    }

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-abc"

    private fun vaultsFor(member: String) = TeamVaults(
        dir = Files.createTempDirectory("skerry-teams-$member").toString().toPath(),
        crypto = crypto,
        deviceId = "dev-$member",
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-07-04T00:00:00Z" },
    )

    private fun engineFor(vault: app.skerry.shared.vault.Vault, server: TeamClient) = SyncEngine(
        TeamScopedSyncClient(server, teamId),
        vault,
        InMemorySyncStateStore(),
        settings = { SyncSettings() },
    )

    @Test
    fun `record shared by A appears decrypted at B and tombstone comes back`() = runBlocking {
        initializeVaultCrypto()
        val server = FakeTeamServer()
        val teamKey = crypto.newDataKey()
        val session = SyncSession("acct", "access", "refresh")

        // A: puts a host into the team vault and syncs
        val aliceVault = vaultsFor("alice").open(teamId, teamKey)!!
        aliceVault.put("h1", RecordType.HOST, """{"name":"prod"}""".encodeToByteArray())
        val aliceEngine = engineFor(aliceVault, server)
        aliceEngine.sync(session)

        // B: got teamKey from the invitation, opens its team vault and syncs
        val bobVault = vaultsFor("bob").open(teamId, teamKey)!!
        val bobEngine = engineFor(bobVault, server)
        bobEngine.sync(session)

        assertContentEquals("""{"name":"prod"}""".encodeToByteArray(), bobVault.openPayload("h1"))

        // B removes the host -> tombstone propagates to A
        bobVault.remove("h1")
        bobEngine.sync(session)
        aliceEngine.sync(session)

        assertNull(aliceVault.openPayload("h1"))
        assertTrue(aliceVault.records().first { it.id == "h1" }.deleted)
    }

    @Test
    fun `team vault does not open with a wrong team key`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("carol")
        val rightKey = crypto.newDataKey()
        val vault = vaults.open(teamId, rightKey)!!
        vault.put("h1", RecordType.HOST, "x".encodeToByteArray())
        vault.lock()
        vaults.lockAll()

        assertNull(vaults.open(teamId, crypto.newDataKey()))
        assertEquals(true, vaults.open(teamId, rightKey)?.isUnlocked)
    }

    @Test
    fun `reset removes the local team vault file`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("dave")
        val key = crypto.newDataKey()
        vaults.open(teamId, key)!!.put("h1", RecordType.HOST, "x".encodeToByteArray())

        vaults.reset(teamId)

        // file removed -> opening recreates an empty vault
        val fresh = vaults.open(teamId, key)!!
        assertEquals(0, fresh.records().size)
    }
}
