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
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The verified-invite cache holds decrypted teamKey material, so it must not outlive the invite:
 * a team that vanished from the server (revoked invite, deleted team) has to drop its cached entry
 * on the next refresh, not linger until accept/leave/lock.
 */
class TeamsCoordinatorInviteCacheTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-inv"
    private val self = "alice@example.com"
    private val bob = "bob@example.com"

    /** Fake team network: a mutable team list and the inviter's published keys. */
    private class FakeInviteClient(
        private val inviterId: String,
        private val inviterKeys: AccountKeys,
    ) : TeamClient {
        var teams: List<TeamSummary> = emptyList()

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = teams
        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? =
            if (accountId == inviterId) inviterKeys else null
        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = emptyList()
        override suspend fun pullTeam(session: SyncSession, teamId: String, since: Long): RecordPage =
            RecordPage(emptyList(), since)
        override suspend fun pushTeam(session: SyncSession, teamId: String, records: List<RemoteRecord>): RecordPage =
            RecordPage(emptyList(), 0)
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) = error("unused")
        override suspend fun accept(session: SyncSession, teamId: String) = error("unused")
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) = error("unused")
        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
        override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> = error("unused")
        override suspend fun deleteTeam(session: SyncSession, teamId: String) = error("unused")
    }

    @Test
    fun `refresh drops the cached invite of a team gone from the server`() = runBlocking {
        initializeVaultCrypto()
        val vaultFile = Files.createTempFile("skerry-acct", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile)
        val teamDir = Files.createTempDirectory("skerry-teamvaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        val identity = TeamIdentityStore(vault, crypto).ensure()

        val bobSigning = crypto.newSigningKeyPair()
        val envelope = TeamInviteCodec(crypto).seal(
            recipientPublicKey = identity.sharing.publicKey,
            inviter = bobSigning,
            inviterId = bob,
            inviteeId = self,
            teamId = teamId,
            teamKey = crypto.newDataKey(),
            teamName = "Ops",
            epoch = 0,
        )
        val client = FakeInviteClient(bob, AccountKeys(crypto.newSharingKeyPair().publicKey, bobSigning.publicKey))
        client.teams = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = bob, role = TeamRole.VIEWER,
                status = TeamMemberStatus.INVITED, createdAt = 0, memberCount = 2,
                envelope = envelope, keyEpoch = 0, keyEnvelope = null,
            ),
        )
        val coord = TeamsCoordinator(
            session = { SyncSession(self, "access", "refresh") },
            client = { client },
            vault = vault,
            crypto = crypto,
            teamVaults = TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW }),
            teamState = InMemorySyncStateStore(),
            newTeamId = { "unused" },
        )

        assertNotNull(coord.acceptPreview(teamId)) // banner shown: the verified invite is now cached
        client.teams = emptyList() // invite revoked / team deleted server-side
        coord.refresh()

        assertNull(coord.cachedInvite(teamId))
    }

    private companion object {
        const val NOW = "2026-07-20T00:00:00Z"
    }
}
