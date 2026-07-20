package app.skerry.shared.vault

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Result of unlocking a [Vault]: success, wrong password, or an unreadable file. */
sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassword : UnlockResult
    data object Corrupted : UnlockResult
}

/**
 * Plaintext authentication material for self-hosted sync: the masterKey derivation salt and the
 * dataKey wrapper. Not secrets individually (the salt is public, the wrapper is ciphertext), but
 * they let another device derive the same masterKey from the master password and unwrap the
 * dataKey. Bytes are copies; the caller is free to wipe them.
 */
class SyncMeta internal constructor(val kdfSalt: ByteArray, val wrappedDataKey: ByteArray)

/**
 * Result of [Vault.mergeRemote]: [applied] are the LWW winners that were stored; [rejected] are
 * LWW winners whose blob failed AEAD authentication against their claimed metadata — a tampered
 * or replayed record from a compromised sync server (or garbage). Rejected records are NOT stored:
 * the local record (if any) survives untouched. Kept separate from [applied] so callers can
 * surface the rejection instead of silently dropping data.
 */
data class MergeResult(val applied: List<VaultRecord>, val rejected: List<VaultRecord>) {
    companion object {
        val EMPTY = MergeResult(emptyList(), emptyList())
    }
}

/**
 * Local encrypted record store (hosts/keys/identity). Key hierarchy and format: Argon2id → masterKey → dataKey, XChaCha20-Poly1305; crypto behind
 * [VaultCrypto]. Unlike [app.skerry.shared.host.HostStore], the vault has a lifecycle: while
 * locked, `dataKey` isn't in memory and any CRUD call throws [IllegalStateException].
 *
 * `dataKey` is never exposed: CRUD works with plaintext payloads, encryption/decryption with AAD
 * bound to the record's metadata (see [VaultRecord]) happens internally. The platform implementation is file-based
 * ([app.skerry.shared.vault.FileVault] on desktop). The implementation wipes passwords passed in.
 */
interface Vault {

    /**
     * Runs [block] under the vault's internal lock as a single atomic transaction: a composite
     * read-modify-write operation (e.g. reordering hosts) won't interleave with a concurrent
     * [mergeRemote] from background sync, which would otherwise let a reorder computed from a stale
     * snapshot overwrite a just-merged update. The lock is reentrant — nested CRUD calls inside
     * [block] re-acquire it. Default (fakes/single-threaded tests) just calls [block].
     */
    fun <T> transaction(block: () -> T): T = block()

    /** Whether the vault file already exists (to choose "create" vs "unlock" screen). */
    fun exists(): Boolean

    /** Whether the vault is unlocked (`dataKey` in memory). */
    val isUnlocked: Boolean

    /**
     * Creates a new vault from scratch (salt + random dataKey + wrapper under the master
     * password), writes an empty file. The vault is unlocked after the call. Overwrites an
     * existing file — the caller checks [exists] beforehand.
     */
    fun create(password: CharArray)

    /**
     * Creates a vault with a GIVEN `dataKey` (Teams team-vault: dataKey = teamKey, stored as a
     * [RecordType.TEAM] record in the account vault, not wrapped in this file's meta). The vault is
     * unlocked after the call; open it later via [unlockWithDataKey]. The implementation takes
     * ownership of the given key (the caller doesn't wipe it — same contract as [unlockWithDataKey]).
     * The default throws: password vaults don't need this method.
     */
    fun createWithDataKey(dataKey: DataKey): Unit =
        throw UnsupportedOperationException("createWithDataKey is only supported by file vaults")

    /** Unlocks an existing vault; see [UnlockResult]. */
    fun unlock(password: CharArray): UnlockResult

    /**
     * Unlocks with the same `dataKey`, bypassing the master password — the biometrics path.
     * `dataKey` typically comes from [BiometricKeyStore.unwrap] (the `vault.bio` wrapper), wrapped
     * under the device's `bioKey`. The implementation **takes ownership** of the given [dataKey]
     * (the caller doesn't wipe it) and loads records from the file; [UnlockResult.Corrupted] if the
     * file can't be read. The method deliberately doesn't verify that `dataKey` is correct (there's
     * no master key to check against): a wrong key simply fails to open records (AEAD failure in
     * [openPayload]). Use only from the trusted biometrics path.
     */
    fun unlockWithDataKey(dataKey: DataKey): UnlockResult

