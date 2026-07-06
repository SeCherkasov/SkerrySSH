package app.skerry.shared.vault

/**
 * 256-bit key derived from the master password via Argon2id (see [VaultCrypto.deriveMasterKey]).
 * Exists only in device memory and is never serialized — only the wrapped dataKey
 * ([VaultCrypto.wrapDataKey]) leaves it. Bytes are kept `internal` so the key material can't leak
 * into the public API, logs, or accidental comparisons; [toString] deliberately doesn't print content.
 */
class MasterKey internal constructor(internal val bytes: ByteArray) {
    /**
     * Wipe the key material (`bytes.fill(0)`). Call as soon as masterKey is no longer needed: it's
     * Argon2id output, no reason to keep it in the heap longer than necessary (a heap dump/swap would
     * expose the key). `shared` code wipes its own masterKeys; this public method is for callers
     * outside the module (e.g. `SyncCoordinator`) that can't access `bytes`. Idempotent.
     */
    fun zeroize() { bytes.fill(0) }

    override fun toString(): String = "MasterKey(redacted)"
}

/**
 * 256-bit random data key. Encrypts every vault record (XChaCha20-Poly1305) and is itself stored
 * only wrapped under [MasterKey]. Changing the master password rewraps this key without
 * re-encrypting records — see `docs/skerry-sync-design.md` §1.
 */
class DataKey internal constructor(internal val bytes: ByteArray) {
    /**
     * Wipe the key material of a **copy** of dataKey (e.g. one obtained from [Vault.exportDataKey] or
     * [VaultCrypto.unwrapDataKey]). Do NOT call on a key still owned by a live vault — that would wipe
     * the working key. For copies whose lifetime the caller controls. Idempotent.
     */
    fun zeroize() { bytes.fill(0) }

    override fun toString(): String = "DataKey(redacted)"
}

/**
 * Crypto primitives for the local zero-knowledge vault. The implementation is platform-specific (on
 * JVM: libsodium via bindings), so the interface lives in the core while strength parameters
 * (Argon2id m=64MiB/t=3/p=4) and cipher (XChaCha20-Poly1305) are implementation details, not part
 * of the contract. The same key hierarchy is reused by Phase 2's E2E sync.
 *
 * Error convention: [unwrapDataKey] and [open] return `null` **only** on an AEAD tag check failure
 * (wrong key/master password or corrupted ciphertext) — an expected, handled outcome. Structurally
 * invalid input (wrong key length, a truncated blob with no room for nonce/tag) is a programming
 * error and throws.
 */
interface VaultCrypto {

    companion object {
        /** Empty AAD — no slot binding; the getter returns a fresh array (no shared mutable state). */
        val EMPTY_AAD: ByteArray get() = ByteArray(0)
    }

    /** New random salt for master-key derivation (length required by Argon2id). */
    fun newSalt(): ByteArray

    /**
     * Deterministic masterKey derivation salt for self-hosted sync — derived from [accountId]
     * (`docs/skerry-sync-design.md` §1: "salt = accountId"). The same on every device, not a secret;
     * lets a new device derive the same masterKey from one master password and unwrap the server's
     * dataKey wrapper without fetching a salt from the server. Length matches [newSalt].
     */
    fun deriveSyncSalt(accountId: String): ByteArray

    /**
     * Argon2id(password, salt) → [MasterKey]. Deliberately expensive (tens of ms or more);
     * deterministic for a given (password, salt) pair. The caller is responsible for wiping
     * [password] after the call.
     */
    fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey

    /** New random [DataKey] — created once when the vault is initialized. */
    fun newDataKey(): DataKey

    /**
     * Deterministically derives a 256-bit authKey from [masterKey] for self-hosted sync
     * authentication (`docs/skerry-sync-design.md` §1: HKDF branch masterKey → authKey → SRP
     * verifier). A separate derivation domain from the dataKey wrapper; the server never sees
     * authKey, only the SRP verifier. Deterministic for a given [masterKey].
     */
    fun deriveAuthKey(masterKey: MasterKey): ByteArray

