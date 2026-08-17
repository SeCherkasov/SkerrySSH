package app.skerry.shared.sync

import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlin.coroutines.cancellation.CancellationException

/**
 * Record types introduced after servers were already deployed in the field. They are pushed in a
 * separate, best-effort batch so a server that doesn't know one of them rejects only that batch
 * (see the push in [SyncEngine.sync]). A type joins this set when it ships and leaves it once every
 * supported server release accepts it.
 */
private val TYPES_NEWER_THAN_SOME_SERVERS =
    setOf(RecordType.TRASH, RecordType.RUNBOOK, RecordType.RUNBOOK_RUN, RecordType.TRUSTED_CA)

/**
 * How a server that predates one of [TYPES_NEWER_THAN_SOME_SERVERS] answers a push carrying it:
 * the type fails validation (400) or the route is absent (404). Every other failure of that batch
 * is a real one and belongs to the caller — not swallowed as "the server is just old".
 */
private val OLD_SERVER_REFUSALS = setOf(SyncException.Kind.PROTOCOL, SyncException.Kind.NOT_FOUND)

/**
 * Where the delta sync cursor (`lastSyncVersion`) is stored, one per [key].
 *
 * The key is opaque to the store, and what it names is the caller's invariant: a cursor is only ever a
 * position in ONE server's history, so anything two servers can share is the wrong key. The account vault
 * files it under the whole link (`ServerLink.cursorKey`) — one account id names different accounts on a
 * home and a work instance, and sharing a cursor between them means every record at or below the other's
 * tip is never pulled (issue #242). A team space is keyed on the link AND the space, for the same
 * reason and through [KeyedStateStore].
 */
interface SyncStateStore {
    fun cursor(key: String): Long
    fun setCursor(key: String, cursor: Long)

    /**
     * Every key this store holds a cursor for. For the caller that has to forget a thing whose cursor it
     * filed under more than one link — a team space is synced on whichever server the device was on, and
     * clearing only the current one leaves a stale tip for a later re-join to resume from.
     */
    fun keys(): Set<String>
}

/** In-memory cursor (tests / ephemeral sessions). File persistence is a separate implementation. */
class InMemorySyncStateStore : SyncStateStore {
    private val cursors = mutableMapOf<String, Long>()
    override fun cursor(key: String): Long = cursors[key] ?: 0L
    override fun setCursor(key: String, cursor: Long) {
        cursors[key] = cursor
    }
    override fun keys(): Set<String> = cursors.keys.toSet()
}

/**
 * A [SyncStateStore] pinned to one [key], so a [SyncEngine] — which knows only the session it syncs —
 * keeps its cursor where its caller decided, not where the session's account id would put it.
 */
class KeyedStateStore(
    private val backing: SyncStateStore,
    private val pinnedKey: String,
) : SyncStateStore {
    // The passed key is the caller's idea of where its cursor goes; ignoring it is the whole point. The
    // property is named apart from the parameter on purpose — with both called `key`, dropping one
    // `this.` would turn this into a pass-through and silently re-key every cursor.
    override fun cursor(key: String): Long = backing.cursor(pinnedKey)
    override fun setCursor(key: String, cursor: Long) = backing.setCursor(pinnedKey, cursor)
    override fun keys(): Set<String> = setOf(pinnedKey)
}

/**
 * Outcome of one sync cycle. [rejected] counts incoming LWW winners whose blob failed
 * authentication against their claimed metadata and were NOT applied (a tampering/replay signal
 * from the server — see [app.skerry.shared.vault.MergeResult]); local records survived.
 */
data class SyncOutcome(val pulled: Int, val pushed: Int, val cursor: Long, val rejected: Int = 0)

/**
 * Client-side sync engine. Runs deltas between the local [Vault]
 * and the server via [SyncClient], resolving LWW conflicts inside [Vault.mergeRemote]. Operates on
 * ciphertext blobs — never decrypts the payload (zero-knowledge); the cursor is held by [SyncStateStore].
 *
 * One [sync] cycle: pull delta -> merge into vault -> push all local records -> pull again (to
 * pick up what was just pushed plus any concurrent remote changes, so the cursor doesn't skip
 * them). Requires an unlocked vault.
 */
