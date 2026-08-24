package app.skerry.shared.vault

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Shared base for vault-backed stores: records of [type] whose payload is JSON-serialized [T]
 * (single `Json { ignoreUnknownKeys = true }`). The codec does not enforce locked-vault policy —
 * the `isUnlocked` gate stays on the store.
 *
 * Corrupt/undecryptable payload decodes to `null` and is skipped — one bad record doesn't fail the
 * list.
 */
internal class VaultRecordCodec<T>(
    private val vault: Vault,
    private val type: RecordType,
    private val serializer: KSerializer<T>,
    /** Trash to snapshot deletions into; `null` deletes outright (infrastructure records, team vaults). */
    private val trash: TrashStore? = null,
    /** Human-readable name of a value, shown in the trash list. Unused without [trash]. */
    private val label: (T) -> String = { "" },
) {

    /** All live records of the type (tombstones and other types dropped); corrupt payload skipped. */
    fun list(): List<T> =
        vault.records()
            .filter { it.type == type && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }

    /**
     * Ids of every live record of the type, whether or not its payload can be read. [list] drops
     * the unreadable ones (one bad record must not fail a listing), which makes them
     * indistinguishable from records that were deleted — a difference that matters to any caller
     * acting on a disappearance.
     */
    fun liveIds(): Set<String> =
        vault.records().filter { it.type == type && !it.deleted }.map { it.id }.toSet()

    /** Value for [id], or `null` if the record is absent, deleted, or its payload can't be read. */
    fun get(id: String): T? {
        val record = vault.records()
            .firstOrNull { it.id == id && it.type == type && !it.deleted }
            ?: return null
        return decode(vault.openPayload(record.id))
    }

    /** Create or update a record (upsert by [id]). */
    fun put(id: String, value: T) {
        vault.put(id, type, encode(value))
    }

    /**
     * Soft-delete a record (tombstone) — delegates to [Vault.remove]. With a [trash] configured the
     * value is snapshotted first, in the same transaction as the removal and the ownership check
     * below: a merge landing in between would otherwise let the trash hold a payload the deletion
     * never applied to, or move the id under another store between the check and the tombstone.
     *
     * A record of another type under the same id is left alone: [Vault.remove] takes no type, and
     * several stores file ids the sync server chose, so deleting by id alone would let a "team" the
     * server named after a verified peer fingerprint tombstone that fingerprint (#319). Only this
     * store's own records are this store's to delete.
     */
    fun remove(id: String) {
        vault.transaction {
            // Inside the transaction with the removal it guards: a merge landing between the two
            // would let this tombstone a record the guard was there to spare.
            if (vault.records().any { it.id == id && it.type != type }) return@transaction
            trash?.let { bin -> get(id)?.let { bin.capture(id, type, label(it)) } }
            vault.remove(id)
        }
    }

    fun encode(value: T): ByteArray = json.encodeToString(serializer, value).encodeToByteArray()

    fun decode(payload: ByteArray?): T? =
        payload?.let { runCatching { json.decodeFromString(serializer, it.decodeToString()) }.getOrNull() }

    internal companion object {
        // Shared Json for all vault stores: unknown fields ignored (newer-version records stay readable).
        internal val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Singleton vault record (settings/layout): fixed [id] + [type], value is JSON [T]. [load] returns
 * [default] on a locked vault, a missing record, corrupt payload, or a throw from [Vault.openPayload]
 * — this helper must not crash its caller (e.g. the sync loop). [save] requires an unlocked vault.
 */
internal class VaultSingletonStore<T>(
    private val vault: Vault,
    private val id: String,
    private val type: RecordType,
    serializer: KSerializer<T>,
    private val default: () -> T,
) {

    private val codec = VaultRecordCodec(vault, type, serializer)

    fun load(): T = loadOrNull() ?: default()

    /**
     * The stored value, or null when a record **is** there and cannot be read — an unreadable blob
     * (adopting an account dataKey leaves the old ones behind) or a payload that no longer parses.
     * A locked vault and a missing record both answer with [default] through [load]: those mean
     * "nothing stored", which is not the same as "stored, and we cannot see it". A caller that
     * read-modify-writes the record has to tell them apart, or it replaces what it could not read.
     */
    fun loadOrNull(): T? {
        if (!vault.isUnlocked) return default()
        val record = vault.records().firstOrNull { it.id == id && it.type == type && !it.deleted }
            ?: return default()
        // Wrap openPayload: even if the impl throws on I/O/AEAD (rather than returning null), the
        // caller must not crash (it would abort the sync drainPull).
        return codec.decode(runCatching { vault.openPayload(record.id) }.getOrNull())
    }

    fun save(value: T) {
        codec.put(id, value)
    }
}
