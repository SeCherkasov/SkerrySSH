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
) {

    /** All live records of the type (tombstones and other types dropped); corrupt payload skipped. */
    fun list(): List<T> =
        vault.records()
            .filter { it.type == type && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }

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

    /** Soft-delete a record (tombstone) — delegates to [Vault.remove]. */
    fun remove(id: String) {
        vault.remove(id)
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

    fun load(): T {
        if (!vault.isUnlocked) return default()
        val record = vault.records().firstOrNull { it.id == id && it.type == type && !it.deleted }
            ?: return default()
        // Wrap openPayload: even if the impl throws on I/O/AEAD (rather than returning null), the
        // caller must get the default, not a crash (it would abort the sync drainPull).
        return codec.decode(runCatching { vault.openPayload(record.id) }.getOrNull()) ?: default()
    }

    fun save(value: T) {
        codec.put(id, value)
    }
}
