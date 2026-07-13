package app.skerry.shared.vault

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path

/** Plaintext part of the vault file: format version and material for dataKey derivation/wrapping. */
@Serializable
internal data class VaultMeta(
    val formatVersion: Int,
    val salt: ByteArray,
    val wrappedDataKey: ByteArray,
)

/** Root of the vault file: [VaultMeta] + encrypted records. */
@Serializable
internal data class VaultFileBody(
    val meta: VaultMeta,
    val records: List<VaultRecord>,
)

/**
 * File-backed [Vault] over okio (desktop JVM + Android), I/O behind [FileSystem] (desktop/mobile
 * pass `FileSystem.SYSTEM`, tests pass `FakeFileSystem`): records are cached in memory, the file is
 * rewritten atomically (tmp + [FileSystem.atomicMove]); an unreadable file on unlock yields
 * [UnlockResult.Corrupted].
 *
 * `dataKey` lives in memory only between [unlock]/[create] and [lock], never leaving the class.
 * Record timestamps come from [now] (injected — commonMain has no platform clock; tests pass a
 * deterministic stub).
 *
 * State is atomic: mutators write the new snapshot to disk ([writeFile]) and only commit it to
 * fields after success. A failed write leaves the cache, `meta`, and `dataKey` unchanged, so the
 * file never goes out of sync. All public methods are synchronized (the vault is called from UI
 * coroutines and potentially background sync) via the multiplatform [SynchronizedObject]. Passed-in
 * passwords are wiped.
 */
