package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamSessionKind
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeGrantEntry
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopeSummary
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Granular sharing in [TeamsCoordinator]: a scope is a share space with its own key, so what is
 * shared into it must be unreadable — not merely unlisted — for members without a grant. Only the
 * network is faked; vaults and crypto are real.
 */
class TeamsCoordinatorScopeTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-scoped"
    private val self = "alice@example.com"
    private val bob = "bob@example.com"

    /** Fake team network with scope support: stores grants and per-space records like the server. */
    private class FakeTeamClient(
        private val self: String,
        private val teamId: String,
        private val bobKeys: AccountKeys? = null,
        private val bobId: String = "bob@example.com",
    ) : TeamClient {
        val scopes = linkedMapOf<String, MutableMap<String, ByteArray>>() // scopeId -> account -> envelope
        val epochs = mutableMapOf<String, Long>()
        val rekeyCalls = mutableListOf<Pair<String, Long>>()
        val revoked = mutableListOf<Pair<String, String>>()
        val deletedScopes = mutableListOf<String>()
        val teamRekeyCalls = mutableListOf<Long>()
        val removed = mutableListOf<String>()
        /** Set to make listScopes fail — a server without scope routes (404) or a transient error. */
        var listScopesFailure: SyncException? = null

        private val store = linkedMapOf<String, Triple<RemoteRecord, Long, String>>() // id -> (rec, seq, scope)
        private var seq = 0L

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = self, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 2,
                envelope = null, keyEpoch = 0, keyEnvelope = null,
            ),
        )

        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> =
            scopes.also { listScopesFailure?.let { failure -> throw failure } }.map { (scopeId, grants) ->
                TeamScopeSummary(scopeId, epochs[scopeId] ?: 0, grants.size, grants[session.accountId])
            }

        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) {
            scopes[scopeId] = mutableMapOf(session.accountId to envelope)
            epochs[scopeId] = 0
        }

        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) {
            deletedScopes += scopeId
            scopes.remove(scopeId)
            store.entries.removeAll { it.value.third == scopeId }
        }

        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> =
            scopes[scopeId]?.keys?.map { TeamScopeGrantEntry(it, 0) } ?: emptyList()

        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) {
            scopes[scopeId]?.put(accountId, envelope)
        }

        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) {
            revoked += scopeId to accountId
            scopes[scopeId]?.remove(accountId)
        }

        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
            rekeyCalls += scopeId to newEpoch
            epochs[scopeId] = newEpoch
            val grants = scopes[scopeId] ?: return
            envelopes.forEach { (account, env) -> if (grants.containsKey(account)) grants[account] = env }
        }

        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> =
            listOf(
                TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
                TeamMember(bobId, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            )

        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? =
            if (accountId == bobId) bobKeys else null

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

        /** Ciphertext of a record as the server holds it — used to prove a scope's blob stays sealed. */
        fun blobOf(id: String): ByteArray? = store[id]?.first?.blob

        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
            teamRekeyCalls += newEpoch
        }
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun reportSessionEvent(
            session: SyncSession,
            teamId: String,
            recordId: String,
            kind: TeamSessionKind,
            durationSec: Long?,
        ) = error("unused")
        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) {
            removed += accountId
            scopes.values.forEach { it.remove(accountId) } // the server drops grants with the membership
        }
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
    }

    private class Fixture(val vault: FileVault, val teamVaults: TeamVaults, val teamDir: okio.Path)

    private fun newFixture(): Fixture {
        val vaultFile = Files.createTempFile("skerry-acct", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile)
        val teamDir = Files.createTempDirectory("skerry-teamvaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        return Fixture(vault, TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW }), teamDir)
    }

    private fun coordinator(f: Fixture, client: TeamClient, ids: Iterator<String>) = TeamsCoordinator(
        session = { SyncSession(self, "access", "refresh") },
        client = { client },
        vault = f.vault,
        crypto = crypto,
        teamVaults = f.teamVaults,
        teamState = InMemorySyncStateStore(),
        newId = { ids.next() },
    )

    private fun seedTeam(f: Fixture) =
        TeamKeyStore(f.vault).also { it.put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0) }

    @Test
    fun `creating a scope stores its own key and lists it on the team`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())

        coord.createScope(teamId, "Production")

        val scope = ks.scope(teamId, "prod")
        assertEquals("Production", scope?.name)
        assertNotEquals(ks.get(teamId)!!.teamKey, scope!!.key) // a key of its own, not the team's
        assertTrue(client.scopes["prod"]!!.containsKey(self)) // the creator's own recovery envelope
        assertNull(coord.lastError.value)

        coord.refresh()
        val ui = coord.teams.value.single().scopes.single()
        assertEquals("prod", ui.id)
        assertEquals("Production", ui.name)
        assertTrue(ui.hasKey)
    }

    @Test
    fun `a record shared into a scope is sealed under the scope key, not the team key`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, """{"name":"db-prod"}""".encodeToByteArray())

        assertTrue(coord.shareRecord(TeamScopeRef(teamId, "prod"), "h1", RecordType.HOST))

        // It lands in the scope's vault, and the team-wide vault knows nothing about it.
        val scopeKey = ks.scope(teamId, "prod")!!.dataKey()!!
        assertContentEquals(
            """{"name":"db-prod"}""".encodeToByteArray(),
            f.teamVaults.open(TeamScopeRef(teamId, "prod"), scopeKey)!!.openPayload("h1"),
        )
        assertNull(f.teamVaults.open(TeamScopeRef(teamId), ks.get(teamId)!!.dataKey()!!)!!.openPayload("h1"))
        // What reached the server is ciphertext the team key does not open — the guarantee that
        // survives a server that ignores its own ACL.
        val blob = client.blobOf("h1")
        assertTrue(blob != null && !blob.contentEquals("""{"name":"db-prod"}""".encodeToByteArray()))
    }

    @Test
    fun `revoking a grant rotates only that scope key and re-encrypts its records`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val bobIdentity = crypto.newSharingKeyPair()
        val client = FakeTeamClient(self, teamId, AccountKeys(bobIdentity.publicKey, crypto.newSigningKeyPair().publicKey))
        val coord = coordinator(f, client, listOf("prod").iterator())
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, "secret".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId, "prod"), "h1", RecordType.HOST)
        coord.grantScope(teamId, "prod", bob)
        val keyBeforeRevoke = ks.scope(teamId, "prod")!!.key
        val teamKeyBefore = ks.get(teamId)!!.teamKey

        coord.revokeScope(teamId, "prod", bob)

        assertEquals(listOf("prod" to bob), client.revoked)
        assertEquals(listOf("prod" to 1L), client.rekeyCalls)
        assertEquals(1, ks.scope(teamId, "prod")!!.epoch)
        assertNotEquals(keyBeforeRevoke, ks.scope(teamId, "prod")!!.key)
        // The team key is untouched: a revoke inside one scope must not churn everyone else's key.
        assertEquals(teamKeyBefore, ks.get(teamId)!!.teamKey)
        // Records survive the rotation, re-encrypted under the new key.
        val newKey = ks.scope(teamId, "prod")!!.dataKey()!!
        assertContentEquals("secret".encodeToByteArray(), f.teamVaults.open(TeamScopeRef(teamId, "prod"), newKey)!!.openPayload("h1"))
        assertNull(coord.lastError.value)
    }

    @Test
    fun `a scope grant is adopted only when properly signed and bound to that scope`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        // Our own identity is what a grant is sealed to; the granter (bob) signs it, so bob's
        // published signing key is what verification must match.
        val identity = TeamIdentityStore(f.vault, crypto).ensure()
        val granter = crypto.newSigningKeyPair()
        val impostor = crypto.newSigningKeyPair()
        val client = FakeTeamClient(self, teamId, AccountKeys(crypto.newSharingKeyPair().publicKey, granter.publicKey))
        val coord = coordinator(f, client, listOf("prod").iterator())
        val codec = TeamInviteCodec(crypto)
        val scopeKey = crypto.newDataKey()

        fun envelope(signer: app.skerry.shared.vault.SigningKeyPair, scopeId: String) = codec.seal(
            recipientPublicKey = identity.sharing.publicKey,
            inviter = signer, inviterId = bob, inviteeId = self, teamId = teamId,
            teamKey = scopeKey, teamName = "Production", epoch = 0, scopeId = scopeId,
        )

        // Signed by someone whose published key isn't the one we verify against → ignored.
        client.scopes["prod"] = mutableMapOf(self to envelope(impostor, "prod"))
        coord.refresh()
        assertNull(ks.scope(teamId, "prod"))

        // Correctly signed but bound to a different scope (a server filing it under the wrong slot).
        client.scopes["prod"] = mutableMapOf(self to envelope(granter, "staging"))
        coord.refresh()
        assertNull(ks.scope(teamId, "prod"))

        // Properly signed and bound → adopted, and the adopted key really is the granted one.
        val probe = crypto.seal(scopeKey, "probe".encodeToByteArray(), VaultCrypto.EMPTY_AAD)
        client.scopes["prod"] = mutableMapOf(self to envelope(granter, "prod"))
        coord.refresh()
        val adopted = assertNotNull(ks.scope(teamId, "prod")?.dataKey())
        assertContentEquals("probe".encodeToByteArray(), crypto.open(adopted, probe, VaultCrypto.EMPTY_AAD))
    }

    @Test
    fun `losing a grant drops the local scope key and its vault file`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, "secret".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId, "prod"), "h1", RecordType.HOST)
        val scopeFile = f.teamDir / TeamScopeRef(teamId, "prod").fileName
        assertTrue(FileSystem.SYSTEM.exists(scopeFile))

        client.scopes.clear() // access revoked elsewhere
        coord.refresh()

        assertNull(ks.scope(teamId, "prod"))
        assertTrue(!FileSystem.SYSTEM.exists(scopeFile))
        assertTrue(coord.teams.value.single().scopes.isEmpty())
    }

    @Test
    fun `deleting a scope removes it locally with its records`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, "secret".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId, "prod"), "h1", RecordType.HOST)

        coord.deleteScope(teamId, "prod")

        assertEquals(listOf("prod"), client.deletedScopes)
        assertNull(ks.scope(teamId, "prod"))
        assertTrue(!FileSystem.SYSTEM.exists(f.teamDir / TeamScopeRef(teamId, "prod").fileName))
    }

    @Test
    fun `locking the coordinator locks the scope vault itself`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())
        coord.createScope(teamId, "Production")
        f.vault.put("h1", RecordType.HOST, "secret".encodeToByteArray())
        coord.shareRecord(TeamScopeRef(teamId, "prod"), "h1", RecordType.HOST)
        // Hold the instance: asserting spaceVault() == null afterwards would pass on the locked
        // account vault alone and prove nothing about the scope's own file.
        val scopeVault = assertNotNull(coord.spaceVault(TeamScopeRef(teamId, "prod")))
        assertTrue(scopeVault.isUnlocked)

        coord.lock()

        assertFalse(scopeVault.isUnlocked)
    }

    @Test
    fun `removing a member rotates the key of every scope they held`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val client = FakeTeamClient(self, teamId, AccountKeys(crypto.newSharingKeyPair().publicKey, crypto.newSigningKeyPair().publicKey))
        val coord = coordinator(f, client, listOf("prod", "staging", "sandbox").iterator())
        coord.createScope(teamId, "Production")
        coord.createScope(teamId, "Staging")
        coord.createScope(teamId, "Sandbox")
        coord.grantScope(teamId, "prod", bob)
        coord.grantScope(teamId, "staging", bob)
        val untouchedKey = ks.scope(teamId, "sandbox")!!.key

        coord.removeMember(teamId, bob)

        assertEquals(listOf(bob), client.removed)
        // Every scope the removed member held is rotated — leaving one out leaves them a live key.
        assertEquals(listOf("prod" to 1L, "staging" to 1L), client.rekeyCalls.sortedBy { it.first })
        assertEquals(1, ks.scope(teamId, "prod")!!.epoch)
        assertEquals(1, ks.scope(teamId, "staging")!!.epoch)
        // A scope they never had is left alone: rotation churns every grantee, so it isn't free.
        assertEquals(0, ks.scope(teamId, "sandbox")!!.epoch)
        assertEquals(untouchedKey, ks.scope(teamId, "sandbox")!!.key)
        // The team key rotates too, as it did before scopes existed.
        assertEquals(listOf(1L), client.teamRekeyCalls)
    }

    @Test
    fun `a server without scope support is named as such instead of failing as a missing account`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val ks = seedTeam(f)
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())
        // A self-hosted deployment older than granular sharing has no /teams/{id}/scopes route.
        client.listScopesFailure = SyncException(SyncException.Kind.NOT_FOUND, "no route")

        coord.refresh()

        // The team list survives — scopes are optional, losing the whole screen over them would be worse.
        assertEquals(1, coord.teams.value.size)
        assertTrue(coord.teams.value.single().scopes.isEmpty())

        coord.createScope(teamId, "Production")

        assertEquals(TeamsFailure.ScopesUnsupported, coord.lastError.value)
        assertNull(ks.scope(teamId, "prod")) // nothing half-created locally
    }

    @Test
    fun `a transient failure keeps the known scopes on screen instead of blanking them`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val client = FakeTeamClient(self, teamId)
        val coord = coordinator(f, client, listOf("prod").iterator())
        seedTeam(f)
        coord.createScope(teamId, "Production")
        coord.refresh()
        assertEquals(listOf("prod"), coord.teams.value.single().scopes.map { it.id })

        // A network blip says nothing about whether scopes exist — unlike a 404, it must not read as
        // "this team has none", or the scope selector would blink out under a flaky connection.
        client.listScopesFailure = SyncException(SyncException.Kind.NETWORK, "offline")
        coord.refresh()

        assertEquals(listOf("prod"), coord.teams.value.single().scopes.map { it.id })
        assertEquals(TeamsFailure.Network, coord.lastError.value)
    }

    private companion object {
        const val NOW = "2026-07-26T00:00:00Z"
    }
}
