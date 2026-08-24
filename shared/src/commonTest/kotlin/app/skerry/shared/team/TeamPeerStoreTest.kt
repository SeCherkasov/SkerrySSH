package app.skerry.shared.team

import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pin behind every seal to another account (#319). What it must get right is who may move a
 * fingerprint: a human who confirmed the new one out of band, and nobody else — least of all the
 * server whose answer is being checked.
 */
class TeamPeerStoreTest {

    private val session = SyncSession("alice@example.com", "access", "refresh")
    private val bob = "bob@example.com"

    /** A client whose key table answers with whatever it was last set to — i.e. the sync server. */
    private class FakeKeys(var keys: AccountKeys?) : TeamClient {
        var fetches = 0
        override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? {
            fetches += 1
            return keys
        }
        override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) = Unit
        override suspend fun createTeam(session: SyncSession, teamId: String) = error("unused")
        override suspend fun listTeams(session: SyncSession): List<TeamSummary> = emptyList()
        override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> = emptyList()
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
        override suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage = RecordPage(emptyList(), since)
        override suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage = RecordPage(emptyList(), 0)
        override suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary> = emptyList()
        override suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray) = error("unused")
        override suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String) = error("unused")
        override suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry> = emptyList()
        override suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray) = error("unused")
        override suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String) = error("unused")
        override suspend fun rekeyScope(session: SyncSession, teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) = error("unused")
    }

    private fun keys(seed: Byte) = AccountKeys(ByteArray(32) { seed }, ByteArray(32) { (seed + 1).toByte() })

    @Test
    fun `a first sight pins what the server answered`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        val client = FakeKeys(keys(1))

        val result = store.fetchPinned(session, client, bob)

        assertIs<PeerKeys.Pinned>(result)
        assertEquals(accountKeyFingerprint(keys(1).sharing, keys(1).signing), pinned(store, bob))
    }

    @Test
    fun `a key that moved after the pin is refused, and the pin stands`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        val client = FakeKeys(keys(1))
        store.fetchPinned(session, client, bob)

        client.keys = keys(9) // the server publishes a different key for the same account
        assertIs<PeerKeys.Unconfirmed>(store.fetchPinned(session, client, bob))
        // Refusing is only half of it: a refusal that overwrote the pin would let the second attempt
        // through, which is the same as not having one.
        assertEquals(accountKeyFingerprint(keys(1).sharing, keys(1).signing), pinned(store, bob))
    }

    @Test
    fun `an account with no published key is not a moved one`() = runTest {
        val vault = FakeVault()
        val store = TeamPeerStore(vault)

        assertEquals(PeerKeys.Unpublished, store.fetchPinned(session, FakeKeys(null), bob))
        assertEquals(Pin.None, store.pin(bob), "nothing was answered, so nothing may be pinned")
    }

    @Test
    fun `only a confirmed fingerprint moves the pin`() = runTest {
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        store.rememberFirstSight(bob, "aaaa-bbbb")
        store.rememberFirstSight(bob, "cccc-dddd") // a later fetch must not quietly replace it

        assertEquals("aaaa-bbbb", pinned(store, bob))

        store.confirm(bob, "cccc-dddd") // the invite ceremony: a human read the new one out loud

        assertEquals("cccc-dddd", pinned(store, bob))
        assertTrue(vault.records().any { it.type == RecordType.TEAM_PEER && !it.deleted })
    }

    /**
     * A locked vault cannot answer what was pinned, and "cannot read" must not degrade to "nothing
     * pinned" — that would seal to whatever the server answered on the next fetch.
     */
    @Test
    fun `a locked vault refuses rather than reading as unpinned`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        store.confirm(bob, "aaaa-bbbb")

        vault.locked = true

        assertEquals(Pin.Unreadable, store.pin(bob))
        assertIs<PeerKeys.Unconfirmed>(store.fetchPinned(session, FakeKeys(keys(1)), bob))
        assertFailsWith<IllegalStateException> { store.rememberFirstSight(bob, "cccc-dddd") }
        assertFailsWith<IllegalStateException> { store.confirm(bob, "cccc-dddd") }
    }

    /**
     * A record whose payload no longer decrypts is what [app.skerry.shared.vault.Vault.adoptDataKey]
     * leaves behind on a device that joins an existing sync account. Read as "never pinned" it would
     * be overwritten with the server's current answer — a verified fingerprint silently replaced.
     */
    @Test
    fun `a pin that cannot be decrypted is not an absent one`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        store.confirm(bob, "aaaa-bbbb")
        vault.unreadable += vault.records().single { it.type == RecordType.TEAM_PEER }.id

        assertEquals(Pin.Unreadable, store.pin(bob))
        assertIs<PeerKeys.Unconfirmed>(store.fetchPinned(session, FakeKeys(keys(1)), bob))
        // …and the unreadable record is left alone rather than replaced by what the server answered.
        assertEquals(1, vault.records().count { it.type == RecordType.TEAM_PEER && !it.deleted })
        assertEquals(1L, vault.records().single { it.type == RecordType.TEAM_PEER }.version)
    }

    /**
     * The account id is the server's to choose (a member list, a grant list, the inviter named inside
     * an envelope anyone can seal) and a vault record is located by id alone and replaced wholesale.
     * An un-namespaced pin would let the server name a credential's record and have this client
     * destroy it — the write lands before any signature is checked.
     */
    @Test
    fun `a pin cannot be filed under another record's id`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        vault.put("cred-1", RecordType.CREDENTIAL, "secret".encodeToByteArray())

        TeamPeerStore(vault).confirm("cred-1", "aaaa-bbbb")

        val credential = vault.records().single { it.id == "cred-1" }
        assertEquals(RecordType.CREDENTIAL, credential.type)
        assertEquals("secret", vault.openPayload("cred-1")?.decodeToString())
    }

    /**
     * The receiving paths ([checkPinned]) run on account ids the SERVER named — a member list, a
     * grant list, the inviter inside an envelope anyone can seal. An unpinned account there is still
     * accepted (a colleague who never invited us has never been confirmed, and refusing every one of
     * them would leave a team unable to rotate), but the sighting must write NOTHING: a pin created
     * from this side is the server fixing its own key as the account's verified one, and every later
     * check would then pass against it.
     */
    @Test
    fun `a receiving path never writes a pin`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        val client = FakeKeys(keys(1))

        assertIs<PeerKeys.Pinned>(store.checkPinned(session, client, bob))

        assertEquals(Pin.None, store.pin(bob), "an unconfirmed sighting is not a confirmation")
        assertTrue(vault.records().none { it.type == RecordType.TEAM_PEER })
        // Which is what makes the next answer refusable: once a human pins the real key, the server
        // swapping it is caught — where a pin written above would have made the swap the pinned one.
        store.confirm(bob, accountKeyFingerprint(keys(1).sharing, keys(1).signing))
        client.keys = keys(9)
        assertIs<PeerKeys.Unconfirmed>(store.checkPinned(session, client, bob))
    }

    /**
     * The namespace keeps a pin out of another store's way; this is the other direction — a team the
     * server named after a pin's own record id. A vault record is found by id alone, so without a
     * type check the "team" would carry the pin off with it: overwritten by [TeamKeyStore.put], or
     * tombstoned by [TeamKeyStore.remove] when the user leaves a team they never joined.
     */
    @Test
    fun `a team the server named after a pin cannot overwrite or delete it`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        store.confirm(bob, "aaaa-bbbb")
        val pinId = vault.records().single { it.type == RecordType.TEAM_PEER }.id
        val teams = TeamKeyStore(vault)

        assertFailsWith<IllegalArgumentException> {
            teams.put(pinId, "Ops", TeamRole.OWNER, DataKey(ByteArray(32) { 7 }))
        }
        teams.remove(pinId)

        assertEquals("aaaa-bbbb", pinned(store, bob))
        assertTrue(vault.records().single { it.id == pinId }.let { it.type == RecordType.TEAM_PEER && !it.deleted })
    }

    /**
     * The mirror of the test above: here the server gets there FIRST, so the pin's id already holds a
     * record of another type. Read through the type filter that slot says "never pinned", and the
     * next fetch would pin whatever the server publishes — with the write then refused by the vault,
     * taking the ceremony down with a generic error. Any live record at the id means the pin cannot
     * be read, so the path fails closed instead.
     */
    @Test
    fun `a record squatting the pin id reads as unreadable, not as absent`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        val squatId = pinIdOf(bob)
        vault.put(squatId, RecordType.TEAM, "team key".encodeToByteArray())

        assertEquals(Pin.Unreadable, store.pin(bob))
        assertIs<PeerKeys.Unconfirmed>(store.fetchPinned(session, FakeKeys(keys(1)), bob))
        assertFalse(store.confirm(bob, "aaaa-bbbb"), "an id another record holds is not this store's")
        // Neither adopted nor destroyed: the squat is the server's doing, and this client leaves it
        // exactly where it is while refusing to read a pin out of it.
        val squat = vault.records().single { it.id == squatId }
        assertEquals(RecordType.TEAM, squat.type)
        assertEquals(1L, squat.version)
    }

    /**
     * And the same id once the squat is deleted. A tombstone still holds the id as far as
     * [app.skerry.shared.vault.Vault.put] is concerned, so no pin can ever be written for this
     * account again — and an account whose pin cannot be written must not read as "nothing was ever
     * pinned". That answer pins on first sight, the write silently no-ops, and every later fetch is
     * first sight again: the seal follows whatever the server publishes, forever and without a word.
     * The slot is unreadable, which fails closed.
     */
    @Test
    fun `a tombstoned squat is unreadable, not absent`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        val squatId = pinIdOf(bob)
        vault.put(squatId, RecordType.TEAM, "team key".encodeToByteArray())
        vault.remove(squatId)

        assertEquals(Pin.Unreadable, store.pin(bob), "a pin that cannot be written cannot be trusted")
        assertFalse(store.confirm(bob, "aaaa-bbbb"))
        // Refused, not thrown: whatever ceremony asked for the pin reports it and stops, rather than
        // unwinding on an exception from the vault.
        store.rememberFirstSight(bob, "aaaa-bbbb")
        assertIs<PeerKeys.Unconfirmed>(store.fetchPinned(session, FakeKeys(keys(1)), bob))
        assertEquals(RecordType.TEAM, vault.records().single { it.id == squatId }.type)
    }

    /**
     * An own pin that no longer decrypts is a different case from the squat above: nothing else is
     * using the id, so the human who just confirmed a fingerprint out of band may write it.
     */
    @Test
    fun `a confirmation replaces a pin this device can no longer read`() = runTest {
        initializeVaultCrypto()
        val vault = FakeVault()
        val store = TeamPeerStore(vault)
        store.confirm(bob, "aaaa-bbbb")
        vault.unreadable += vault.records().single { it.type == RecordType.TEAM_PEER }.id

        assertEquals(Pin.Unreadable, store.pin(bob))
        assertTrue(store.confirm(bob, "cccc-dddd"))

        vault.unreadable.clear()
        assertEquals("cccc-dddd", pinned(store, bob))
    }

    /** The pin's record id, asked of the store rather than assumed from its namespace. */
    private fun pinIdOf(accountId: String): String {
        val probe = FakeVault()
        TeamPeerStore(probe).confirm(accountId, "probe")
        return probe.records().single().id
    }

    /** The pin's own fingerprint, or null when nothing readable is pinned. */
    private fun pinned(store: TeamPeerStore, accountId: String) =
        (store.pin(accountId) as? Pin.Known)?.fingerprint
}
