package app.skerry.ui.teams

import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Which member a refused seal was about. [TeamsFailure] is an enum and carries no account id, so the
 * error told the user to confirm a fingerprint in the member list without naming whose row — and the
 * pin cannot supply the name either: a colleague who rotated their Teams identity leaves one that is
 * still confirmed, identical to every healthy member's (#326).
 */
class TeamsCoordinatorRefusedPeersTest : TeamsPeerPinFixture() {

    /**
     * The refusal used to carry no account id at all — `TeamsFailure` is an enum — so the screen told
     * the user to confirm a fingerprint in the member list without naming whose. A rotation walks
     * every recipient and can refuse more than one, so what comes out is the set of them (#326).
     */
    @Test
    fun `a rotation that steps over recipients names every one of them`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        listOf(bob, carol, dave).forEach {
            client.published[it] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        }
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(dave, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 4))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight pins each of them
        coord.grantScope(teamId, "prod", carol)
        coord.grantScope(teamId, "prod", dave)

        // Bob and dave rotated their Teams identity; carol did not. The pins for the two of them are
        // still what they were, so nothing about their rows says which is which.
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[dave] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.removeMember(teamId, carol)

        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
        assertEquals(setOf(bob, dave), coord.refusedPeers.value, "both refused recipients, and nobody else")
    }

    /** A grant refused for one member names that member and no other. */
    @Test
    fun `a refused grant names the member it was refused for`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 2))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
        )
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))

        coord.grantScope(teamId, "prod", bob)

        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
        assertEquals(setOf(bob), coord.refusedPeers.value)
    }

    /**
     * The other lookup. A key arriving from the other side is held to the same pin and stepped over
     * the same way, and the remedy is the same ceremony — so the row it belongs to has to be named
     * there too, even though the banner over it is the milder one.
     */
    @Test
    fun `a key ignored on the way in names the account it came from`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId))
        coord.accept(teamId) // the ceremony pins Bob

        // The server rotates Bob's published key to one of its own and offers a scope under it.
        val attacker = crypto.newSigningKeyPair()
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), attacker))
        client.teams = listOf(activeTeam(TeamRole.VIEWER))
        client.scopeEnvelopes["prod"] = mutableMapOf(self to scopeEnvelope(f, bob, attacker, "prod", epoch = 0))
        coord.refresh()

        assertEquals(TeamsFailure.UnconfirmedKeyIgnored, coord.lastError.value)
        assertEquals(setOf(bob), coord.refusedPeers.value)
    }

    /**
     * The ceremony the mark opens is the one thing that empties the slot the mark lives in. A confirm
     * that did not record leaves the account exactly where it was — refused — so the row must keep
     * its mark rather than go quiet under the failure it just raised.
     */
    @Test
    fun `a confirm that did not record leaves the mark where it was`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 2))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
        )
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.grantScope(teamId, "prod", bob) // refused: the key moved
        val shown = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob)).preview
        assertEquals(setOf(bob), coord.refusedPeers.value)

        // The key moves once more between the fingerprint being read out loud and Confirm being
        // pressed: nothing is written, and the pin still does not match what the server publishes.
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.confirmPeer(shown)

        assertEquals(TeamsFailure.RecipientKeyChanged, coord.lastError.value)
        assertEquals(setOf(bob), coord.refusedPeers.value, "the ceremony failed, so the row is where it was")
    }

    /** A confirm that did record is the remedy working: the pin moved, and the mark goes with it. */
    @Test
    fun `a confirm that recorded clears the mark`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 2))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.grantScope(teamId, "prod", bob) // refused: the key moved
        val shown = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob)).preview

        coord.confirmPeer(shown)

        assertNull(coord.lastError.value)
        assertEquals(emptySet(), coord.refusedPeers.value)
    }

    /**
     * A mark is evidence about one colleague, so only evidence about that same colleague may take it
     * away. A rotation refuses several recipients at once; opening the ceremony for one of them — or
     * doing anything else at all — must not quietly clear the rest, which would leave a member whose
     * key is still refused wearing the same quiet mark as everyone healthy: #326's own bug, one row
     * over.
     */
    @Test
    fun `a mark on one colleague outlives an operation about another`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        listOf(bob, dave).forEach {
            client.published[it] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        }
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight pins both
        coord.grantScope(teamId, "prod", dave)
        listOf(bob, dave).forEach {
            client.published[it] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        }
        coord.grantScope(teamId, "prod", bob)
        coord.grantScope(teamId, "prod", dave)
        assertEquals(setOf(bob, dave), coord.refusedPeers.value)

        // The manager deals with bob and never touches dave.
        val shown = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob)).preview
        coord.confirmPeer(shown)

        assertEquals(setOf(dave), coord.refusedPeers.value, "dave's key is still refused, and still says so")
    }

    /**
     * The other direction, and the reason a mark needs no expiry: the lookup that raised it is the
     * one that takes it back. A colleague whose published key is the pinned one again is no longer
     * refused, whether the pin moved under a confirm or the server stopped substituting.
     */
    @Test
    fun `a lookup that finds the key back on its pin clears the mark`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        val pinned = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[bob] = pinned
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 2))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.grantScope(teamId, "prod", bob)
        assertEquals(setOf(bob), coord.refusedPeers.value)

        client.published[bob] = pinned
        coord.grantScope(teamId, "prod", bob)

        assertNull(coord.lastError.value)
        assertEquals(emptySet(), coord.refusedPeers.value)
    }

    /** Another account's vault is another account's colleagues: a reset takes every mark with it. */
    @Test
    fun `a vault reset drops every mark`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 2))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.grantScope(teamId, "prod", bob)
        assertEquals(setOf(bob), coord.refusedPeers.value)

        coord.lock()

        assertEquals(emptySet(), coord.refusedPeers.value)
    }
}
