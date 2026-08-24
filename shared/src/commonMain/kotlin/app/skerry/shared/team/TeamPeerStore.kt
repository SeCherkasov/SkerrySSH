package app.skerry.shared.team

import app.skerry.shared.sync.SyncSession
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
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
data class TeamPeerEntry(
    val fingerprint: String,
    /**
     * How the fingerprint got here. Defaulted rather than required, and defaulted to the weaker of
     * the two: records written before this field existed carry no claim at all, and reading them as
     * a confirmation would put the word on a fingerprint nobody read out loud — the defect this
     * field exists to fix (#323). Such a pin still guards every seal; it is only asked to be
     * confirmed from the member list before a screen calls it confirmed.
     */
    val origin: PinOrigin = PinOrigin.FIRST_SIGHT,
)

/**
 * How a pinned fingerprint was established. The distinction is the whole point of the record: a key
 * the server answered with is held to from then on, but nobody vouched for it, and only a human on a
 * channel the server does not own can turn it into a confirmation (#323).
 */
enum class PinOrigin {
    /** A human read the fingerprint out loud: the invite ceremony, or the member list's own confirm. */
    CONFIRMED,

    /** Whatever the server answered the first time something was sealed to the account. */
    FIRST_SIGHT,
}

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

    /**
     * The fingerprint pinned for the account, and what the record claims about it.
     *
     * A data class because a pin is compared: the ceremony carries the one it was shown into the
     * write that replaces it, and screens hold it inside their own state.
     */
    data class Known(val fingerprint: String, val origin: PinOrigin) : Pin
}

/** What a [TeamPeerStore.confirm] did, or why it did nothing. */
enum class ConfirmOutcome {
    /** The fingerprint is on record, confirmed. */
    RECORDED,

    /** The record moved between the ceremony and the write, so what was shown is not what was replaced. */
    MOVED,

    /** The id is not this store's to write: a record of another type holds it. */
    REFUSED,
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
        // One transaction for the lookup and the read it decides: a compact landing between them
        // would drop the record the payload read is about to ask for.
        pinAt(recordAt(recordId(accountId)))
    }

    /**
     * What [record] — whatever sits at a pin's id, of any type, tombstoned or not — says is pinned
     * for that account.
     *
     * A payload that does not decrypt, and a record of another type the server named after this id
     * and got there first, both mean a pin this device cannot read. Reading either as "nothing
     * pinned" would pin whatever the server publishes next — so any live record at the id fails the
     * read closed. [Pin.None] is answered exactly when a first-sight pin could be written here, and
     * a tombstone holds an id's type until compaction: a deleted TEAM the server named after this
     * pin leaves the slot unwritable just as a live one does. Reading that as "nothing was ever
     * pinned" would pin on first sight, silently drop the write, and call the next fetch first sight
     * again — every later seal following whatever the server publishes, forever and without a word
     * (#319 reopened for that account). Unwritable reads as unreadable, which fails closed.
     */
    private fun pinAt(record: VaultRecord?): Pin {
        if (record != null && record.type == RecordType.TEAM_PEER && !record.deleted) {
            codec.decode(vault.openPayload(record.id))
                ?.let { return Pin.Known(it.fingerprint, it.origin) }
        }
        return if (record == null || (record.deleted && record.type == RecordType.TEAM_PEER)) Pin.None else Pin.Unreadable
    }

    /**
     * What is pinned for each of [accountIds] — the member list's read, in one transaction so every
     * row of one paint answers against the same vault state.
     *
     * Suspending, and on [vaultDispatcher] for the same reason the write path is: the transaction
     * takes the account vault's single lock, which every write holds across a whole-file re-serialize
     * and rewrite. Read from a composition it would block the frame for the length of a sync merge's
     * commit.
     */
    suspend fun pins(accountIds: Collection<String>): Map<String, Pin> =
        withContext(vaultDispatcher) {
            vault.transaction {
                if (!vault.isUnlocked) return@transaction accountIds.associateWith { Pin.Unreadable }
                // One pass over the record list for the whole table. [pin] scans it per account — and
                // twice for one with no pin, which is the common case — while the member list is the
                // server's to size and this runs inside the account vault's single lock.
                val wanted = accountIds.mapTo(mutableSetOf()) { recordId(it) }
                val found = vault.records().filter { it.id in wanted }.associateBy { it.id }
                accountIds.associateWith { pinAt(found[recordId(it)]) }
            }
        }

    /**
     * The record at [id], of this type or another, tombstoned or not. Tombstones count for the write
     * guards below because [Vault.put] counts them: an id keeps its type until the record is
     * physically gone, so a deleted TEAM at a pin's id refuses the write just as a live one does.
     */
    private fun recordAt(id: String) = vault.records().firstOrNull { it.id == id }

    /**
     * Pin a fingerprint a human just confirmed out of band (the invite send, the invite accept, the
     * member list's own ceremony). Replaces whatever was pinned: an honest identity rotation is
     * re-confirmed by the same ceremony that pinned the first key, and the user is shown that it
     * moved before they get here.
     *
     * [shown] is what the ceremony was drawn against — pass it and the write is refused if the record
     * moved since. What a moved pin costs is a second, deliberate acknowledgement, and that gate is
     * decided from a pin read when the screen opened: the records sync between this account's own
     * devices, and the server chooses when one arrives. Delivering it after the dialog said "nothing
     * is on record" would otherwise replace a confirmed pin with no gate at all. Null means the
     * caller has no ceremony to hold the write to.
     */
    fun confirm(accountId: String, fingerprint: String, shown: Pin? = null): ConfirmOutcome {
        check(vault.isUnlocked) { "pinning a peer needs an unlocked vault" }
        return vault.transaction {
            val id = recordId(accountId)
            // Refused rather than thrown when another record already holds the id: [Vault.put] refuses
            // to re-type a record, and the caller has to stop the ceremony it was about to record —
            // sealing a key whose confirmation cannot be written is worse than not sealing it. Checked
            // before [shown], because an id this store cannot write is the more specific answer.
            val squat = recordAt(id)
            if (squat != null && squat.type != RecordType.TEAM_PEER) return@transaction ConfirmOutcome.REFUSED
            // Measured on what the ceremony's gate was decided from — [pinNotice] against the
            // fingerprint about to be written — rather than on the record as a whole. A first sight
            // of that very fingerprint landing mid-ceremony contradicts nothing the user was told,
            // and refusing there would throw away a phone call; a record that became a *confirmed*
            // one asks a harder question than the one they answered, and does refuse.
            if (shown != null && pinNotice(pin(accountId), fingerprint) != pinNotice(shown, fingerprint)) {
                return@transaction ConfirmOutcome.MOVED
            }
            codec.put(id, TeamPeerEntry(fingerprint, PinOrigin.CONFIRMED))
            ConfirmOutcome.RECORDED
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
                codec.put(id, TeamPeerEntry(fingerprint, PinOrigin.FIRST_SIGHT))
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