class FileVault(
    private val path: Path,
    private val crypto: VaultCrypto,
    private val deviceId: String,
    private val fileSystem: FileSystem,
    // Hook to tighten the tmp file to 0600 before atomicMove (desktop passes PrivateConfig.harden).
    // Defaults to no-op: FakeFileSystem tests and Android (filesDir is UID-private) leave permissions alone.
    // Kept before [now] so [now] stays the last parameter (trailing-lambda call sites).
    private val harden: (Path) -> Unit = {},
    private val now: () -> String,
) : Vault {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private var dataKey: DataKey? = null
    private var meta: VaultMeta? = null
    private val records = mutableListOf<VaultRecord>()

    // Records this app version couldn't parse (unfamiliar RecordType/ConnectionType after another
    // device upgraded, or some future format). Kept as raw JSON verbatim and re-appended on every file
    // rewrite — otherwise a downgraded/older client would silently drop data synced by a newer version.
    // One such record does not make the whole vault Corrupted (see [parseBody]).
    private val unknownRecords = mutableListOf<JsonElement>()

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    // "Local mutation" signal for live-sync (see [Vault.localChanges]). replay=0 + DROP_OLDEST: the
    // signal is idempotent (only the fact of a change matters, not the count), and tryEmit with no
    // subscriber must neither block the mutator nor throw. Emitted AFTER a successful commit (after
    // writeFile), regardless of whether the sync coordinator is subscribed.
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val localChanges: Flow<Unit> = _localChanges

    override fun exists(): Boolean = fileSystem.exists(path)

    override val isUnlocked: Boolean get() = synchronized(lock) { dataKey != null }

    override fun create(password: CharArray): Unit = synchronized(lock) {
        try {
            val salt = crypto.newSalt()
            val masterKey = crypto.deriveMasterKey(password, salt)
            val freshDataKey = crypto.newDataKey()
            val wrapped = crypto.wrapDataKey(masterKey, freshDataKey)
            masterKey.bytes.fill(0)
            val newMeta = VaultMeta(FORMAT_VERSION, salt, wrapped)
            unknownRecords.clear() // fresh vault from scratch — don't carry over unrecognized records from elsewhere
            try {
                writeFile(newMeta, emptyList())
            } catch (e: Throwable) {
                freshDataKey.bytes.fill(0) // write failed — key doesn't go anywhere
                throw e
            }
            dataKey?.bytes?.fill(0) // don't orphan the old key on repeated create
            dataKey = freshDataKey
            meta = newMeta
            records.clear()
        } finally {
            password.fill(' ')
        }
    }

    override fun createWithDataKey(dataKey: DataKey): Unit = synchronized(lock) {
        // meta.wrappedDataKey is intentionally empty: this vault's key lives elsewhere (a TEAM
        // record in the account vault); the password-based unlock() path isn't used for these files.
        val newMeta = VaultMeta(FORMAT_VERSION, crypto.newSalt(), ByteArray(0))
        unknownRecords.clear()
        try {
            writeFile(newMeta, emptyList())
        } catch (e: Throwable) {
            dataKey.bytes.fill(0) // write failed — don't keep the passed-in key in memory
            throw e
        }
        this.dataKey?.bytes?.fill(0)
        this.dataKey = dataKey
        meta = newMeta
        records.clear()
    }

    override fun unlock(password: CharArray): UnlockResult = synchronized(lock) {
        try {
            val body = runCatching {
                parseBody(fileSystem.read(path) { readUtf8() })
            }.getOrElse { return@synchronized UnlockResult.Corrupted }
            val masterKey = crypto.deriveMasterKey(password, body.meta.salt)
            val unwrapped = crypto.unwrapDataKey(masterKey, body.meta.wrappedDataKey)
            masterKey.bytes.fill(0)
            if (unwrapped == null) return@synchronized UnlockResult.WrongPassword
            dataKey?.bytes?.fill(0) // repeated unlock must not orphan the previous key
            dataKey = unwrapped
            adoptBody(body)
            UnlockResult.Success
        } finally {
            password.fill(' ')
        }
    }

    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = synchronized(lock) {
        val body = runCatching {
            parseBody(fileSystem.read(path) { readUtf8() })
        }.getOrElse {
            dataKey.bytes.fill(0) // nothing to assign — don't leave the passed-in key dangling in memory
            return@synchronized UnlockResult.Corrupted
        }
        this.dataKey?.bytes?.fill(0) // repeated unlock must not orphan the previous key
        this.dataKey = dataKey // assign the passed-in key — the caller does not wipe it (see contract)
        adoptBody(body)
        UnlockResult.Success
    }

    override fun exportDataKey(): DataKey? = synchronized(lock) {
        dataKey?.let { DataKey(it.bytes.copyOf()) } // copy: the caller can wipe it without touching the live key
    }

    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = synchronized(lock) {
        try {
            val key = dataKey
            check(key != null) { "vault is locked" }
            // Same key (primary device reconnecting with its own) → don't rewrite meta, or the vault
            // password would be silently changed. Don't keep an extra key copy in memory.
            // Constant-time comparison: contentEquals bails on the first mismatch, and its timing
            // would let an attacker brute-force the key byte by byte.
            if (constantTimeEquals(newDataKey.bytes, key.bytes)) {
                newDataKey.bytes.fill(0)
                return@synchronized false
            }
            val newSalt = crypto.newSalt()
            val newMaster = crypto.deriveMasterKey(password, newSalt)
            val newWrapped = crypto.wrapDataKey(newMaster, newDataKey)
            newMaster.bytes.fill(0)
            val newMeta = VaultMeta(FORMAT_VERSION, newSalt, newWrapped)
            // Records stay as-is: synced ones will arrive under the new key; local ones (under the
            // old key) become unreadable. Commit happens after persist — a failed write leaves fields untouched.
            writeFile(newMeta, records.toList())
            key.bytes.fill(0) // old key no longer needed
            dataKey = newDataKey
            meta = newMeta
            true
        } finally {
            password.fill(' ')
        }
    }

    override fun lock(): Unit = synchronized(lock) {
        dataKey?.bytes?.fill(0)
        dataKey = null
        meta = null
        records.clear()
        unknownRecords.clear()
    }

    override fun reset(): Unit = synchronized(lock) {
        dataKey?.bytes?.fill(0)
        dataKey = null
        // wrappedDataKey is the key wrapped under the master password (ciphertext, useless without the
        // password); on irreversible reset, wipe it too so no key material lingers in the heap.
        meta?.wrappedDataKey?.fill(0)
        meta = null
        records.clear()
        unknownRecords.clear()
        // mustExist=false: reset is idempotent and must not fail on an already-missing/broken file.
        fileSystem.delete(path, mustExist = false)
    }

    override fun records(): List<VaultRecord> = synchronized(lock) {
        requireUnlocked()
        records.toList()
    }

    override fun syncMeta(): SyncMeta? = synchronized(lock) {
        val m = meta ?: return@synchronized null // meta exists only on an unlocked vault
        SyncMeta(m.salt.copyOf(), m.wrappedDataKey.copyOf()) // copies: the caller is free to wipe them
    }

    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = synchronized(lock) {
        val key = requireUnlocked()
        val currentMeta = session()
        val working = records.toMutableList()
        val applied = mutableListOf<VaultRecord>()
        val rejected = mutableListOf<VaultRecord>()
        for (r in remote) {
            val index = working.indexOfFirst { it.id == r.id }
            val local = if (index >= 0) working[index] else null
            // LWW: higher version wins; on a tie, the lexicographically larger deviceId wins.
            val wins = local == null ||
                r.version > local.version ||
                (r.version == local.version && r.deviceId > local.deviceId)
            if (!wins) continue
            // Trial-decrypt before storing: the AAD binds the metadata the record claims, so a
            // compromised server can't roll a record back by replaying an old genuine ciphertext
            // with a bumped version, forge a tombstone from a live blob, or replace a readable
            // record with garbage. Records the current key can't authenticate are rejected, and
            // the local record (if any) survives.
            if (!authenticates(key, r)) {
                rejected += r
                continue
            }
            if (index >= 0) working[index] = r else working += r
            applied += r
        }
        if (applied.isNotEmpty()) {
            commit(currentMeta, working)
        }
        MergeResult(applied, rejected)
    }

    override fun openPayload(id: String): ByteArray? = synchronized(lock) {
        val key = requireUnlocked()
        val record = records.firstOrNull { it.id == id } ?: return@synchronized null
        // A tombstone doesn't return a payload: the deleted record's blob is kept for sync but never exposed.
        if (record.deleted) return@synchronized null
        openRecord(key, record)
    }

    override fun put(id: String, type: RecordType, payload: ByteArray): Unit = synchronized(lock) {
        val key = requireUnlocked()
        val currentMeta = session()
        val index = records.indexOfFirst { it.id == id }
        val version = if (index >= 0) records[index].version + 1 else 1L
        val at = now()
        val blob = crypto.seal(key, payload, recordAad(id, type, version, deviceId, deleted = false, updatedAt = at))
        val record = VaultRecord(id, type, version, at, deviceId, deleted = false, blob = blob)
        val updated = records.toMutableList().also {
            if (index >= 0) it[index] = record else it += record
        }
        commit(currentMeta, updated)
        _localChanges.tryEmit(Unit) // commit succeeded → wake live-sync push
    }

    override fun remove(id: String): Unit = synchronized(lock) {
        val key = requireUnlocked()
        val currentMeta = session()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val current = records[index]
        if (current.deleted) return@synchronized
        // The tombstone is re-sealed (not copied from the live blob): the AAD must cover the bumped
        // version and deleted=true, or other devices couldn't tell this deletion from one forged by
        // the sync server. The payload is empty — sync only needs an authenticated blob, and the
        // deleted secret shouldn't outlive the record. Works even when the old blob is unreadable
        // under the current key (adoptDataKey leftovers).
        val version = current.version + 1
        val at = now()
        val blob = crypto.seal(key, ByteArray(0), recordAad(current.id, current.type, version, current.deviceId, deleted = true, updatedAt = at))
        val tombstone = current.copy(deleted = true, version = version, updatedAt = at, blob = blob)
        val updated = records.toMutableList().also { it[index] = tombstone }
        commit(currentMeta, updated)
        _localChanges.tryEmit(Unit) // local delete → wake live-sync push
    }

    override fun compact(ids: List<String>): Unit = synchronized(lock) {
        requireUnlocked()
        if (ids.isEmpty()) return@synchronized
        val currentMeta = session()
        val drop = ids.toHashSet()
        // Only removes TOMBSTONES from the list: a live (newer, not-yet-pushed) record with the same
        // id is kept — the next push will carry it, or unpushed data would be lost.
        val updated = records.filterNot { it.id in drop && it.deleted }
        if (updated.size == records.size) return@synchronized // nothing to compact — leave the file untouched
        commit(currentMeta, updated)
    }

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = synchronized(lock) {
        try {
            val currentMeta = meta
            val key = dataKey
            check(currentMeta != null && key != null) { "vault is locked" }
            val oldMaster = crypto.deriveMasterKey(oldPassword, currentMeta.salt)
            val verified = crypto.unwrapDataKey(oldMaster, currentMeta.wrappedDataKey)
            oldMaster.bytes.fill(0)
            if (verified == null) return@synchronized false
            verified.bytes.fill(0) // only needed to verify the old password
            val newSalt = crypto.newSalt()
            val newMaster = crypto.deriveMasterKey(newPassword, newSalt)
            val newWrapped = crypto.wrapDataKey(newMaster, key)
            newMaster.bytes.fill(0)
            val newMeta = currentMeta.copy(salt = newSalt, wrappedDataKey = newWrapped)
            writeFile(newMeta, records.toList()) // on failure meta is not swapped in
            meta = newMeta
            true
        } finally {
            oldPassword.fill(' ')
            newPassword.fill(' ')
        }
    }

    override fun verifyPassword(password: CharArray): Boolean = synchronized(lock) {
        try {
            // Verified against the metadata of the open session (as changePassword verifies the old
            // password): the vault must be unlocked. dataKey/records are untouched — this only verifies
            // identity. dataKey == null duplicates the meta == null condition (lock() clears both), but
            // makes the "unlocked vault only" invariant explicit and robust to a future meta-only read.
            val currentMeta = meta ?: return@synchronized false
            if (dataKey == null) return@synchronized false
            val master = crypto.deriveMasterKey(password, currentMeta.salt)
            val verified = crypto.unwrapDataKey(master, currentMeta.wrappedDataKey)
            master.bytes.fill(0)
            if (verified == null) return@synchronized false
            verified.bytes.fill(0) // only needed for verification — don't keep the unwrapped key in memory
            true
        } finally {
            password.fill(' ')
        }
    }

    private fun requireUnlocked(): DataKey =
        dataKey ?: throw IllegalStateException("vault is locked")

    /** [meta] of the open session; throws if the "unlocked ⇒ meta present" invariant is broken. */
    private fun session(): VaultMeta = meta ?: error("unlocked vault has no metadata")

    /** Persist-then-commit: atomically write the snapshot and update the record cache only after success. */
    private fun commit(meta: VaultMeta, updated: List<VaultRecord>) {
        writeFile(meta, updated) // on failure the cache is untouched
        records.clear()
        records.addAll(updated)
    }

    /**
     * Atomically writes the vault snapshot (tmp + move — [atomicWriteUtf8]). A pure function of its
     * arguments — doesn't read or write fields (except [unknownRecords], see below). If the write/move
     * fails, the exception propagates and fields stay unchanged (commit happens after persist): no
     * data is lost, the error is visible upstream. [harden] tightens tmp permissions before the target
     * swap, so the secrets file itself — not just the directory — stays private.
     */
    private fun writeFile(meta: VaultMeta, records: List<VaultRecord>) {
        // Unrecognized records are re-appended verbatim so this app version doesn't drop data it
        // doesn't understand (see [unknownRecords]). The file shape matches VaultFileBody
        // ({meta, records}), so old and new clients read it without a format change.
        val body = buildJsonObject {
            put("meta", json.encodeToJsonElement(VaultMeta.serializer(), meta))
            put("records", buildJsonArray {
                records.forEach { add(json.encodeToJsonElement(VaultRecord.serializer(), it)) }
                unknownRecords.forEach { add(it) }
            })
        }
        atomicWriteUtf8(fileSystem, path, json.encodeToString(JsonObject.serializer(), body), harden)
    }

    /** Assigns a parsed file snapshot to the session fields (shared by [unlock]/[unlockWithDataKey]). */
    private fun adoptBody(body: ParsedBody) {
        meta = body.meta
        records.clear()
        records.addAll(body.records)
        unknownRecords.clear()
        unknownRecords.addAll(body.unknown)
    }

    /**
     * Parses the vault file tolerantly: `meta` and the overall structure are required (otherwise the
     * file really is broken — the exception surfaces as [UnlockResult.Corrupted]), but each record is
     * decoded independently. A record this version doesn't understand (unfamiliar enum value, etc.)
     * doesn't fail the whole file — it's kept raw in [ParsedBody.unknown] and survives rewrites (see
     * [writeFile]).
     */
    private fun parseBody(text: String): ParsedBody {
        val root = json.parseToJsonElement(text).jsonObject
        val parsedMeta = json.decodeFromJsonElement(VaultMeta.serializer(), root.getValue("meta"))
        val known = mutableListOf<VaultRecord>()
        val unknown = mutableListOf<JsonElement>()
        (root["records"] as? JsonArray)?.forEach { el ->
            runCatching { json.decodeFromJsonElement(VaultRecord.serializer(), el) }
                .onSuccess { known += it }
                .onFailure { unknown += el }
        }
        return ParsedBody(parsedMeta, known, unknown)
    }

    private class ParsedBody(
        val meta: VaultMeta,
        val records: List<VaultRecord>,
        val unknown: List<JsonElement>,
    )

    /**
     * Decrypts a record's blob against the metadata it claims ([recordAad]), falling back to the
     * legacy pre-metadata-binding AAD ([legacyRecordAad]) for blobs sealed by older clients (or
     * still stored on the sync server). `null` means the blob doesn't authenticate under either.
     */
    private fun openRecord(key: DataKey, record: VaultRecord): ByteArray? =
        crypto.open(key, record.blob, recordAad(record))
            ?: crypto.open(key, record.blob, legacyRecordAad(record.id, record.type))

    /** True when [record]'s blob authenticates; the decrypted copy is wiped immediately. */
    private fun authenticates(key: DataKey, record: VaultRecord): Boolean {
        val plaintext = openRecord(key, record) ?: return false
        plaintext.fill(0)
        return true
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Unit Separator (U+001F) between AAD fields. Explicit escape — control byte 0x1F. */
private const val AAD_SEP = "\u001F"

/**
 * Record AAD, format 2: binds the blob to ALL plaintext metadata (`2‖id‖type‖version‖deviceId‖
 * deleted‖updatedAt`, [AAD_SEP]-separated), so a sync server can't replay an old genuine
 * ciphertext under a bumped version, flip `deleted`, or move a blob between slots — the AEAD tag
 * check fails ([FileVault.mergeRemote] rejects such records). Unambiguous without escaping:
 * honest fields never contain U+001F, so the serialized string has exactly six separators and a
 * forged tuple with embedded separators can't collide with a genuine one. `internal` for tests.
 */
internal fun recordAad(
    id: String,
    type: RecordType,
    version: Long,
    deviceId: String,
    deleted: Boolean,
    updatedAt: String,
): ByteArray =
    "2$AAD_SEP$id$AAD_SEP${type.name}$AAD_SEP$version$AAD_SEP$deviceId$AAD_SEP${if (deleted) "1" else "0"}$AAD_SEP$updatedAt"
        .encodeToByteArray()

/** [recordAad] for the metadata an existing record claims. */
internal fun recordAad(record: VaultRecord): ByteArray =
    recordAad(record.id, record.type, record.version, record.deviceId, record.deleted, record.updatedAt)

/**
 * Legacy record AAD (pre-0.1.3): `id` + [AAD_SEP] + `type.name` — metadata wasn't covered. Kept as
 * a read/merge fallback so existing vaults and records already on sync servers stay readable.
 * Never used for sealing: every rewrite (put/remove) upgrades the blob to [recordAad]. Residual
 * risk, accepted: a blob sealed under this AAD can still be replayed with tampered
 * version/deleted until its record is next edited.
 */
internal fun legacyRecordAad(id: String, type: RecordType): ByteArray =
    "$id$AAD_SEP${type.name}".encodeToByteArray()
