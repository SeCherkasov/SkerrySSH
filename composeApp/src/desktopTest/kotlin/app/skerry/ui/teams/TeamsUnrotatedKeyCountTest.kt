package app.skerry.ui.teams

import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #324: removing a member rotates the team key and every scope key they held, and the single
 * error slot can only name one failure. How many keys the removed member walked away with is the
 * fact the manager needs — it is known exactly where it is destroyed — so it is counted, published
 * with the failure that qualifies it, and never left standing beside a later, unrelated one.
 *
 * Split out of [TeamsCoordinatorPeerPinTest], which shares this fixture and had grown past what
 * detekt allows one class.
 */
class TeamsUnrotatedKeyCountTest : TeamsPeerPinFixture() {

    /**
     * Issue #324: both rotations can fail for real — the team rekey on the network, a scope rekey on
     * a 5xx — and the single error slot then names one of them. The removed member walked away with
     * two keys; being told about one understates what is still theirs, and the information is
     * destroyed where it is known. The count says how many keys did not rotate.
     */
    @Test
    fun `a removal that fails both rotations counts both keys`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[carol] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)
        coord.grantScope(teamId, "prod", carol) // carol holds the scope key she must lose

        client.teamRekeyFails = true
        client.scopeRekeyFails = true
        coord.removeMember(teamId, carol)

        assertTrue(client.removed.contains(carol), "the revocation itself still stands")
        assertEquals(TeamsFailure.Network, coord.lastError.value)
        assertEquals(2, coord.unrotatedKeys.value, "the team key and one scope key both stayed put")
    }

    /** A rotation that committed while stepping over a recipient is not a key the member kept. */
    @Test
    fun `a rotation that only skipped a recipient counts no unrotated key`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[carol] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)
        coord.grantScope(teamId, "prod", carol)

        // Bob's key moves: both rotations commit, each stepping over him.
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.removeMember(teamId, carol)

        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
        assertEquals(0, coord.unrotatedKeys.value, "both keys rotated; only a colleague needs re-confirming")
    }

    /**
     * The reread after a removal runs over the same network the rotations just failed on. When it
     * fails too, the manager used to be told about the reread and nothing else — no verdict, and no
     * count — so the one fact this change exists to surface was destroyed by the failure that came
     * after it (#324).
     */
    @Test
    fun `a removal whose reread fails still reports the keys that stayed put`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[carol] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)
        coord.grantScope(teamId, "prod", carol)

        client.teamRekeyFails = true
        client.scopeRekeyFails = true
        client.listTeamsFails = true // the network is gone for the reread too
        coord.removeMember(teamId, carol)

        assertEquals(TeamsFailure.Network, coord.lastError.value)
        assertEquals(2, coord.unrotatedKeys.value, "the count went down with the reread that followed it")
    }

    /**
     * The count qualifies one removal's failure and nothing else. Every operation opens with the
     * same guard — no sync session, no operation — and that path never reaches the reset at the top
     * of an operation, so a count left over from a removal used to be read out beside an unrelated
     * error about a team nobody had touched (#324).
     */
    @Test
    fun `the un-rotated count does not attach to a later unrelated failure`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[carol] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3))
        var connected = true
        val coord = coordinator(f, client, connected = { connected })
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)
        coord.grantScope(teamId, "prod", carol)

        client.teamRekeyFails = true
        client.scopeRekeyFails = true
        coord.removeMember(teamId, carol)
        assertEquals(2, coord.unrotatedKeys.value)

        connected = false
        coord.deleteTeam(teamId) // refused at the guard, having removed nobody

        assertEquals(TeamsFailure.NotConnected, coord.lastError.value)
        assertEquals(0, coord.unrotatedKeys.value, "a removal's count was read out beside another failure")
    }
}
