package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamPeerStore
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeGrantEntry
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopeSummary
import app.skerry.shared.team.TeamSessionKind
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.accountKeyFingerprint
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.SigningKeyPair
import app.skerry.ui.sync.TeamLink
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files

/**
 * The sync server the peer-pin tests run against, and the account it runs against it: a mutable key
 * table the fake is free to swap between two fetches, a real file vault, and a coordinator wired to
 * both.
 *
 * A base class rather than a second copy per test file: what these tests are about is a server that
 * answers one key for the check and another for the seal, and a fake that drifts between two files
 * stops being that server for one of them.
 */
abstract class TeamsPeerPinFixture {

    protected val crypto = IonspinVaultCrypto()
    protected val teamId = "team-pin"
    protected val self = "alice@example.com"
    protected val bob = "bob@example.com"
    protected val carol = "carol@example.com"
    protected val dave = "dave@example.com"

    /** An account's published keys, as the server holds them — swappable between two fetches. */
    protected class Published(var keys: AccountKeys)

    protected fun keysOf(sharing: SharingKeyPair, signing: SigningKeyPair) =
        AccountKeys(sharing.publicKey, signing.publicKey)

    protected fun fingerprintOf(keys: AccountKeys) = accountKeyFingerprint(keys.sharing, keys.signing)

    /**
     * Fake team network: a mutable key table, a mutable member list, and every envelope the client
     * hands over kept for inspection.
     */
    protected class FakePeerClient(private val self: String, private val teamId: String) : TeamClient {
        val published = mutableMapOf<String, Published>()
        var teams: List<TeamSummary> = emptyList()
        var memberList: List<TeamMember> = emptyList()
        val accepted = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val grants = mutableListOf<Pair<String, ByteArray>>()
        val invites = mutableListOf<String>()
        val teamRekeys = mutableListOf<Map<String, ByteArray>>()
        val scopeEnvelopes = linkedMapOf<String, MutableMap<String, ByteArray>>()
        val scopeEpochs = mutableMapOf<String, Long>()
        var teamEpoch = 0L
        /** How many times each account's key was fetched — the double fetch is the hole (#319 item 1). */
        val fetches = mutableMapOf<String, Int>()

        /** Run inside the lookup, for whatever the round trip is supposed to give the world time to do. */
        var duringFetch: (() -> Unit)? = null

        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? {
            fetches[accountId] = (fetches[accountId] ?: 0) + 1
            duringFetch?.invoke()
            return published[accountId]?.keys
        }

        /** Set to fail the team listing the way a dropped network does — the reread after a removal. */
        var listTeamsFails = false

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> {
            if (listTeamsFails) throw SyncException(SyncException.Kind.NETWORK, "list teams failed")
            return teams
        }
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = memberList
        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun accept(session: SyncSession, teamId: String) { accepted += teamId }

        override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) {
            removed += accountId
            memberList = memberList.filter { it.accountId != accountId }
            scopeEnvelopes.values.forEach { it.remove(accountId) }
        }