class SyncEngine(
    private val client: SyncClient,
    private val vault: Vault,
    private val state: SyncStateStore = InMemorySyncStateStore(),
    /**
     * Current "what to sync" settings (account level). Read lazily each cycle, and re-read after
     * an incoming [RecordType.SETTINGS] record is applied in [drainPull], so a disable from
     * another device takes effect within the same cycle. Defaults to syncing everything (tests/
     * configs without the feature). See [SyncSettings].
     */
    private val settings: () -> SyncSettings = { SyncSettings() },
) {

    suspend fun sync(session: SyncSession): SyncOutcome {
        var cursor = state.cursor(session.accountId)
        var pulled = 0
        var rejected = 0
        val onMerged = { m: MergeResult ->
            pulled += m.applied.size
            rejected += m.rejected.size
        }

        cursor = drainPull(session, cursor, onMerged)

        // Push local records of allowed types (account-level "what to sync" filter): a disabled
        // type stays local and never reaches the server. The settings record itself always syncs
        // (shouldSync), otherwise a disable would never reach other devices. Push-all by type is
        // simple and correct at current vault sizes; fine-grained dirty tracking is a future optimization.
        val filter = settings()
        val local = vault.records().filter { filter.shouldSync(it.type, it.id) }
        // Types a self-hosted server may not know yet go in a batch of their own: the server
        // rejects a whole push batch on an unknown type, and losing hosts/keys/settings sync
        // because a trash snapshot or a trusted CA can't be mirrored would be a far worse trade.
        // Their rejection is swallowed — those records then stay device-local until the server is
        // updated, and the next cycle retries (push-all sends them again anyway).
        val (recentTypes, records) = local.partition { it.type in TYPES_NEWER_THAN_SOME_SERVERS }
        var pushed = 0
        if (records.isNotEmpty()) {
            client.push(session, records.map { it.toRemote() })
            pushed += records.size
        }
        if (recentTypes.isNotEmpty()) {
            try {
                client.push(session, recentTypes.map { it.toRemote() })
                pushed += recentTypes.size
            } catch (e: CancellationException) {
                // A `runCatching` here caught this too, so a vault auto-lock or a disconnect landing
                // inside the push left the engine running on a cancelled job through the second pull.
                throw e
            } catch (e: SyncException) {
                // Only the two answers an older server gives to a type it does not know are
                // swallowed. A 401, a 429, a 5xx or a dropped network say nothing about the record
                // type, and hiding them made a batch that will never be accepted look exactly like
                // one the server is merely too old for — so nothing was ever diagnosable.
                if (e.kind !in OLD_SERVER_REFUSALS) throw e
            }
        }

        // Pull again: picks up our own just-pushed records (merge is idempotent) and any remote
        // changes with a serverSeq between the first pull and the push, so the cursor doesn't skip them.
        cursor = drainPull(session, cursor, onMerged)

        state.setCursor(session.accountId, cursor)
        return SyncOutcome(pulled = pulled, pushed = pushed, cursor = cursor, rejected = rejected)
    }

    /** Pulls delta pages until exhausted (for future pagination), merging each page into the vault. */
    private suspend fun drainPull(session: SyncSession, from: Long, onMerged: (MergeResult) -> Unit): Long {
        var cursor = from
        // Filter is read once per drainPull and re-read only after an incoming SETTINGS record is
        // applied (it's a singleton, changes rarely) — not on every page (vault.records()+AEAD is costly).
        var filter = settings()
        while (true) {
            val page = client.pull(session, cursor)
            if (page.records.isNotEmpty()) {
                val incoming = page.records.mapNotNull { it.toVaultRecord() }
                // Settings records are applied first and unfiltered (they control the filter): on
                // a fresh device, a "Snippets" disable must take effect before snippets from the
                // same page get applied. The filter is re-read after merging them.
                // Known edge case: if a disable arrives in the same page as a record of that type
                // pushed before the disable, that record is dropped (its serverSeq is past the
                // cursor). Re-enabling the type triggers a full re-pull that recovers it.
                val settingsRecords = incoming.filter { it.type == RecordType.SETTINGS }
                if (settingsRecords.isNotEmpty()) {
                    onMerged(vault.mergeRemote(settingsRecords))
                    filter = settings()
                }
                val rest = incoming.filter { it.type != RecordType.SETTINGS && filter.shouldSync(it.type, it.id) }
                if (rest.isNotEmpty()) onMerged(vault.mergeRemote(rest))
            }
            // Compact after merge: otherwise a tombstone just merged from this same page would
            // immediately reappear in the vault. Idempotent — the list arrives on every pull while
            // the tombstone is still alive.
            if (page.compactedIds.isNotEmpty()) vault.compact(page.compactedIds)
            if (page.records.isEmpty()) return cursor
            if (page.cursor <= cursor) return page.cursor // guard against looping if the cursor doesn't advance
            cursor = page.cursor
        }
    }

    private fun VaultRecord.toRemote() =
        RemoteRecord(id, type.name, version, updatedAt, deviceId, deleted, blob)

    /** `null` for an unrecognized server type (skipped; the server validates them, client stays resilient). */
    private fun RemoteRecord.toVaultRecord(): VaultRecord? {
        val recordType = RecordType.entries.firstOrNull { it.name == type } ?: return null
        return VaultRecord(id, recordType, version, updatedAt, deviceId, deleted, blob)
    }
}
