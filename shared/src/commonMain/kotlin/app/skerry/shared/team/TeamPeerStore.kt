package app.skerry.shared.team

import app.skerry.shared.sync.SyncSession
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The fingerprint of another account's Teams keys as this account holds it — one
 * [RecordType.TEAM_PEER] record per peer.
 *
 * The invite ceremony verifies a fingerprint over a channel the server does not own, but that
 * verification used to end with the send: every later seal to the same colleague (a scope grant, a
 * key rotation after a member removal) fetched the key again and sealed to whatever came back. A
 * server that failed at invite time only had to wait for the next removal (#319). This record is
 * what makes one verification durable — it is the fingerprint every later fetch is held to.
 */
@Serializable
data class TeamPeerEntry(val fingerprint: String)

/** What this account holds for a peer: nothing, something it cannot read, or a fingerprint. */
sealed interface Pin {
    /** Nothing was ever pinned for the account. */
    data object None : Pin

    /**
     * A pin exists and this device cannot read it: the vault is locked, or the payload no longer
     * decrypts (what [Vault.adoptDataKey] leaves behind on a device that joins an existing sync
     * account). Distinct from [None] on purpose — an unreadable pin must fail closed, and must never
     * be overwritten with whatever the server answers next.
     */
    data object Unreadable : Pin

    /** The fingerprint pinned for the account. */
    class Known(val fingerprint: String) : Pin
}

/** Store of [RecordType.TEAM_PEER] records. Synced between the account's own devices like team keys. */
class TeamPeerStore(
    private val vault: Vault,
    /**
     * Where [fetchPinned] and [checkPinned] do their vault work. Every seal to another account goes
     * through them, and a rotation walks every recipient: on a team that predates this record each
     * one is a first-sight write, and a vault write re-serializes and re-writes the whole file. The
     * coordinator's operations run on the caller's thread, which is the UI one.
     */
    internal val vaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val codec = VaultRecordCodec(vault, RecordType.TEAM_PEER, TeamPeerEntry.serializer())

    /** What is pinned for [accountId]. */
    fun pin(accountId: String): Pin = vault.transaction {
        if (!vault.isUnlocked) return@transaction Pin.Unreadable
        val id = recordId(accountId)
        // One transaction for both reads: a compact landing between them would drop the record
        // `codec.get` just failed on, and the fallback would read the fail-open answer.
        codec.get(id)?.let { return@transaction Pin.Known(it.fingerprint) }
        // codec.get answers null for "absent", "tombstoned", "present but does not decrypt" and
        // "present as another type" alike. Both of the last two mean a pin this device cannot read:
        // a blob that stopped opening (an adopted account key), or a record the server named after
        // this id and got there first. Reading either as "nothing pinned" would pin whatever the
        // server publishes next — so any live record at the id fails the read closed.
        // [None] is answered exactly when a first-sight pin could be written here, and a tombstone
        // holds an id's type until compaction — so a deleted TEAM the server named after this pin
        // leaves the slot unwritable just as a live one does. Reading that as "nothing was ever
        // pinned" would pin on first sight, silently drop the write, and call the next fetch first
        // sight again: every later seal follows whatever the server publishes, forever and without
        // a word (#319 reopened for that account). Unwritable reads as unreadable, which fails closed.
        val at = recordAt(id)
        if (at == null || (at.deleted && at.type == RecordType.TEAM_PEER)) Pin.None else Pin.Unreadable
    }

    /**
     * The record at [id], of this type or another, tombstoned or not. Tombstones count for the write
     * guards below because [Vault.put] counts them: an id keeps its type until the record is
     * physically gone, so a deleted TEAM at a pin's id refuses the write just as a live one does.
     */
    private fun recordAt(id: String) = vault.records().firstOrNull { it.id == id }

    /**
     * Pin a fingerprint a human just confirmed out of band (the invite send, the invite accept).
     * Replaces whatever was pinned: an honest identity rotation is re-confirmed by the same ceremony
     * that pinned the first key, and the user is shown that it moved before they get here.
     *
     * Answers `false` when the id is not this store's to write — a record of another type is sitting
     * on it. The ceremony the caller was about to record has to stop there.
     */
    fun confirm(accountId: String, fingerprint: String): Boolean {
        check(vault.isUnlocked) { "pinning a peer needs an unlocked vault" }
        return vault.transaction {
            val id = recordId(accountId)
            // False rather than a throw when another record already holds the id: [Vault.put] refuses
            // to re-type a record, and the caller has to stop the ceremony it was about to record —
            // sealing a key whose confirmation cannot be written is worse than not sealing it.
            val squat = recordAt(id)
            if (squat != null && squat.type != RecordType.TEAM_PEER) return@transaction false
            codec.put(id, TeamPeerEntry(fingerprint))
            true
        }
    }

    /**
     * Pin a key on first sight — nothing has ever been verified for this account, so there is nothing
     * to contradict. An existing pin is kept, readable or not: only a human may move one, and doing
     * it here would undo the guarantee on the very fetch it exists to guard.
     */
    fun rememberFirstSight(accountId: String, fingerprint: String) {
        check(vault.isUnlocked) { "pinning a peer needs an unlocked vault" }
        vault.transaction {
            val id = recordId(accountId)
            // Nothing is written over a live pin (only a human moves one) or over an id another
            // store's record still holds — a write the vault would refuse anyway.
            val at = recordAt(id)
            if (at == null || (at.deleted && at.type == RecordType.TEAM_PEER)) {
                codec.put(id, TeamPeerEntry(fingerprint))
            }
        }
    }

    /**
     * The pin for [accountId] measured against [fingerprint], recording it on first sight when
     * [pinOnFirstSight]. One transaction for the read and the write it decides: a merge landing
     * between them would decide the write against a pin that no longer exists.
     */
    internal fun match(accountId: String, fingerprint: String, pinOnFirstSight: Boolean): Pin =
        vault.transaction {
            val pin = pin(accountId)
            if (pin == Pin.None && pinOnFirstSight) rememberFirstSight(accountId, fingerprint)
            pin
        }

    /**
     * The record id for a peer's pin. Namespaced, and that is load-bearing: the account id comes from
     * the server (a member list, a grant list, the inviter named inside a sealed envelope anyone can
     * seal), while a vault record is located by id alone and replaced wholesale — an un-namespaced id
     * would let the server name a credential's or an identity's record and have this client destroy it.
     */
    private fun recordId(accountId: String) = "$ID_PREFIX$accountId"

    private companion object {
        const val ID_PREFIX = "peer:"
    }
}

/** What [fetchPinned] found: the account's keys, no published key at all, or a key nobody confirmed. */
sealed interface PeerKeys {
    /** The published keys, matching what is pinned for the account (or newly pinned on first sight). */
    class Pinned(val keys: AccountKeys) : PeerKeys

    /** The account has no Teams keys on this server — it has never opened Teams. */
    data object Unpublished : PeerKeys

    /**
     * The published keys are not the ones pinned for the account: they hash to another fingerprint,
     * or the pin itself cannot be read. Either way nothing may be sealed to them.
     */
    class Unconfirmed(val accountId: String, val fingerprint: String) : PeerKeys
}

/**
 * The account's published keys under the pin that guards them: the one call every seal to another
 * account goes through. A key that is not the pinned one is refused rather than sealed to, which is
 * what stops the sync server — the owner of the key table — from substituting its own key on any
 * fetch after the one the user verified (#319).
 *
 * With nothing pinned yet the key is pinned on first sight: a team that predates this record has no
 * pins, and refusing there would take the keys away from every existing member. First sight is no
 * weaker than what the fetch did before; what it adds is that the second sight is checked.
 */
suspend fun TeamPeerStore.fetchPinned(session: SyncSession, client: TeamClient, accountId: String): PeerKeys =
    resolve(session, client, accountId, pinOnFirstSight = true)

/**
 * Like [fetchPinned], but never writes a pin — for the receiving end (a rekey envelope, a scope
 * grant), where the account id is one the server chose and nothing on screen was confirmed. A pin
 * written from there would let the server fix a key of its own as the account's "verified" one, and
 * the real colleague would be the one refused from that point on.
 *
 * Which is the deliberate limit of this side: an account somebody already confirmed is held to that
 * fingerprint, and one nobody ever confirmed is accepted on first sight without being pinned.
 * Refusing there instead would mean refusing every colleague this account has never invited — a team
 * that predates the pin record could not rotate a key at all. Closing that gap needs a rule the
 * fingerprint alone cannot express (a seal refused to any unconfirmed account, plus continuity
 * across an honest rekey), which is its own change.
 */
suspend fun TeamPeerStore.checkPinned(session: SyncSession, client: TeamClient, accountId: String): PeerKeys =
    resolve(session, client, accountId, pinOnFirstSight = false)

private suspend fun TeamPeerStore.resolve(
    session: SyncSession,
    client: TeamClient,
    accountId: String,
    pinOnFirstSight: Boolean,
): PeerKeys {
    val keys = client.fetchPublicKey(session, accountId) ?: return PeerKeys.Unpublished
    val fingerprint = accountKeyFingerprint(keys.sharing, keys.signing)
    val pin = withContext(vaultDispatcher) { match(accountId, fingerprint, pinOnFirstSight) }
    return when (pin) {
        is Pin.Known -> if (pin.fingerprint == fingerprint) PeerKeys.Pinned(keys) else PeerKeys.Unconfirmed(accountId, fingerprint)
        Pin.Unreadable -> PeerKeys.Unconfirmed(accountId, fingerprint)
        Pin.None -> PeerKeys.Pinned(keys)
    }
}