        override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
            if (teamRekeyFails) throw SyncException(SyncException.Kind.NETWORK, "team rekey failed")
            teamRekeys += envelopes
            teamEpoch = newEpoch
        }

        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> =
            scopeEnvelopes.map { (scopeId, grants) ->
                TeamScopeSummary(scopeId, scopeEpochs[scopeId] ?: 0, grants.size, grants[self])
            }

        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) {
            scopeEnvelopes[scopeId] = mutableMapOf(self to envelope)
            scopeEpochs[scopeId] = 0
        }

        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) {
            grants += accountId to envelope
            scopeEnvelopes[scopeId]?.put(accountId, envelope)
        }

        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> =
            scopeEnvelopes[scopeId]?.keys?.map { TeamScopeGrantEntry(it, 0) } ?: emptyList()

        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) {
            scopeEnvelopes[scopeId]?.remove(accountId)
        }

        /** Set to fail every scope rotation the way an exhausted retry does. */
        var scopeRekeyFails = false

        /** The same for the team key's own rotation. */
        var teamRekeyFails = false

        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
            if (scopeRekeyFails) throw SyncException(SyncException.Kind.NETWORK, "scope rekey failed")
            scopeEpochs[scopeId] = newEpoch
            val holders = scopeEnvelopes[scopeId] ?: return
            envelopes.forEach { (account, env) -> if (holders.containsKey(account)) holders[account] = env }
        }

        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage =
            RecordPage(emptyList(), since)

        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage =
            RecordPage(records, 1)

        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) {
            invites += accountId
        }
        override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
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

    protected class Fixture(val vault: FileVault, val teamVaults: TeamVaults)

    protected fun newFixture(): Fixture {
        val vaultFile = Files.createTempFile("skerry-acct", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile)
        val teamDir = Files.createTempDirectory("skerry-teamvaults").toString().toPath()
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        return Fixture(vault, TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW }))
    }

    protected fun coordinator(
        f: Fixture,
        client: TeamClient,
        ids: Iterator<String> = listOf("prod").iterator(),
        /** Whether the sync session is still up — false is the guard clause every operation opens with. */
        connected: () -> Boolean = { true },
    ) =
        TeamsCoordinator(
            live = {
                if (connected()) TeamLink(SyncSession(self, "access", "refresh"), client, "test-link") else null
            },
            vault = f.vault,
            crypto = crypto,
            teamVaults = f.teamVaults,
            teamState = InMemorySyncStateStore(),
            newId = { ids.next() },
        )

    /** An invite from [inviter] to us, sealed to our own sharing key and signed by their identity. */
    protected fun inviteEnvelope(f: Fixture, inviter: String, signing: SigningKeyPair, epoch: Int = 0): ByteArray {
        val identity = TeamIdentityStore(f.vault, crypto).ensure()
        return TeamInviteCodec(crypto).seal(
            recipientPublicKey = identity.sharing.publicKey,
            inviter = signing,
            inviterId = inviter,
            inviteeId = self,
            teamId = teamId,
            teamKey = crypto.newDataKey(),
            teamName = "Ops",
            epoch = epoch,
        )
    }

    protected fun invitedTeam(envelope: ByteArray) = TeamSummary(
        id = teamId, ownerAccountId = bob, role = TeamRole.VIEWER,
        status = TeamMemberStatus.INVITED, createdAt = 0, memberCount = 2,
        envelope = envelope, keyEpoch = 0, keyEnvelope = null,
    )

    /** A scope key granted to us by [granter], signed with [signing] and bound to [scopeId]. */
    protected fun scopeEnvelope(f: Fixture, granter: String, signing: SigningKeyPair, scopeId: String, epoch: Int): ByteArray {
        val identity = TeamIdentityStore(f.vault, crypto).ensure()
        return TeamInviteCodec(crypto).seal(
            recipientPublicKey = identity.sharing.publicKey,
            inviter = signing,
            inviterId = granter,
            inviteeId = self,
            teamId = teamId,
            teamKey = crypto.newDataKey(),
            teamName = "Production",
            epoch = epoch,
            scopeId = scopeId,
        )
    }


    /**
     * The id a pin for [accountId] would use, asked of the store rather than assumed from its
     * namespace: pin an unrelated account, read the id it filed, and swap the account in.
     */
    protected fun pinIdOf(f: Fixture, accountId: String): String {
        val probe = "probe@example.com"
        TeamPeerStore(f.vault).confirm(probe, "probe")
        val filed = f.vault.records().single { it.type == RecordType.TEAM_PEER && !it.deleted }.id
        return filed.removeSuffix(probe) + accountId
    }

    /** The team as the server lists it once we are a member of it. */
    protected fun activeTeam(role: TeamRole, members: Int = 2, keyEpoch: Long = 0, keyEnvelope: ByteArray? = null) = TeamSummary(
        id = teamId, ownerAccountId = if (role == TeamRole.OWNER) self else bob, role = role,
        status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = members,
        envelope = null, keyEpoch = keyEpoch, keyEnvelope = keyEnvelope,
    )

    protected companion object {
        const val NOW = "2026-08-24T00:00:00Z"
    }
}
