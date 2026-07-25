package app.skerry.shared.vault

import app.skerry.shared.terminal.epochMillis
import kotlinx.serialization.Serializable

/**
 * One deleted record kept in the trash: everything needed to put it back exactly where it was.
 * Lives inside the encrypted payload of a [RecordType.TRASH] record, so [label] (a host name, a key
 * name) never appears in plaintext metadata — same zero-knowledge rule as [Credential].
 *
 * [payload] is the original record's plaintext payload (JSON, as every vault store writes it).
 * [originVersion] is the version the record had while alive; the tombstone that replaced it is
 * `originVersion + 1`, which is what [TrashStore.restore] has to outrank.
 */
@Serializable
data class TrashEntry(
    val originId: String,
    val originType: RecordType,
    val label: String,
    /** Epoch millis of the deletion — the retention window is measured from here. */
    val deletedAt: Long,
    val originVersion: Long,
    val payload: String,
) {
    /** Id of the [RecordType.TRASH] record holding this entry. */
    val recordId: String get() = trashRecordId(originType, originId)

    /** Redacted: the payload is the deleted secret itself. */
    override fun toString(): String = "TrashEntry($originType/$originId, redacted)"
}

private const val TRASH_ID_PREFIX = "skerry.trash:"

/**
 * Id of the trash record for [originId] of [type] — one snapshot per deleted record (deleting the
 * same id again overwrites it). The origin type is part of the id on purpose: selective sync
 * ([app.skerry.shared.sync.SyncSettings]) must decide whether a snapshot may leave the device
 * without decrypting it.
 */
fun trashRecordId(type: RecordType, originId: String): String = "$TRASH_ID_PREFIX${type.name}:$originId"

/** Origin type encoded in a [trashRecordId]; `null` if [recordId] isn't one (or names an unknown type). */
fun trashOriginType(recordId: String): RecordType? {
    if (!recordId.startsWith(TRASH_ID_PREFIX)) return null
    val name = recordId.removePrefix(TRASH_ID_PREFIX).substringBefore(':')
    return RecordType.entries.firstOrNull { it.name == name && it != RecordType.TRASH }
}

/**
 * Read/restore side of the trash, as the UI sees it — [TrashStore] without the [TrashStore.capture]
 * hook that the stores use. Exists so the UI layer can be tested against a stub instead of a vault.
 */
interface TrashSource {
    fun entries(): List<TrashEntry>
    fun restore(recordId: String): Boolean
    fun purge(recordId: String)
    fun emptyAll()
    fun purgeExpired(): Int
}

/**
 * The vault trash: a deletion made through a store keeps a snapshot for [retentionMillis], so an
 * accidental delete — including one that arrived from another device via sync — can be undone.
 *
 * Deletion stays what it was: [Vault.remove] still writes an empty tombstone that propagates
 * normally, and the snapshot is a separate [RecordType.TRASH] record. Consequences worth knowing:
 * the snapshot syncs like any other record (so the trash is the same on every device of the
 * account) and it holds the deleted secret in the vault file until it expires or is purged.
 *
 * Only user-owned records are covered ([SUPPORTED]); infrastructure records (known-hosts, layout,
 * settings, team keys, terminal history) are deleted outright — restoring them has no value and
 * some are rewritten constantly.
 *
 * The store is stateless (everything lives in the vault), so any number of instances over the same
 * vault behave identically. Every method degrades to a no-op on a locked vault instead of throwing:
 * capture runs inside a store's delete path and must never turn a delete into a crash.
 */