    /**
     * New one-time transfer key for quick device pairing (variant B,
     * `docs/skerry-sync-design.md` §3): 32 random bytes (AEAD key length). Goes **only** into the
     * QR/code the user transfers to the new device visually/via camera — never reaches the server,
     * so the server's dataKey ciphertext is useless without it. Returned as raw bytes (not
     * [DataKey]) because it must serialize into the pairing payload.
     */
    fun newTransferKey(): ByteArray

    /**
     * Seals a live [dataKey] under a one-time [transferKey] for transfer to a new device (variant
     * B). The result (nonce-prefixed XChaCha20-Poly1305) is stored on the server in the pairing
     * session; only the same [transferKey], which the server never has, can unwrap it. A domain AAD
     * separates the pairing envelope from the dataKey wrapper and records. [transferKey] must be
     * the AEAD key length (see [newTransferKey]).
     */
    fun sealDataKeyForTransfer(dataKey: DataKey, transferKey: ByteArray): ByteArray

    /**
     * Unwraps the transferred dataKey on the new device using the [transferKey] from the QR/code.
     * `null` means a wrong [transferKey] or a tampered envelope (AEAD failure) — same as [unwrapDataKey].
     */
    fun openTransferredDataKey(transferKey: ByteArray, envelope: ByteArray): DataKey?

    /** Wraps [dataKey] with the master key for storage on disk/server (only ciphertext is visible). */
    fun wrapDataKey(masterKey: MasterKey, dataKey: DataKey): ByteArray

    /** Unwraps the wrapper. `null` means a wrong master password or a corrupted wrapper. */
    fun unwrapDataKey(masterKey: MasterKey, wrapped: ByteArray): DataKey?

    /**
     * Encrypts a record under [dataKey]; a random nonce is prefixed to the result.
     *
     * [associatedData] is authenticated (AEAD) but not encrypted: the caller should bind a stable
     * record-slot identifier here (e.g. `id‖type`) so the ciphertext can't be silently swapped into
     * another slot — [open] with a different AAD returns `null`. AAD is always passed explicitly (no
     * default — sealing "with no binding" can't happen silently); a deliberate absence of binding is
     * [EMPTY_AAD].
     */
    fun seal(dataKey: DataKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Decrypts a record. `null` means a wrong key, a corrupted/tampered ciphertext, **or**
     * [associatedData] that doesn't match what the record was sealed with.
     */
    fun open(dataKey: DataKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray?

    /**
     * New X25519 key pair for receiving sealed envelopes ([sealForRecipient]). The public half is
     * published on the sync server, the secret half is stored only in the vault's own record
     * (TEAM_IDENTITY) and never leaves the device in plaintext.
     */
    fun newSharingKeyPair(): SharingKeyPair

    /**
     * Restores a pair from serialized halves (a vault record on another device). Bytes are copied;
     * wiping the input arrays is the caller's job.
     */
    fun sharingKeyPairFromBytes(publicKey: ByteArray, secretKey: ByteArray): SharingKeyPair

    /**
     * Seals [plaintext] to the recipient's public key (crypto_box_seal: ephemeral X25519 pair,
     * anonymous sender). Only the owner of the secret half can open the envelope — the server that
     * delivers the invite envelope sees only ciphertext.
     */
    fun sealForRecipient(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Opens a sealed envelope with own key pair. `null` means the envelope is addressed to another
     * pair, is corrupted, or is too short (untrusted input from the server — not thrown).
     */
    fun openSealedEnvelope(keyPair: SharingKeyPair, envelope: ByteArray): ByteArray?
}

/**
 * X25519 key pair for Teams sealed envelopes. The secret half is kept `internal` for the same
 * reasons as [MasterKey.bytes]; [exportSecretKey] returns a copy only for serializing into the
 * account's own vault record (encrypted with the account's dataKey).
 */
class SharingKeyPair internal constructor(
    val publicKey: ByteArray,
    internal val secretKey: ByteArray,
) {
    /** Copy of the secret half for storing in the vault; wiping the copy is the caller's job. */
    fun exportSecretKey(): ByteArray = secretKey.copyOf()

    /** Wipe the secret half once the pair is no longer needed. Idempotent. */
    fun zeroize() {
        secretKey.fill(0)
    }

    override fun toString(): String = "SharingKeyPair(publicKey=${publicKey.size}B, secret=redacted)"
}
