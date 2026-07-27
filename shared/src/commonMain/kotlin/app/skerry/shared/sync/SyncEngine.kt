package app.skerry.shared.sync

import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord

/**
 * Record types introduced after servers were already deployed in the field. They are pushed in a
 * separate, best-effort batch so a server that doesn't know one of them rejects only that batch
 * (see the push in [SyncEngine.sync]). A type joins this set when it ships and leaves it once every
 * supported server release accepts it.
 */
private val TYPES_NEWER_THAN_SOME_SERVERS = setOf(RecordType.TRASH, RecordType.RUNBOOK, RecordType.TRUSTED_CA)

/** Where the delta sync cursor (`lastSyncVersion`) is stored, per device/account. */
interface SyncStateStore {
    fun cursor(accountId: String): Long
    fun setCursor(accountId: String, cursor: Long)
}

/** In-memory cursor (tests / ephemeral sessions). File persistence is a separate implementation. */
class InMemorySyncStateStore : SyncStateStore {
    private val cursors = mutableMapOf<String, Long>()
    override fun cursor(accountId: String): Long = cursors[accountId] ?: 0L
    override fun setCursor(accountId: String, cursor: Long) {
        cursors[accountId] = cursor
    }
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
        if (recentTypes.isNotEmpty() && runCatching { client.push(session, recentTypes.map { it.toRemote() }) }.isSuccess) {
            pushed += recentTypes.size
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