class TrashStore(
    private val vault: Vault,
    private val retentionMillis: Long = RETENTION_MILLIS,
    private val now: () -> Long = ::epochMillis,
    /** Tree order to put a restored host back into; shares [VaultHostStore]'s record. */
    private val layout: WorkspaceLayoutStore = WorkspaceLayoutStore(vault),
) : TrashSource {

    private val codec = VaultRecordCodec(vault, RecordType.TRASH, TrashEntry.serializer())

    /**
     * Snapshots the live record [originId] before its deletion; call inside the same
     * [Vault.transaction] as [Vault.remove], so a concurrent merge can't land between the two.
     * `false` (and nothing written) when the vault is locked, the type isn't covered, the record is
     * missing/already a tombstone, or its payload can't be decrypted — in all of those there is
     * nothing to restore later, and the deletion itself must still go through.
     */
    fun capture(originId: String, originType: RecordType, label: String): Boolean {
        if (originType !in SUPPORTED || !vault.isUnlocked) return false
        return runCatching {
            vault.transaction {
                val record = vault.records().firstOrNull { it.id == originId && !it.deleted } ?: return@transaction false
                val payload = vault.openPayload(originId) ?: return@transaction false
                codec.put(
                    trashRecordId(originType, originId),
                    TrashEntry(
                        originId = originId,
                        originType = originType,
                        label = label,
                        deletedAt = now(),
                        originVersion = record.version,
                        payload = payload.decodeToString(),
                    ),
                )
                true
            }
        }.getOrDefault(false)
    }

    /** Restorable entries, newest deletion first. Expired ones are hidden even before [purgeExpired] runs. */
    override fun entries(): List<TrashEntry> {
        if (!vault.isUnlocked) return emptyList()
        val cutoff = now() - retentionMillis
        return codec.list().filter { it.deletedAt > cutoff }.sortedByDescending { it.deletedAt }
    }

    /**
     * Puts the snapshot [recordId] back under its original id and drops it from the trash. Takes an
     * id rather than an entry so callers (the UI) never have to hold the deleted payload: the
     * snapshot is read from the vault here, and an entry already restored or purged elsewhere fails
     * instead of resurrecting a stale payload.
     *
     * The restored record's version is lifted above the tombstone that deleted it
     * ([Vault.putAtLeast]): a device that already compacted that tombstone would otherwise restart at
     * version 1 and lose LWW against devices that still hold it — the record would silently
     * disappear again. Hosts are also put back into the tree order, appended at the end.
     */
    override fun restore(recordId: String): Boolean {
        if (!vault.isUnlocked) return false
        return runCatching {
            vault.transaction {
                val stored = codec.get(recordId) ?: return@transaction false
                vault.putAtLeast(
                    stored.originId,
                    stored.originType,
                    stored.payload.encodeToByteArray(),
                    // originVersion + 1 is the tombstone; the restored record has to beat it.
                    minVersion = stored.originVersion + 2,
                )
                if (stored.originType == RecordType.HOST) restoreHostOrder(stored.originId)
                codec.remove(recordId)
                true
            }
        }.getOrDefault(false)
    }

    /** Forgets one snapshot for good (the deletion stays). No-op if it's already gone. */
    override fun purge(recordId: String) {
        if (!vault.isUnlocked) return
        runCatching { codec.remove(recordId) }
    }

    /** Empties the trash. */
    override fun emptyAll() {
        if (!vault.isUnlocked) return
        runCatching {
            vault.transaction { codec.list().forEach { codec.remove(it.recordId) } }
        }
    }

    /**
     * Drops entries past the retention window; returns how many. Called when the trash is opened and
     * after unlock — there is no timer, so an expired snapshot lives in the file until the app next
     * looks at it (with [entries] already hiding it).
     */
    override fun purgeExpired(): Int {
        if (!vault.isUnlocked) return 0
        return runCatching {
            vault.transaction {
                val cutoff = now() - retentionMillis
                val expired = codec.list().filter { it.deletedAt <= cutoff }
                expired.forEach { codec.remove(it.recordId) }
                expired.size
            }
        }.getOrDefault(0)
    }

    /**
     * Appends a restored host to the tree order (it was dropped from it on delete). Runs inside the
     * caller's [Vault.transaction], so the read-modify-write can't interleave with a merge; the
     * layout store holds no state of its own, so a separate instance is safe here.
     */
    private fun restoreHostOrder(hostId: String) {
        val current = layout.read()
        if (hostId !in current.hostOrder) layout.write(current.copy(hostOrder = current.hostOrder + hostId))
    }

    companion object {
        /** Record types the trash covers — everything a user creates by hand and would miss. */
        val SUPPORTED = setOf(RecordType.HOST, RecordType.CREDENTIAL, RecordType.SNIPPET, RecordType.TUNNEL)

        /** 30 days, as in the feature note: long enough to notice a bad sync, short enough to stay small. */
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
