package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.Pin
import app.skerry.shared.team.PinOrigin
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamKeyStore
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
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every seal after the invite used to go to whatever key the server answered with (#319): the
 * account's own key table is the server's, so it can hand out one key for the check and another for
 * the seal, or simply swap a member's key once and wait for the next grant or rotation.
 *
 * The fake below is that server. What it must not achieve is a team key, a scope key or a rotated
 * key ending up under a key nobody ever read out loud.
 */
class TeamsCoordinatorPeerPinTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-pin"
    private val self = "alice@example.com"
    private val bob = "bob@example.com"
    private val carol = "carol@example.com"
    private val dave = "dave@example.com"

    /** An account's published keys, as the server holds them — swappable between two fetches. */
    private class Published(var keys: AccountKeys)

    private fun keysOf(sharing: SharingKeyPair, signing: SigningKeyPair) =
        AccountKeys(sharing.publicKey, signing.publicKey)

    private fun fingerprintOf(keys: AccountKeys) = accountKeyFingerprint(keys.sharing, keys.signing)

    /**
     * Fake team network: a mutable key table, a mutable member list, and every envelope the client
     * hands over kept for inspection.
     */
    private class FakePeerClient(private val self: String, private val teamId: String) : TeamClient {
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

        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = teams
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
            live = { TeamLink(SyncSession(self, "access", "refresh"), client, "test-link") },
            vault = f.vault,
            crypto = crypto,
            teamVaults = f.teamVaults,
            teamState = InMemorySyncStateStore(),
            newId = { ids.next() },
        )

    /** An invite from [inviter] to us, sealed to our own sharing key and signed by their identity. */
    private fun inviteEnvelope(f: Fixture, inviter: String, signing: SigningKeyPair, epoch: Int = 0): ByteArray {
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

    private fun invitedTeam(envelope: ByteArray) = TeamSummary(
        id = teamId, ownerAccountId = bob, role = TeamRole.VIEWER,
        status = TeamMemberStatus.INVITED, createdAt = 0, memberCount = 2,
        envelope = envelope, keyEpoch = 0, keyEnvelope = null,
    )

    /**
     * Item 1: the banner verifies the envelope's signature against one fetch and used to fingerprint
     * a second one. A server answering the first with a key it forged the invite under and the second
     * with the real colleague's key gets its own team confirmed over the phone.
     */
    @Test
    fun `the fingerprint on the invite banner belongs to the key the signature was checked against`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val forged = crypto.newSigningKeyPair()
        val forgedKeys = keysOf(crypto.newSharingKeyPair(), forged)
        val realKeys = keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair())
        val backing = FakePeerClient(self, teamId)
        backing.teams = listOf(invitedTeam(inviteEnvelope(f, bob, forged)))
        // The key table is the server's: the fetch the signature is checked against answers with the
        // key it forged the invite under, any later one with the real colleague's key.
        var fetches = 0
        val client = object : TeamClient by backing {
            override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? {
                fetches += 1
                return if (accountId != bob) null else if (fetches == 1) forgedKeys else realKeys
            }
        }
        val coord = coordinator(f, client)

        val preview = assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId), "the envelope verifies").preview

        assertEquals(
            fingerprintOf(forgedKeys),
            preview.fingerprint,
            "the fingerprint must come from the key the signature was verified against, not a second fetch",
        )
        assertEquals(1, fetches, "one fetch, one key: a second answer is a window the server owns")
    }

    /** Item 2: Accept before the banner resolved adopted a team key with no ceremony at all. */
    @Test
    fun `accept refuses an invite whose inviter was never shown`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)

        coord.accept(teamId) // the Accept button pressed while the banner is still resolving

        assertNull(TeamKeyStore(f.vault).get(teamId), "no team key may be adopted without the ceremony")
        assertEquals(emptyList(), client.accepted)
        assertEquals(TeamsFailure.InviteUnverified, coord.lastError.value)
    }

    /** The same accept, after the banner did resolve, still works — and pins the inviter. */
    @Test
    fun `accept after the banner adopts the key and pins the inviter`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)

        val shown = assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId)).preview
        coord.accept(teamId)

        assertNotNull(TeamKeyStore(f.vault).get(teamId))
        assertEquals(listOf(teamId), client.accepted)
        // The pin is the point of the ceremony: what was read out loud is what every later envelope
        // from Bob is held to. Adopting the key without writing it leaves the next rotation open.
        assertEquals(
            shown.fingerprint,
            (TeamPeerStore(f.vault).pin(bob) as? Pin.Known)?.fingerprint,
            "accepting must pin the fingerprint the banner showed",
        )
        assertFalse(shown.keyChanged, "nothing was pinned before, so nothing moved")
    }

    /**
     * An invite from an account already pinned under another fingerprint is an identity that moved:
     * an honest rotation, or the server trying its luck. Accepting replaces the pin, so the banner
     * has to say so before the user reads the new one out loud.
     */
    @Test
    fun `an invite from an account pinned under another key is shown as changed`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        TeamPeerStore(f.vault).confirm(bob, "an-older-fingerprint")
        val coord = coordinator(f, client)

        val preview = assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId)).preview

        assertTrue(preview.keyChanged, "the pinned fingerprint is not this one")
        assertEquals(
            "an-older-fingerprint",
            (TeamPeerStore(f.vault).pin(bob) as? Pin.Known)?.fingerprint,
            "showing the banner must not move the pin — only accepting it does",
        )
        coord.accept(teamId)
        assertEquals(preview.fingerprint, (TeamPeerStore(f.vault).pin(bob) as? Pin.Known)?.fingerprint)
    }

    /**
     * A check that could not be made is not a check that failed. The banner refuses Accept either
     * way, but only one of the two is the user's colleague sending something forged — the other is
     * their own vault being locked, and it is retried rather than accused.
     */
    @Test
    fun `a check that could not be made is not an unverified invite`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)

        f.vault.lock()
        assertEquals(InviteVerdict.Failed(TeamsFailure.VaultLocked), coord.acceptPreview(teamId))
        // The verdict is the whole report: the banner draws and announces it, and writing the shared
        // error slot as well would have two live regions on one screen speak about one event.
        assertNull(coord.lastError.value)

        f.vault.unlock("master".toCharArray())
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId), "the same invite verifies once the check can run")

        // An envelope that is genuinely not ours is the other answer, and it is permanent.
        client.teams = listOf(invitedTeam(ByteArray(96) { 0x7 }))
        assertEquals(InviteVerdict.Unverified, coord.acceptPreview(teamId))
    }

    /**
     * The other local condition that used to read as a forgery: the identity the envelope is sealed
     * to is gone from this device (a reactivation reconcile drops the record, the re-pull has not
     * landed) or no longer decrypts. Nothing is wrong with the invite, and the banner must not say
     * the inviter sent something forged.
     */
    @Test
    fun `an identity this device cannot read is not a forged invite`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)

        f.vault.remove(f.vault.records().single { it.type == RecordType.TEAM_IDENTITY }.id)

        assertEquals(InviteVerdict.Failed(TeamsFailure.IdentityUnreadable), coord.acceptPreview(teamId))
        assertNull(coord.lastError.value)
    }

    /**
     * Item 4, receiving end: a rotation envelope arrives signed by whatever key the server publishes
     * for the rotator. Once that account is pinned, a key that moved cannot pass as theirs.
     */
    @Test
    fun `a rotation envelope signed under a moved key is not adopted`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId))
        coord.accept(teamId)
        val adopted = assertNotNull(TeamKeyStore(f.vault).get(teamId))

        // The server rotates on Bob's behalf under a key of its own and republishes it as his.
        val attacker = crypto.newSigningKeyPair()
        val attackerSharing = crypto.newSharingKeyPair()
        client.published[bob] = Published(keysOf(attackerSharing, attacker))
        client.teams = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = bob, role = TeamRole.VIEWER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 2,
                envelope = null, keyEpoch = 1, keyEnvelope = inviteEnvelope(f, bob, attacker, epoch = 1),
            ),
        )
        coord.refresh()

        val after = assertNotNull(TeamKeyStore(f.vault).get(teamId))
        assertEquals(adopted.teamKey, after.teamKey, "a key signed under a fingerprint nobody verified must be ignored")
        assertEquals(0, after.epoch)
    }

    /** Item 3: a scope grant sealed to whatever the server answers, with nothing on screen to contradict it. */
    @Test
    fun `a scope grant to a key that moved since it was pinned is refused`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = self, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 2,
                envelope = null, keyEpoch = 0, keyEnvelope = null,
            ),
        )
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob) // first sight: the key is pinned
        assertEquals(1, client.grants.size)

        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.grantScope(teamId, "prod", bob)

        assertEquals(1, client.grants.size, "the scope key must not be sealed to a key that moved")
        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
    }

    /**
     * Item 4, sending end: every removal re-seals the new team key to freshly fetched keys, so a
     * server that failed at invite time only had to wait for the next removal.
     */
    @Test
    fun `a rotation does not re-seal the new team key to a member whose key moved`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[carol] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.published[dave] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(dave, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = self, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 4,
                envelope = null, keyEpoch = 0, keyEnvelope = null,
            ),
        )
        val coord = coordinator(f, client)

        coord.removeMember(teamId, carol) // first rotation: pins the keys it seals to
        assertTrue(client.teamRekeys.last().containsKey(bob))

        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.removeMember(teamId, dave)

        val resealed = client.teamRekeys.last()
        assertFalse(resealed.containsKey(bob), "the rotated key must not be handed to a key nobody verified")
        assertTrue(client.removed.contains(dave), "the revocation itself still stands")
        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
    }

    /** A scope key granted to us by [granter], signed with [signing] and bound to [scopeId]. */
    private fun scopeEnvelope(f: Fixture, granter: String, signing: SigningKeyPair, scopeId: String, epoch: Int): ByteArray {
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
     * The send half of the ceremony: pressing Send is a human saying the fingerprint on screen is the
     * colleague's. Nothing recorded that, so the next fetch for the same account started from zero —
     * and the banner could not tell the user their colleague's key had moved since.
     */
    @Test
    fun `the invite pins the key it sealed to, and a later lookup says it moved`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = self, role = TeamRole.OWNER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 2,
                envelope = null, keyEpoch = 0, keyEnvelope = null,
            ),
        )
        val coord = coordinator(f, client)

        val first = assertNotNull(coord.previewPeerKey(bob))
        assertFalse(first.keyChanged, "nothing was ever pinned for this account, so nothing moved")
        coord.invite(teamId, first, TeamRole.VIEWER)
        assertEquals(listOf(bob), client.invites)

        // The server publishes another key for the same colleague after the ceremony.
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))

        val second = assertNotNull(coord.previewPeerKey(bob))
        assertTrue(second.keyChanged, "the fingerprint differs from the pinned one and the user must be told")
        // …and until a human confirms the new one, nothing is sealed to it.
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)
        assertEquals(emptyList(), client.grants, "the scope key must not be sealed to a key nobody confirmed")
        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
    }

    /**
     * Item 3, receiving end: a scope grant arrives signed by whatever key the server publishes for the
     * granter. Once that account is pinned, a signature checked against a key that is not the pinned
     * one buys nothing — the scope key it carries is the server's to read.
     */
    @Test
    fun `a scope grant signed under an unconfirmed key is not adopted`() = runBlocking {
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
        client.teams = listOf(
            TeamSummary(
                id = teamId, ownerAccountId = bob, role = TeamRole.VIEWER,
                status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = 2,
                envelope = null, keyEpoch = 0, keyEnvelope = null,
            ),
        )
        client.scopeEnvelopes["prod"] = mutableMapOf(self to scopeEnvelope(f, bob, attacker, "prod", epoch = 0))
        coord.refresh()

        assertNull(TeamKeyStore(f.vault).scope(teamId, "prod"), "a scope key signed by an unconfirmed identity must be ignored")
        assertEquals(TeamsFailure.UnconfirmedKeyIgnored, coord.lastError.value)
    }

    /**
     * The warning is deliberately re-earnable: an unconfirmed key is a standing condition, not an
     * event, and a pass over it starts by clearing the slot. Remembered for the lifetime of the
     * coordinator it would be said once and never again, while the scope key stays unadopted.
     */
    @Test
    fun `a second pass over the same unconfirmed key reports it again`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId))
        coord.accept(teamId) // the ceremony pins Bob

        val attacker = crypto.newSigningKeyPair()
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), attacker))
        client.teams = listOf(activeTeam(TeamRole.VIEWER))
        client.scopeEnvelopes["prod"] = mutableMapOf(self to scopeEnvelope(f, bob, attacker, "prod", epoch = 0))

        coord.refresh()
        assertEquals(TeamsFailure.UnconfirmedKeyIgnored, coord.lastError.value)
        coord.refresh()
        assertEquals(TeamsFailure.UnconfirmedKeyIgnored, coord.lastError.value, "the condition still holds, so it is still said")
    }

    /**
     * `lastError` holds a single value and the reread that ends a removal writes it too. Published
     * before that reread, the rotation verdict was replaced by whatever the reread found — "the
     * removed member still holds the team key" hidden behind the milder "a key nobody confirmed was
     * ignored", which is the one thing on this screen the user cannot afford to miss.
     */
    @Test
    fun `the rotation verdict outlives the reread that follows it`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId))
        coord.accept(teamId) // pins Bob

        // Bob's published key moves, and the reread has something of its own to complain about: a
        // scope grant signed under that key, which it steps over with UnconfirmedKeyIgnored.
        val attacker = crypto.newSigningKeyPair()
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), attacker))
        client.teams = listOf(activeTeam(TeamRole.VIEWER))
        client.scopeEnvelopes["prod"] = mutableMapOf(self to scopeEnvelope(f, bob, attacker, "prod", epoch = 0))
        client.memberList = listOf(
            TeamMember(self, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )

        coord.removeMember(teamId, carol)

        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value, "the rotation verdict is the one that stands")
    }

    /**
     * The other rotation trigger: a revoked grant. The scope still rotates for the holders that
     * stand, the one whose key moved is stepped over rather than re-sealed to, and the skip is said
     * out loud — a revocation reported as clean while a member's key was never rotated is the
     * failure this whole path exists to prevent.
     */
    @Test
    fun `revoking a grant rotates the scope past a holder whose key moved`() = runBlocking {
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
        coord.grantScope(teamId, "prod", bob) // first sight: both keys are pinned here
        coord.grantScope(teamId, "prod", carol)
        val sealedToBob = assertNotNull(client.scopeEnvelopes["prod"]?.get(bob))

        // Bob's key moves, and the reread that ends the revocation has something of its own to
        // report: a rotated team key signed under that moved key, which it steps over with the
        // milder UnconfirmedKeyIgnored. The scope verdict is the one the user must be left with.
        val moved = crypto.newSigningKeyPair()
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), moved))
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3, keyEpoch = 1, keyEnvelope = inviteEnvelope(f, bob, moved, epoch = 1)))
        coord.revokeScope(teamId, "prod", carol)

        val holders = assertNotNull(client.scopeEnvelopes["prod"])
        assertFalse(holders.containsKey(carol), "the revocation itself still stands")
        assertContentEquals(sealedToBob, holders[bob], "the new scope key must not be sealed to a key nobody verified")
        assertEquals(1L, client.scopeEpochs["prod"], "the scope rotated for the holders that remain")
        assertEquals(TeamsFailure.PeerKeyUnconfirmed, coord.lastError.value)
    }

    /**
     * Both rotations can fail in one removal, and `lastError` holds one of them. The team key is the
     * more serious of the two — except when its own verdict is the mild one: a rotation that
     * committed while stepping over a recipient. A scope that never rotated at all is a key the
     * removed member kept, and it must not be replaced by "one colleague to re-confirm".
     */
    @Test
    fun `a scope that never rotated outranks a team rotation that only skipped someone`() = runBlocking {
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
        coord.grantScope(teamId, "prod", bob) // first sight pins both of them
        coord.grantScope(teamId, "prod", carol) // carol holds the scope key she must lose

        // Bob's key moves: the team rotation commits and steps over him. The scope rotation cannot
        // commit at all, so carol keeps that key.
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.scopeRekeyFails = true
        coord.removeMember(teamId, carol)

        assertTrue(client.removed.contains(carol), "the revocation itself still stands")
        assertEquals(TeamsFailure.Network, coord.lastError.value, "the scope nobody rotated is the one to report")
    }

    /**
     * The other direction of the same rule, and the one the removal hangs on: the team key itself
     * never rotated, so the member who was just removed still holds it. A scope that rotated while
     * stepping over one recipient is the milder verdict and must not take the slot.
     */
    @Test
    fun `a team key that never rotated outranks a scope that only skipped someone`() = runBlocking {
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
        coord.grantScope(teamId, "prod", bob) // first sight pins both of them
        coord.grantScope(teamId, "prod", carol)

        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.teamRekeyFails = true
        coord.removeMember(teamId, carol)

        assertTrue(client.removed.contains(carol), "the revocation itself still stands")
        assertEquals(1L, client.scopeEpochs["prod"], "the scope rotated, stepping over the moved key")
        assertEquals(TeamsFailure.Network, coord.lastError.value, "the key the removed member kept is the one to report")
    }

    /**
     * A pin the vault refuses to write is the ceremony failing, not a detail: the fingerprint was
     * read out loud and nothing would record it. The invite goes nowhere.
     *
     * The id can be occupied because a team id is the server's to choose, and a vault record is found
     * by id alone — file a "team" at the pin's id and the confirmation has nowhere to live.
     */
    @Test
    fun `an invite whose pin cannot be written is not sent`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        val coord = coordinator(f, client)
        val preview = assertNotNull(coord.previewPeerKey(bob))
        TeamKeyStore(f.vault).put(pinIdOf(f, bob), "Squat", TeamRole.VIEWER, crypto.newDataKey(), epoch = 0)

        coord.invite(teamId, preview, TeamRole.VIEWER)

        assertEquals(emptyList(), client.invites, "the team key must not be sealed to a key nothing records")
        assertEquals(TeamsFailure.PinNotRecorded, coord.lastError.value)
    }

    /** The same refusal on the invitee's side: no membership whose later envelopes nothing guards. */
    @Test
    fun `an accept whose pin cannot be written joins nothing`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId))
        TeamKeyStore(f.vault).put(pinIdOf(f, bob), "Squat", TeamRole.VIEWER, crypto.newDataKey(), epoch = 0)

        coord.accept(teamId)

        assertEquals(emptyList(), client.accepted)
        assertNull(TeamKeyStore(f.vault).get(teamId), "no key is adopted without a pin to hold it to")
        assertEquals(TeamsFailure.PinNotRecorded, coord.lastError.value)
    }

    /**
     * A scope grant pins whatever the server answered, and until #323 that pin was indistinguishable
     * from one a human read out loud. Confirming from the member list is what promotes it: the same
     * fingerprint, a different claim about who vouched for it.
     */
    @Test
    fun `confirming from the member list promotes a first sight pin without moving it`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)

        val first = assertIs<Pin.Known>(coord.peerPins(listOf(bob))[bob])
        assertEquals(PinOrigin.FIRST_SIGHT, first.origin, "nobody read this fingerprint out loud")

        val ready = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob))
        coord.confirmPeer(ready.preview)

        val after = assertIs<Pin.Known>(coord.peerPins(listOf(bob))[bob])
        assertEquals(PinOrigin.CONFIRMED, after.origin)
        assertEquals(first.fingerprint, after.fingerprint, "confirming records who vouched, it does not move the key")
        assertNull(coord.lastError.value)
    }

    /**
     * The confirm re-fetches the key for the same reason the invite does (#316): the fingerprint on
     * screen is what the user read out loud, and the server owns the key table. Answering the confirm
     * with a different key must not have the record claim a human confirmed it.
     */
    @Test
    fun `a confirm the server answers with another key leaves the pin as it was`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        client.memberList = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
        )
        client.teams = listOf(activeTeam(TeamRole.OWNER))
        val coord = coordinator(f, client)
        coord.createScope(teamId, "Production")
        coord.grantScope(teamId, "prod", bob)
        val ready = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob))

        // The server swaps the key between the fingerprint being read out loud and the confirm.
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        coord.confirmPeer(ready.preview)

        assertEquals(TeamsFailure.RecipientKeyChanged, coord.lastError.value)
        val after = assertIs<Pin.Known>(coord.peerPins(listOf(bob))[bob])
        assertEquals(PinOrigin.FIRST_SIGHT, after.origin, "the ceremony did not reach the key that is now published")
        assertEquals(ready.preview.fingerprint, after.fingerprint)
    }

    /** A vault that locked between the fingerprint being read out loud and the press writes nothing. */
    @Test
    fun `a confirm against a vault that locked meanwhile records nothing`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        val coord = coordinator(f, client)
        val ready = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob))

        f.vault.lock()
        coord.confirmPeer(ready.preview)

        assertEquals(TeamsFailure.VaultLocked, coord.lastError.value)
        f.vault.unlock("master".toCharArray())
        assertEquals(Pin.None, TeamPeerStore(f.vault).pin(bob), "nothing was written")
    }

    /**
     * And so does one that locks during the round trip the press starts. The confirm re-fetches the
     * key before writing (#316), which is a suspension long enough for an idle timer to fire — the
     * check the press passed on its way in says nothing about the state the write lands in.
     */
    @Test
    fun `a confirm against a vault that locks during the lookup records nothing`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        val coord = coordinator(f, client)
        val ready = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob))

        client.duringFetch = { f.vault.lock() } // the idle timer fires while the key is in flight
        coord.confirmPeer(ready.preview)

        assertEquals(TeamsFailure.VaultLocked, coord.lastError.value)
        f.vault.unlock("master".toCharArray())
        assertEquals(Pin.None, TeamPeerStore(f.vault).pin(bob), "nothing was written")
    }

    /**
     * The acknowledgement a moved pin costs is decided from the record the ceremony was drawn against.
     * These records sync between this account's own devices, so the server chooses when one lands: it
     * must not be able to slip one in behind a dialog that has already asked its question (#323).
     */
    @Test
    fun `a confirm is refused when the record moved while the fingerprint was on screen`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val client = FakePeerClient(self, teamId)
        val keys = keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair())
        client.published[bob] = Published(keys)
        val coord = coordinator(f, client)
        // The dialog opens against an empty record and demands no acknowledgement…
        val ready = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob))
        assertEquals(Pin.None, ready.preview.pinned)

        // …and a pin for another key lands from the account's own sync before the press.
        TeamPeerStore(f.vault).confirm(bob, "an-older-fingerprint")
        coord.confirmPeer(ready.preview)

        assertEquals(TeamsFailure.PinMovedMeanwhile, coord.lastError.value)
        assertEquals(
            Pin.Known("an-older-fingerprint", PinOrigin.CONFIRMED),
            TeamPeerStore(f.vault).pin(bob),
            "the record the ceremony never saw stands",
        )
    }

    /**
     * The same gate on the invitee's side, where the press adopts a team key. The banner decides its
     * acknowledgement from the pin it read when it drew the fingerprint, so a record landing behind
     * it must not turn an unanswered question into an answered one (#323).
     */
    @Test
    fun `an accept is refused when the record moved while the fingerprint was on the banner`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val signing = crypto.newSigningKeyPair()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), signing))
        client.teams = listOf(invitedTeam(inviteEnvelope(f, bob, signing)))
        val coord = coordinator(f, client)
        assertIs<InviteVerdict.Verified>(coord.acceptPreview(teamId)) // drawn against an empty record

        // A pin for another key arrives from this account's own sync before Accept is pressed.
        TeamPeerStore(f.vault).confirm(bob, "an-older-fingerprint")
        coord.accept(teamId)

        assertEquals(TeamsFailure.PinMovedMeanwhile, coord.lastError.value)
        assertEquals(emptyList(), client.accepted)
        assertNull(TeamKeyStore(f.vault).get(teamId), "no key is adopted against a record nobody was shown")
    }

    /** A confirm whose record cannot be written says so, rather than reading as a confirmation. */
    @Test
    fun `a confirm whose pin cannot be written is reported`() = runBlocking {
        initializeVaultCrypto()
        val f = newFixture()
        val client = FakePeerClient(self, teamId)
        client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        val coord = coordinator(f, client)
        val ready = assertIs<PeerKeyVerdict.Ready>(coord.peerKey(bob))
        TeamKeyStore(f.vault).put(pinIdOf(f, bob), "Squat", TeamRole.VIEWER, crypto.newDataKey(), epoch = 0)

        coord.confirmPeer(ready.preview)

        assertEquals(TeamsFailure.PinNotRecorded, coord.lastError.value)
        assertEquals(Pin.Unreadable, coord.peerPins(listOf(bob))[bob])
    }

    /**
     * The id a pin for [accountId] would use, asked of the store rather than assumed from its
     * namespace: pin an unrelated account, read the id it filed, and swap the account in.
     */
    private fun pinIdOf(f: Fixture, accountId: String): String {
        val probe = "probe@example.com"
        TeamPeerStore(f.vault).confirm(probe, "probe")
        val filed = f.vault.records().single { it.type == RecordType.TEAM_PEER && !it.deleted }.id
        return filed.removeSuffix(probe) + accountId
    }

    /** The team as the server lists it once we are a member of it. */
    private fun activeTeam(role: TeamRole, members: Int = 2, keyEpoch: Long = 0, keyEnvelope: ByteArray? = null) = TeamSummary(
        id = teamId, ownerAccountId = if (role == TeamRole.OWNER) self else bob, role = role,
        status = TeamMemberStatus.ACTIVE, createdAt = 0, memberCount = members,
        envelope = null, keyEpoch = keyEpoch, keyEnvelope = keyEnvelope,
    )

    private companion object {
        const val NOW = "2026-08-24T00:00:00Z"
    }
}