    /**
     * Exports a **copy** of the current `dataKey` to enable biometrics (it gets wrapped under
     * `bioKey` and stored in `vault.bio`). `null` if the vault is locked. Returns a [DataKey] whose
     * bytes are `internal` — the UI can't read them; a copy so the caller can wipe it after
     * wrapping without touching the live key. The only sanctioned way to get `dataKey` out; keep the
     * result minimal and wipe it immediately (`bytes.fill(0)` is only accessible to `shared` code).
     */
    fun exportDataKey(): DataKey?

    /**
     * Adopts the account key onto disk (Phase A sync): if [newDataKey] differs from
     * the current one, rewraps it under [password] (fresh salt) and rewrites the metadata+file — the
     * device then unlocks the vault with this password and reads synced records even after a
     * restart, without signing in again. If the key matches the current one (the primary device
     * reconnecting with its own key) it's a **no-op**: metadata and password are left untouched to
     * avoid accidentally changing the vault password. Existing records are left as-is (they become
     * unreadable under the old key — this is local data of the joining device; synced records arrive
     * again via pull). Requires an unlocked vault. The implementation wipes [password] and takes
     * ownership of [newDataKey]. Returns `true` if the key was adopted (changed), `false` otherwise.
     */
    fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean

    /**
     * Re-encrypts every record (live and tombstone) under [newKey] and switches the active key to
     * it — a teamKey rotation (a member was removed, so future writes must use a key they don't
     * have). Each record's `version` is bumped by one and re-sealed with this device's id, so the
     * re-encrypted copy wins LWW and overwrites the server's old-key blob on the next sync; other
     * members that adopt [newKey] then decrypt it. Records unreadable under the current key are left
     * untouched (can't re-seal what we can't decrypt). Requires an unlocked vault; takes ownership of
     * [newKey]. Returns `true` on success.
     *
     * The default is not a silent no-op: because the method takes ownership of [newKey], a vault that
     * can't rotate would otherwise leak the key AND swallow the rotation without error (the team would
     * adopt a key nobody re-encrypted under). So the default wipes [newKey] and throws — only file
     * vaults back a rotation path; any other implementation must opt in explicitly.
     */
    fun rekeyRecords(newKey: DataKey): Boolean {
        newKey.bytes.fill(0)
        throw UnsupportedOperationException("rekeyRecords is only supported by file vaults")
    }

    /** Locks: wipes `dataKey` from memory. After this, [isUnlocked] == false. */
    fun lock()

    /**
     * Irreversibly resets the vault: wipes `dataKey`/metadata/records from memory and **deletes the
     * file** from disk. After the call, [exists] == false and [isUnlocked] == false — the vault
     * returns to its initial "not yet created" state.
     *
     * This is the emergency exit for a forgotten master password or a corrupted file: zero-knowledge
     * doesn't allow recovery, so the only alternative to a dead end is wiping everything and
     * starting over (Bitwarden/1Password model; deletion is irreversible, no backup is kept). Stored
     * secrets are lost; external data outside the vault file (host profiles, known_hosts) is outside
     * this contract — the caller cleans it up. Biometrics (`vault.bio`) is also removed by the
     * caller: the vault doesn't know about it. Idempotent: calling again / no file present is a no-op.
     */
    fun reset()

    /**
     * Stream of notifications about LOCAL vault mutations ([put]/[remove]) — no content, just a
     * "user changed something" signal. The sync coordinator subscribes, debounces, and pushes the
     * change to the server (live-sync: an edit on one device reaches others on its
     * own). NOT emitted on [mergeRemote]/[compact]: these are incoming/maintenance operations, and
     * re-pushing them would be redundant (LWW would reject it anyway) — this way a pull→merge chain
     * doesn't loop back into a push. Empty by default (fakes/implementations without sync); the file
     * vault overrides it.
     */
    val localChanges: Flow<Unit> get() = emptyFlow()

