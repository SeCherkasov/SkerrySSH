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
import kotlin.test.assertFailsWith
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
        /** id -> (record, seq, scopeId): one counter per team, records filed under one space each. */
        private val records = linkedMapOf<String, Triple<RemoteRecord, Long, String>>()
        private var seq = 0L

        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage {
            val page = records.values.filter { it.second > since && it.third == ref.scopeId }.sortedBy { it.second }
            return RecordPage(page.map { it.first }, page.lastOrNull()?.second ?: since)
        }

        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage {
            val result = records.mapNotNull { rec ->
                val existing = this.records[rec.id]
                // A record stays in the space it was first pushed into (see TeamRecordRepository).
                if (existing != null && existing.third != ref.scopeId) return@mapNotNull null
                val current = existing?.first
                val wins = current == null || rec.version > current.version ||
                    (rec.version == current.version && rec.deviceId > current.deviceId)
                if (wins) {
                    seq += 1
                    this.records[rec.id] = Triple(rec, seq, ref.scopeId)
                    rec
                } else {
                    current
                }
            }
            return RecordPage(result, seq)
        }

        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = error("unused")
        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? = error("unused")
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = error("unused")
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun reportSessionEvent(
            session: SyncSession,
            teamId: String,
            recordId: String,
            kind: TeamSessionKind,
            durationSec: Long?,
        ) = error("unused")
        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> = error("unused")
        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> = error("unused")
        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) = error("unused")
        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) = error("unused")
        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
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

    private fun engineFor(
        vault: app.skerry.shared.vault.Vault,
        server: TeamClient,
        ref: TeamScopeRef = TeamScopeRef(teamId),
    ) = SyncEngine(
        TeamScopedSyncClient(server, ref),
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
        val aliceVault = vaultsFor("alice").open(TeamScopeRef(teamId), teamKey)!!
        aliceVault.put("h1", RecordType.HOST, """{"name":"prod"}""".encodeToByteArray())
        val aliceEngine = engineFor(aliceVault, server)
        aliceEngine.sync(session)

        // B: got teamKey from the invitation, opens its team vault and syncs
        val bobVault = vaultsFor("bob").open(TeamScopeRef(teamId), teamKey)!!
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
        val vault = vaults.open(TeamScopeRef(teamId), rightKey)!!
        vault.put("h1", RecordType.HOST, "x".encodeToByteArray())
        vault.lock()
        vaults.lockAll()

        assertNull(vaults.open(TeamScopeRef(teamId), crypto.newDataKey()))
        assertEquals(true, vaults.open(TeamScopeRef(teamId), rightKey)?.isUnlocked)
    }

    @Test
    fun `openOrClassify distinguishes a superseded key from a corrupt file and never deletes`() = runBlocking {
        initializeVaultCrypto()
        val dir = Files.createTempDirectory("skerry-teams-classify").toString().toPath()
        val vaults = TeamVaults(
            dir = dir,
            crypto = crypto,
            deviceId = "dev-eve",
            fileSystem = FileSystem.SYSTEM,
            now = { "2026-07-04T00:00:00Z" },
        )
        val key = crypto.newDataKey()
        // A non-empty vault under the right key opens.
        val opened = vaults.openOrClassify(TeamScopeRef(teamId), key)
        assertTrue(opened is TeamVaults.OpenResult.Opened)
        opened.vault.put("h1", RecordType.HOST, "x".encodeToByteArray())
        opened.vault.lock()
        vaults.lockAll()

        // A wrong key on a non-empty file is a superseded key (safe to reset), not corruption.
        assertEquals(TeamVaults.OpenResult.StaleKey, vaults.openOrClassify(TeamScopeRef(teamId), crypto.newDataKey()))
        vaults.lockAll()

        // A structurally unreadable file must be reported as Unreadable — and left on disk (deleting
        // it would silently drop any local records that were never pushed).
        val file = dir / "$teamId.vault"
        FileSystem.SYSTEM.write(file) { writeUtf8("not a vault at all") }
        assertEquals(TeamVaults.OpenResult.Unreadable, vaults.openOrClassify(TeamScopeRef(teamId), key))
        assertTrue(FileSystem.SYSTEM.exists(file))
    }

    @Test
    fun `a scope record reaches another holder of the scope key and no one else`() = runBlocking {
        initializeVaultCrypto()
        val server = FakeTeamServer()
        val teamKey = crypto.newDataKey()
        val scopeKey = crypto.newDataKey()
        val prod = TeamScopeRef(teamId, "prod")
        val teamWide = TeamScopeRef(teamId)
        val session = SyncSession("acct", "access", "refresh")
        val public = """{"name":"wiki"}""".encodeToByteArray()
        val secret = """{"name":"db-prod"}""".encodeToByteArray()

        // Alice holds both keys: one host shared with the whole team, one only into the scope.
        val aliceVaults = vaultsFor("alice-scoped")
        val aliceTeam = aliceVaults.open(teamWide, teamKey)!!
        val aliceProd = aliceVaults.open(prod, scopeKey)!!
        aliceTeam.put("wiki", RecordType.HOST, public)
        aliceProd.put("db", RecordType.HOST, secret)
        engineFor(aliceTeam, server).sync(session)
        engineFor(aliceProd, server, prod).sync(session)

        // Bob was granted the scope: he gets both, decrypted.
        val bobVaults = vaultsFor("bob-scoped")
        val bobTeam = bobVaults.open(teamWide, teamKey)!!
        val bobProd = bobVaults.open(prod, scopeKey)!!
        engineFor(bobTeam, server).sync(session)
        val bobProdEngine = engineFor(bobProd, server, prod)
        bobProdEngine.sync(session)
        assertContentEquals(public, bobTeam.openPayload("wiki"))
        assertContentEquals(secret, bobProd.openPayload("db"))

        // Carol is in the team but not in the scope: the scoped record never enters her team space,
        // and she has no key that would open it if it did.
        val carolVaults = vaultsFor("carol-scoped")
        val carolTeam = carolVaults.open(teamWide, teamKey)!!
        engineFor(carolTeam, server).sync(session)
        assertEquals(listOf("wiki"), carolTeam.records().map { it.id })
        assertNull(carolTeam.openPayload("db"))
        assertEquals(TeamVaults.OpenResult.StaleKey, bobVaults.also { it.lockAll() }.openOrClassify(prod, teamKey))

        // And the round trip closes: Bob's removal inside the scope reaches Alice, not the team space.
        val bobProd2 = bobVaults.open(prod, scopeKey)!!
        bobProd2.remove("db")
        engineFor(bobProd2, server, prod).sync(session)
        engineFor(aliceProd, server, prod).sync(session)
        assertNull(aliceProd.openPayload("db"))
        assertTrue(aliceProd.records().first { it.id == "db" }.deleted)
        assertContentEquals(public, aliceTeam.openPayload("wiki"))
    }

    @Test
    fun `a scope vault is a separate file under its own key`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("erin")
        val teamKey = crypto.newDataKey()
        val scopeKey = crypto.newDataKey()
        val teamWide = TeamScopeRef(teamId)
        val prod = TeamScopeRef(teamId, "prod")

        vaults.open(teamWide, teamKey)!!.put("h1", RecordType.HOST, """{"name":"shared"}""".encodeToByteArray())
        vaults.open(prod, scopeKey)!!.put("h2", RecordType.HOST, """{"name":"prod"}""".encodeToByteArray())

        // Separate stores: a member without the scope key sees nothing of it in the team vault.
        assertNull(vaults.open(teamWide, teamKey)!!.openPayload("h2"))
        assertContentEquals("""{"name":"prod"}""".encodeToByteArray(), vaults.open(prod, scopeKey)!!.openPayload("h2"))

        // And the team key does not open the scope's file.
        vaults.lockAll()
        assertEquals(TeamVaults.OpenResult.StaleKey, vaults.openOrClassify(prod, teamKey))
    }

    @Test
    fun `resetting one scope leaves the team vault and other scopes intact`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("frank")
        val teamKey = crypto.newDataKey()
        val prodKey = crypto.newDataKey()
        val stagingKey = crypto.newDataKey()
        vaults.open(TeamScopeRef(teamId), teamKey)!!.put("h1", RecordType.HOST, "a".encodeToByteArray())
        vaults.open(TeamScopeRef(teamId, "prod"), prodKey)!!.put("h2", RecordType.HOST, "b".encodeToByteArray())
        vaults.open(TeamScopeRef(teamId, "staging"), stagingKey)!!.put("h3", RecordType.HOST, "c".encodeToByteArray())

        vaults.reset(TeamScopeRef(teamId, "prod"))

        assertEquals(0, vaults.open(TeamScopeRef(teamId, "prod"), prodKey)!!.records().size)
        assertEquals(1, vaults.open(TeamScopeRef(teamId), teamKey)!!.records().size)
        assertEquals(1, vaults.open(TeamScopeRef(teamId, "staging"), stagingKey)!!.records().size)
    }

    @Test
    fun `resetting the team drops its scope vaults too`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("grace")
        val teamKey = crypto.newDataKey()
        val prodKey = crypto.newDataKey()
        vaults.open(TeamScopeRef(teamId), teamKey)!!.put("h1", RecordType.HOST, "a".encodeToByteArray())
        vaults.open(TeamScopeRef(teamId, "prod"), prodKey)!!.put("h2", RecordType.HOST, "b".encodeToByteArray())

        // Leaving/being removed from a team must not leave a scope's records behind on disk.
        vaults.resetTeam(teamId)

        assertEquals(0, vaults.open(TeamScopeRef(teamId), teamKey)!!.records().size)
        assertEquals(0, vaults.open(TeamScopeRef(teamId, "prod"), prodKey)!!.records().size)
    }

    @Test
    fun `an unsafe scope id cannot escape the vault directory`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("heidi")

        assertFailsWith<IllegalArgumentException> {
            vaults.open(TeamScopeRef(teamId, "../../etc/passwd"), crypto.newDataKey())
        }
    }

    @Test
    fun `reset removes the local team vault file`() = runBlocking {
        initializeVaultCrypto()
        val vaults = vaultsFor("dave")
        val key = crypto.newDataKey()
        vaults.open(TeamScopeRef(teamId), key)!!.put("h1", RecordType.HOST, "x".encodeToByteArray())

        vaults.reset(TeamScopeRef(teamId))

        // file removed -> opening recreates an empty vault
        val fresh = vaults.open(TeamScopeRef(teamId), key)!!
        assertEquals(0, fresh.records().size)
    }
}
