package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamIdentityStore
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
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A space vault dropped because its key moved has to take its delta cursors with it. The engine moves
 * the cursor past records it could not open, so the records sealed under the new key that a stale
 * vault already skipped sit below the tip: a re-created, empty vault resuming from it never receives
 * them, and the space stays empty under a healthy status.
 */
class TeamsCoordinatorSpaceResetCursorTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-reset"
    private val self = "alice@example.com"
    private val bob = "bob@example.com"
    private val link = "17 https://work.test alice@example.com"
    private val otherLink = "17 https://home.test alice@example.com"

    /** Answers the team listings it is handed and records every pull's starting cursor. */
    internal class RecordingClient(
        private val teams: () -> List<TeamSummary>,
        private val scopes: () -> List<TeamScopeSummary>,
        private val bobKeys: AccountKeys?,
    ) : TeamClient {
        val pulls = mutableListOf<Pair<TeamScopeRef, Long>>()

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = teams()
        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> = scopes()
        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? =
            if (accountId == "bob@example.com") bobKeys else null
        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage {
            pulls += ref to since
            return RecordPage(emptyList(), since)
        }
        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage =
            RecordPage(records, 0)
        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = emptyList()
        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> = emptyList()
        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) = error("unused")
        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) = error("unused")
        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
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

    private class Fixture(val vault: FileVault, val teamVaults: TeamVaults, val keys: TeamKeyStore)

    private suspend fun newFixture(): Fixture {
        initializeVaultCrypto()
        val vaultFile = Files.createTempFile("skerry-reset", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile)
        val teamDir = Files.createTempDirectory("skerry-reset-vaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        val teamVaults = TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        return Fixture(vault, teamVaults, TeamKeyStore(vault))
    }

    private fun coordinator(f: Fixture, client: TeamClient, state: InMemorySyncStateStore) = TeamsCoordinator(
        live = { TeamLink(SyncSession(self, "access", "refresh"), client, link) },
        vault = f.vault,
        crypto = crypto,
        teamVaults = f.teamVaults,
        teamState = state,
        newId = { error("unused") },
    )

    private fun activeTeam(keyEnvelope: ByteArray? = null, keyEpoch: Long = 0) = TeamSummary(
        id = teamId, ownerAccountId = bob, role = TeamRole.EDITOR,
        status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 2,
        envelope = null, keyEpoch = keyEpoch, keyEnvelope = keyEnvelope,
    )

    /** A space file holding a record sealed under [key] — what a device has before the key moves. */
    private fun seedSpaceFile(f: Fixture, ref: TeamScopeRef, key: DataKey) {
        f.teamVaults.open(ref, key)!!.put("h1", RecordType.HOST, "old".encodeToByteArray())
        f.teamVaults.lockAll()
    }

    private fun firstPull(client: RecordingClient, ref: TeamScopeRef): Long? = client.pulls.firstOrNull { it.first == ref }?.second

    @Test
    fun `a space file found under a superseded key is re-pulled from the start`() = runBlocking<Unit> {
        val f = newFixture()
        val space = TeamScopeRef(teamId)
        seedSpaceFile(f, space, crypto.newDataKey())
        f.keys.put(teamId, "Ops", TeamRole.EDITOR, crypto.newDataKey(), epoch = 1) // adopted on another device
        val state = InMemorySyncStateStore().apply {
            setCursor(teamCursorKey(link, space), 9)
            setCursor(teamCursorKey(otherLink, space), 4)
        }
        val client = RecordingClient({ listOf(activeTeam()) }, { emptyList() }, bobKeys = null)

        coordinator(f, client, state).syncSpace(space)

        assertEquals(0L, firstPull(client, space), "the rebuilt vault resumed from the stale vault's tip")
        assertEquals(0L, state.cursor(teamCursorKey(otherLink, space)), "the tip on another link survived the reset")
    }

    @Test
    fun `adopting a rotated team key re-pulls the team space from the start`() = runBlocking<Unit> {
        val f = newFixture()
        val space = TeamScopeRef(teamId)
        val oldKey = crypto.newDataKey()
        f.keys.put(teamId, "Ops", TeamRole.EDITOR, oldKey, epoch = 0)
        seedSpaceFile(f, space, oldKey)
        val identity = TeamIdentityStore(f.vault, crypto).ensure()
        val rotator = crypto.newSigningKeyPair()
        val envelope = TeamInviteCodec(crypto).seal(
            recipientPublicKey = identity.sharing.publicKey, inviter = rotator, inviterId = bob, inviteeId = self,
            teamId = teamId, teamKey = crypto.newDataKey(), teamName = "Ops", epoch = 1,
        )
        val state = InMemorySyncStateStore().apply { setCursor(teamCursorKey(link, space), 9) }
        val client = RecordingClient(
            { listOf(activeTeam(keyEnvelope = envelope, keyEpoch = 1)) },
            { emptyList() },
            AccountKeys(crypto.newSharingKeyPair().publicKey, rotator.publicKey),
        )

        coordinator(f, client, state).refresh()

        assertEquals(1, f.keys.get(teamId)!!.epoch, "the rotated key was not adopted")
        assertEquals(0L, firstPull(client, space), "the re-keyed space resumed from the old key's tip")
    }

    @Test
    fun `adopting a re-keyed scope re-pulls that scope from the start`() = runBlocking<Unit> {
        val f = newFixture()
        f.keys.put(teamId, "Ops", TeamRole.EDITOR, crypto.newDataKey(), epoch = 0)
        val scope = TeamScopeRef(teamId, "prod")
        val oldKey = crypto.newDataKey()
        f.keys.putScope(teamId, "prod", "Production", oldKey, epoch = 0)
        seedSpaceFile(f, scope, oldKey)
        val identity = TeamIdentityStore(f.vault, crypto).ensure()
        val granter = crypto.newSigningKeyPair()
        val envelope = TeamInviteCodec(crypto).seal(
            recipientPublicKey = identity.sharing.publicKey, inviter = granter, inviterId = bob, inviteeId = self,
            teamId = teamId, teamKey = crypto.newDataKey(), teamName = "Production", epoch = 1, scopeId = "prod",
        )
        val state = InMemorySyncStateStore().apply { setCursor(teamCursorKey(link, scope), 9) }
        val client = RecordingClient(
            { listOf(activeTeam()) },
            { listOf(TeamScopeSummary("prod", 1, 2, envelope)) },
            AccountKeys(crypto.newSharingKeyPair().publicKey, granter.publicKey),
        )

        coordinator(f, client, state).refresh()

        assertEquals(1, f.keys.scope(teamId, "prod")!!.epoch, "the re-keyed scope was not adopted")
        assertEquals(0L, firstPull(client, scope), "the re-keyed scope resumed from the old key's tip")
    }

    @Test
    fun `a scope that is gone from the server takes its cursors with it`() = runBlocking<Unit> {
        val f = newFixture()
        f.keys.put(teamId, "Ops", TeamRole.EDITOR, crypto.newDataKey(), epoch = 0)
        f.keys.putScope(teamId, "prod", "Production", crypto.newDataKey(), epoch = 0)
        val scope = TeamScopeRef(teamId, "prod")
        val state = InMemorySyncStateStore().apply {
            setCursor(teamCursorKey(link, scope), 9)
            setCursor(teamCursorKey(otherLink, scope), 4)
            setCursor(teamCursorKey(link, TeamScopeRef(teamId)), 7)
        }
        val client = RecordingClient({ listOf(activeTeam()) }, { emptyList() }, bobKeys = null)

        coordinator(f, client, state).refresh()

        assertEquals(0L, state.cursor(teamCursorKey(link, scope)), "a re-grant would resume from this tip")
        assertEquals(0L, state.cursor(teamCursorKey(otherLink, scope)), "the tip on another link survived")
        assertEquals(7L, state.cursor(teamCursorKey(link, TeamScopeRef(teamId))), "the team's own cursor was cleared")
    }

    private companion object {
        const val NOW = "2026-09-26T00:00:00Z"
    }
}