    /** Metadata for all records, including tombstones (`deleted=true`); the caller filters. */
    fun records(): List<VaultRecord>

    /**
     * The masterKey derivation salt and dataKey wrapper for sync authentication. `null` if the
     * vault is locked. From these the caller (knowing the master password) derives the
     * authKey/SRP verifier and can unwrap the dataKey on a new device — see [SyncMeta].
     */
    fun syncMeta(): SyncMeta?

    /**
     * Merges records from sync using the LWW rule: for each, the
     * one with the greater (`version`, then lexicographic `deviceId`) wins; otherwise the local one
     * stays. Before a winner is stored its blob is trial-decrypted against the metadata it claims
     * (the AAD binds id/type/version/deviceId/deleted/updatedAt): a blob that doesn't authenticate —
     * a replayed ciphertext with a bumped version, a forged tombstone, garbage — is rejected and the
     * local record survives (see [MergeResult]). Winners that authenticate are stored **as-is**
     * (blob/version/deviceId/deleted verbatim) — version isn't bumped, the payload isn't
     * re-encrypted, so Lamport counters stay consistent across devices.
     * Requires an unlocked vault (metadata is needed for the atomic write).
     */
    fun mergeRemote(remote: List<VaultRecord>): MergeResult

    /** Decrypted record payload; `null` if the record doesn't exist, is deleted (tombstone), or the blob fails AEAD. */
    fun openPayload(id: String): ByteArray?

    /**
     * Upsert: seals [payload] under `dataKey` (AAD binds the full record metadata — see
     * [VaultRecord]) and stores a record with this [id]/[type], bumping `version` and updating
     * `updatedAt`.
     */
    fun put(id: String, type: RecordType, payload: ByteArray)

    /**
     * Soft-deletes a record (tombstone): `deleted=true`, `version++`. The tombstone blob is
     * re-sealed with an **empty** payload so its bumped version and `deleted=true` are covered by
     * the AEAD tag (other devices authenticate the deletion) and the deleted secret doesn't linger
     * in the file. Unknown id is a no-op.
     */
    fun remove(id: String)

    /**
     * Physically forgets TOMBSTONES by id (compaction) — removes them from storage for good, without
     * a new tombstone and without bumping `version`, unlike [remove]. Called by sync for tombstones
     * the server has marked fully propagated (every device on the account has read them): otherwise
     * the client would keep re-pushing old tombstones forever and they'd resurrect on the server after
     * purge. Removes ONLY records with `deleted=true`: if a live (newer, not yet sent) version exists
     * locally for the same id, it's left untouched, or un-pushed data would be lost. Unknown id is a
     * no-op. No-op by default (fakes/implementations without sync); the file vault overrides it.
     * Requires an unlocked vault.
     */
    fun compact(ids: List<String>) {}

    /**
     * Changes the master password: rewraps the same `dataKey` under the new password (records aren't
     * re-encrypted). `false` if [oldPassword] is wrong. Requires an unlocked vault.
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean

    /**
     * Verifies the master password **without changing state**: derives masterKey from the current
     * salt and unwraps `wrappedDataKey`. `true` means the password is correct. Doesn't touch the
     * session (`dataKey` isn't reissued, records aren't reread); on a locked vault, `false` (no
     * metadata to check against). Used to re-confirm identity before a sensitive action — copying a
     * password to the clipboard — without unlocking again. The password is wiped.
     */
    fun verifyPassword(password: CharArray): Boolean

    /**
     * Re-wrap the CURRENT dataKey under [password], leaving the key and the records alone: the vault
     * starts unlocking with [password] and nothing else changes. Requires an unlocked vault; `false` if
     * the vault is locked or the implementation doesn't support it.
     *
     * Unlike [changePassword] this does not verify an old password — the caller must have established the
     * user's intent by other means. The one such caller is the sync connect flow (issue #28), which needs
     * the vault to end up on the account password after the user confirmed exactly that, in the case
     * [adoptDataKey] refuses to help with: the account key already IS this vault's key, so there is no key
     * to adopt, only a wrap to redo. The password is wiped.
     */
    fun rewrapUnder(password: CharArray): Boolean {
        password.fill(' ')
        return false
    }
}
