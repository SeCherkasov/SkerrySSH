package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
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
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The invite ceremony's whole point is that the fingerprint the user reads out loud belongs to the
 * key the team key is sealed to (#316). The sync server owns the key table, so it can answer the
 * lookup with the invitee's real key and the send with one of its own: the fake below does exactly
 * that, and the send must stop rather than seal to a key nobody verified.
 */
class TeamsCoordinatorInviteBindingTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-inv-bind"
    private val self = "alice@example.com"
    private val bob = "bob@example.com"

    /** Fake team network whose published key for [bob] can be swapped between two fetches. */
    private class FakeInviteClient(private val bob: String, var bobKeys: AccountKeys) : TeamClient {
        val invites = mutableListOf<Triple<String, TeamRole, ByteArray>>()
        var keyFetches = 0

        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? {
            keyFetches += 1
            return if (accountId == bob) bobKeys else null
        }

        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) {
            invites += Triple(accountId, role, envelope)
        }

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = emptyList()
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = emptyList()
        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage =
            RecordPage(emptyList(), since)
        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage =
            RecordPage(emptyList(), 0)
        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> = emptyList()
        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> = emptyList()
        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) = error("unused")
        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) = error("unused")
        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) = error("unused")
        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun reportSessionEvent(
            session: SyncSession,
            teamId: String,
            recordId: String,
            kind: TeamSessionKind,
            durationSec: Long?,
        ) = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
    }

    private class Fixture(val vault: FileVault, val teamVaults: TeamVaults)

    private fun newFixture(): Fixture {
        val vaultFile = Files.createTempFile("skerry-acct", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile) // FileVault creates it
        val teamDir = Files.createTempDirectory("skerry-teamvaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        return Fixture(vault, TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW }))
    }

    private fun coordinator(f: Fixture, client: TeamClient) = TeamsCoordinator(
        live = { TeamLink(SyncSession(self, "access", "refresh"), client, "test-link") },
        vault = f.vault,
        crypto = crypto,
        teamVaults = f.teamVaults,
        teamState = InMemorySyncStateStore(),
        newId = { "unused" },
    )

    private fun keysOf(sharing: SharingKeyPair) = AccountKeys(sharing.publicKey, crypto.newSigningKeyPair().publicKey)

    @Test
    fun `a key that changed between the lookup and the send stops the invite`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)

        val real = crypto.newSharingKeyPair()
        val client = FakeInviteClient(bob, keysOf(real))
        val coord = coordinator(f, client)

        val preview = assertNotNull(coord.previewInvite(bob), "lookup must return a fingerprint")
        // The server swaps its answer between the two steps: the fingerprint the user just read out
        // loud is no longer the key the second fetch returns.
        client.bobKeys = keysOf(crypto.newSharingKeyPair())
        coord.invite(teamId, preview, TeamRole.VIEWER)

        assertEquals(emptyList(), client.invites, "the team key must not be sealed to an unverified key")
        assertEquals(TeamsFailure.RecipientKeyChanged, coord.lastError.value)
    }

    /**
     * The fingerprint hashes the sharing key AND the signing key, so a server that leaves the key the
     * envelope is sealed to alone and swaps only the identity the invitee signs with must be refused
     * too — otherwise the invitee's later envelopes verify against a key nobody read out loud.
     */
    @Test
    fun `a signing key swapped under the same sharing key is a mismatch too`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)

        val real = crypto.newSharingKeyPair()
        val client = FakeInviteClient(bob, keysOf(real))
        val coord = coordinator(f, client)

        val preview = assertNotNull(coord.previewInvite(bob))
        client.bobKeys = AccountKeys(real.publicKey, crypto.newSigningKeyPair().publicKey)
        coord.invite(teamId, preview, TeamRole.VIEWER)

        assertEquals(emptyList(), client.invites)
        assertEquals(TeamsFailure.RecipientKeyChanged, coord.lastError.value)
    }

    @Test
    fun `an unchanged key seals the invite to the key whose fingerprint was verified`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)

        val real = crypto.newSharingKeyPair()
        val client = FakeInviteClient(bob, keysOf(real))
        val coord = coordinator(f, client)

        val preview = assertNotNull(coord.previewInvite(bob))
        coord.invite(teamId, preview, TeamRole.VIEWER)

        assertNull(coord.lastError.value)
        assertEquals(1, client.invites.size)
        // The send fetches the key again rather than trusting the preview: the check is on fresh
        // material, so a key that moved between the two steps cannot slip through unnoticed.
        assertEquals(2, client.keyFetches)
        val (accountId, role, envelope) = client.invites.single()
        assertEquals(bob, accountId)
        assertEquals(TeamRole.VIEWER, role)
        // Opening under the very key pair whose fingerprint the preview showed IS the property: the
        // envelope was sealed to the verified key, not to whatever the second lookup returned.
        val opened = assertNotNull(TeamInviteCodec(crypto).open(real, envelope), "sealed to the verified key")
        assertEquals(teamId, opened.teamId)
        assertEquals(bob, opened.inviteeAccountId)
        assertEquals(self, opened.inviterAccountId)
        assertEquals("Ops", opened.teamName)
    }

    private companion object {
        const val NOW = "2026-08-23T00:00:00Z"
    }
}
