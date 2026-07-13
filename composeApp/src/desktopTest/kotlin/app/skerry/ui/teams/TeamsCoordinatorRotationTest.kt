package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.DataKey
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rotation-path tests for [TeamsCoordinator] (audit findings #2 and #3): removing a member re-keys
 * the team. The account vault and per-team vaults are real [FileVault]s over the system filesystem;
 * only the network ([TeamClient]) is faked so we can drive epochs, conflicts, and call counts.
 */
class TeamsCoordinatorRotationTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-rot"
    private val self = "alice@example.com"
    private val carol = "carol@example.com"

    /** Fake team network: records rekey calls, drives the server epoch and conflict outcomes. */
    private class FakeTeamClient(
        private val self: String,
        private val teamId: String,
        private val others: List<Pair<String, AccountKeys>>,
    ) : TeamClient {
        var serverEpoch: Long = 0
        val rekeyCalls = mutableListOf<Long>()
        val removed = mutableListOf<String>()
        /** Front of the deque decides each rekey: true = accept, false = throw CONFLICT. Empty = accept. */
        val rekeyOutcomes = ArrayDeque<Boolean>()

        private val store = linkedMapOf<String, Pair<RemoteRecord, Long>>()
        private var seq = 0L

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = self, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 1 + others.size,
                envelope = null, keyEpoch = serverEpoch, keyEnvelope = null,
            ),
        )

        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> =
            listOf(TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0)) +
                others.map { TeamMember(it.first, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0) }

        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? =
            others.firstOrNull { it.first == accountId }?.second

        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
            rekeyCalls += newEpoch
            val accept = if (rekeyOutcomes.isEmpty()) true else rekeyOutcomes.removeFirst()
            if (!accept) throw SyncException(SyncException.Kind.CONFLICT, "stale epoch")
            serverEpoch = newEpoch
        }

        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) {
            removed += accountId
        }

        override suspend fun pullTeam(session: SyncSession, teamId: String, since: Long): RecordPage {
            val page = store.values.filter { it.second > since }.sortedBy { it.second }
            return RecordPage(page.map { it.first }, page.lastOrNull()?.second ?: since)
        }

        override suspend fun pushTeam(session: SyncSession, teamId: String, records: List<RemoteRecord>): RecordPage {
            val result = records.map { rec ->
                val existing = store[rec.id]?.first
                val wins = existing == null || rec.version > existing.version ||
                    (rec.version == existing.version && rec.deviceId > existing.deviceId)
                if (wins) { seq += 1; store[rec.id] = rec to seq; rec } else existing
            }
            return RecordPage(result, seq)
        }

        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
    }

    private class Fixture(
        val vault: FileVault,
        val teamVaults: TeamVaults,
        val teamDir: okio.Path,
    )

    private fun newFixture(): Fixture {
        val vaultFile = Files.createTempFile("skerry-acct", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile) // FileVault creates it
        val teamDir = Files.createTempDirectory("skerry-teamvaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        val teamVaults = TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        return Fixture(vault, teamVaults, teamDir)
    }

    private fun carolKeys() = AccountKeys(crypto.newSharingKeyPair().publicKey, crypto.newSigningKeyPair().publicKey)

    private fun coordinator(f: Fixture, client: TeamClient) = TeamsCoordinator(
        session = { SyncSession(self, "access", "refresh") },
        client = { client },
        vault = f.vault,
        crypto = crypto,
        teamVaults = f.teamVaults,
        teamState = InMemorySyncStateStore(),
        newTeamId = { "unused" },
    )

    @Test
    fun `rotation aborts before touching the server when the team vault can't be opened`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = TeamKeyStore(f.vault)
        val oldKey = crypto.newDataKey()
        ks.put(teamId, "Ops", TeamRole.OWNER, oldKey, epoch = 0)
        // Seed a shared record, then corrupt the on-disk team vault so it can't be opened under oldKey.
        f.teamVaults.open(teamId, oldKey)!!.put("h1", RecordType.HOST, "secret".encodeToByteArray())
        f.teamVaults.lockAll()
        val teamFile = f.teamDir / "$teamId.vault"
        FileSystem.SYSTEM.write(teamFile) { writeUtf8("corrupted") }

        val client = FakeTeamClient(self, teamId, listOf(carol to carolKeys()))
        val coord = coordinator(f, client)
        coord.removeMember(teamId, carol)

        // The member is still removed server-side (best-effort), but the rotation never reached the
        // server: no epoch bump, no distributed envelopes. The old key stays authoritative.
        assertEquals(listOf(carol), client.removed)
        assertEquals(emptyList(), client.rekeyCalls)
        assertEquals(0, ks.get(teamId)!!.epoch)
        assertEquals(TeamsFailure.KeyMissing, coord.lastError.value)
        // The corrupt file must NOT be deleted — it may hold records that were never pushed.
        assertTrue(FileSystem.SYSTEM.exists(teamFile))
    }

    @Test
    fun `rotation derives the next epoch from the server, not a stale local epoch`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = TeamKeyStore(f.vault)
        val oldKey = crypto.newDataKey()
        ks.put(teamId, "Ops", TeamRole.OWNER, oldKey, epoch = 0) // local epoch lags behind the server
        f.teamVaults.open(teamId, oldKey)!!.put("h1", RecordType.HOST, "secret".encodeToByteArray())

        val client = FakeTeamClient(self, teamId, listOf(carol to carolKeys()))
        client.serverEpoch = 2 // server is two rotations ahead of this device's local key
        val coord = coordinator(f, client)
        coord.removeMember(teamId, carol)

        // newEpoch is serverEpoch + 1 = 3, not localEpoch + 1 = 1 (which would 409 forever).
        assertEquals(listOf(3L), client.rekeyCalls)
        assertEquals(3, ks.get(teamId)!!.epoch)
        assertNull(coord.lastError.value)
        // The record was re-encrypted under the new key and is still readable through it.
        val newKey = ks.get(teamId)!!.dataKey()!!
        assertContentEquals("secret".encodeToByteArray(), f.teamVaults.open(teamId, newKey)!!.openPayload("h1"))
    }

    @Test
    fun `rotation retries a losing epoch race and commits the winning attempt`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = TeamKeyStore(f.vault)
        val oldKey = crypto.newDataKey()
        ks.put(teamId, "Ops", TeamRole.OWNER, oldKey, epoch = 0)
        f.teamVaults.open(teamId, oldKey)!!.put("h1", RecordType.HOST, "secret".encodeToByteArray())

        val client = FakeTeamClient(self, teamId, listOf(carol to carolKeys()))
        client.rekeyOutcomes.addAll(listOf(false, true)) // first attempt conflicts, second wins
        val coord = coordinator(f, client)
        coord.removeMember(teamId, carol)

        assertEquals(2, client.rekeyCalls.size) // retried after the conflict
        assertEquals(1, ks.get(teamId)!!.epoch) // the winning attempt committed
        assertNull(coord.lastError.value)
    }

    private companion object {
        const val NOW = "2026-07-13T00:00:00Z"
    }
}
