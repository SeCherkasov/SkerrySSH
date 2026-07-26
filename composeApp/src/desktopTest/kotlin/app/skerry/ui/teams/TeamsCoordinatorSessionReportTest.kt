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
import app.skerry.shared.team.TeamScopeGrantEntry
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopeSummary
import app.skerry.shared.team.TeamSessionKind
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reporting a session to the team's activity feed. What matters here is *what never gets reported*:
 * a host in the personal catalog is nobody else's business, and a report must not surface an error
 * or take a connection down with it.
 */
class TeamsCoordinatorSessionReportTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-report"
    private val self = "alice@example.com"

    private class Report(val teamId: String, val recordId: String, val kind: TeamSessionKind, val durationSec: Long?)

    private class FakeTeamClient(private val self: String, private val teamId: String) : TeamClient {
        val reports = mutableListOf<Report>()
        var reportFailure: Exception? = null
        private val store = linkedMapOf<String, Triple<RemoteRecord, Long, String>>()
        private val scopeGrants = linkedMapOf<String, MutableMap<String, ByteArray>>()
        private var seq = 0L

        override suspend fun reportSessionEvent(
            session: SyncSession,
            teamId: String,
            recordId: String,
            kind: TeamSessionKind,
            durationSec: Long?,
        ) {
            reportFailure?.let { throw it }
            reports += Report(teamId, recordId, kind, durationSec)
        }

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = self, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 1,
                envelope = null, keyEpoch = 0, keyEnvelope = null,
            ),
        )

        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> =
            scopeGrants.map { (id, grants) -> TeamScopeSummary(id, 0, grants.size, grants[session.accountId]) }

        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) {
            scopeGrants[scopeId] = mutableMapOf(session.accountId to envelope)
        }

        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage {
            val page = store.values.filter { it.second > since && it.third == ref.scopeId }.sortedBy { it.second }
            return RecordPage(page.map { it.first }, page.lastOrNull()?.second ?: since)
        }

        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage {
            val result = records.map { rec ->
                val existing = store[rec.id]
                val wins = existing == null || rec.version > existing.first.version
                if (wins) { seq += 1; store[rec.id] = Triple(rec, seq, ref.scopeId); rec } else existing.first
            }
            return RecordPage(result, seq)
        }

        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? = null
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> =
            listOf(TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0))
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> =
            scopeGrants[scopeId]?.keys?.map { TeamScopeGrantEntry(it, 0) } ?: emptyList()
        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) = error("unused")
        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) = error("unused")
        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
    }

    private class Fixture(val vault: FileVault, val teamVaults: TeamVaults)

    private fun newFixture(): Fixture {
        val vaultFile = Files.createTempFile("skerry-acct", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile)
        val teamDir = Files.createTempDirectory("skerry-teamvaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        return Fixture(vault, TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW }))
    }

    private fun coordinator(f: Fixture, client: TeamClient, ids: Iterator<String> = listOf("prod").iterator()) =
        TeamsCoordinator(
            session = { SyncSession(self, "access", "refresh") },
            client = { client },
            vault = f.vault,
            crypto = crypto,
            teamVaults = f.teamVaults,
            teamState = InMemorySyncStateStore(),
            newId = { ids.next() },
            // Unconfined: a report is launched into this scope, and the assertions must not race it.
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun seedTeam(f: Fixture) =
        TeamKeyStore(f.vault).also { it.put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0) }

    @Test
    fun `a session on a shared host is reported to the team that holds it`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        f.vault.put("h1", RecordType.HOST, """{"label":"db"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId), "h1", RecordType.HOST)

        coord.reportSessionOpened("h1")
        coord.reportSessionRecorded("h1", durationSec = 754)

        assertEquals(listOf(TeamSessionKind.OPEN, TeamSessionKind.RECORD), client.reports.map { it.kind })
        assertEquals(teamId, client.reports.first().teamId)
        assertEquals("h1", client.reports.first().recordId)
        assertNull(client.reports.first().durationSec)
        assertEquals(754, client.reports.last().durationSec)
    }

    @Test
    fun `with reporting turned off nothing leaves the device`() = runBlocking {
        // Settings -> Security. The gate lives in the coordinator rather than at each call site, so a
        // user who turns it off is not relying on four separate `if`s staying in agreement.
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        coord.reportSessionsEnabled = { false }
        f.vault.put("h1", RecordType.HOST, """{"label":"db"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId), "h1", RecordType.HOST)

        coord.reportSessionOpened("h1")
        coord.reportSessionRecorded("h1", durationSec = 60)

        assertTrue(client.reports.isEmpty())
        assertNull(coord.lastError.value)
    }

    @Test
    fun `record names for the feed come from each space we can read`() = runBlocking {
        // What makes the feed readable: the server logs ids only, so names are resolved from the
        // member's own copy of every share space, keyed by scope.
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, """{"id":"h1","label":"team-wide-db","address":"a","username":"u"}""".encodeToByteArray())
        f.vault.put("h2", RecordType.HOST, """{"id":"h2","label":"prod-db","address":"a","username":"u"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId), "h1", RecordType.HOST)
        coord.shareRecord(TeamScopeRef(teamId, "prod"), "h2", RecordType.HOST)

        val names = coord.sharedRecordNames(teamId)

        assertEquals(mapOf("h1" to "team-wide-db"), names[""])
        assertEquals(mapOf("h2" to "prod-db"), names["prod"])
        // An unshared record has no payload left, so its name is gone — the feed shows a short id.
        coord.unshareRecord(TeamScopeRef(teamId), "h1")
        assertTrue(coord.sharedRecordNames(teamId)[""].isNullOrEmpty())
    }

    @Test
    fun `a host of our own is never reported anywhere`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        // In the personal vault only: connecting to it is private, and the whole feature is worthless
        // if it leaks that.
        f.vault.put("private", RecordType.HOST, """{"label":"my-nas"}""".encodeToByteArray())

        coord.reportSessionOpened("private")

        assertTrue(client.reports.isEmpty())
    }

    @Test
    fun `an unshared host stops being reported`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        f.vault.put("h1", RecordType.HOST, """{"label":"db"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId), "h1", RecordType.HOST)
        coord.unshareRecord(TeamScopeRef(teamId), "h1")

        coord.reportSessionOpened("h1")

        assertTrue(client.reports.isEmpty())
    }

    @Test
    fun `a scoped host is reported for its own team`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, """{"label":"db"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId, "prod"), "h1", RecordType.HOST)

        coord.reportSessionOpened("h1")

        assertEquals(listOf("h1"), client.reports.map { it.recordId })
        assertEquals(teamId, client.reports.single().teamId)
    }

    @Test
    fun `a failed report stays quiet - it must not surface as a Teams error`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        f.vault.put("h1", RecordType.HOST, """{"label":"db"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId), "h1", RecordType.HOST)
        client.reportFailure = SyncException(SyncException.Kind.NETWORK, "offline")

        coord.reportSessionOpened("h1")

        // The session itself is fine and the user asked for nothing here; an audit ping that didn't
        // land is not something to interrupt them with (and the server it goes to may be older).
        assertNull(coord.lastError.value)
    }

    @Test
    fun `a locked vault reports nothing rather than failing`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client)
        f.vault.put("h1", RecordType.HOST, """{"label":"db"}""".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId), "h1", RecordType.HOST)
        // The account vault locking is what drives coord.lock() in the app, and it is the real
        // precondition here: with no keys readable there is no way to tell a shared host from a
        // private one, so the safe answer is to say nothing.
        f.vault.lock()
        coord.lock()

        coord.reportSessionOpened("h1")

        assertTrue(client.reports.isEmpty())
        assertNull(coord.lastError.value)
    }

    private companion object {
        const val NOW = "2026-07-26T00:00:00Z"
    }
}
