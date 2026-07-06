package app.skerry.shared.vault

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_ABYTES
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_KEYBYTES
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.box.BoxCorruptedOrTamperedDataException
import com.ionspin.kotlin.crypto.box.crypto_box_PUBLICKEYBYTES
import com.ionspin.kotlin.crypto.box.crypto_box_SEALBYTES
import com.ionspin.kotlin.crypto.box.crypto_box_SECRETKEYBYTES
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_SALTBYTES
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Initializes native libsodium; must run before creating/using [IonspinVaultCrypto]. Idempotent:
 * calling it again in an already-initialized process is a no-op. This matters on Android/Native,
 * where `LibsodiumInitializer.initialize()` throws on a repeated `sodium_init` (return code 1 =
 * "already initialized"), which used to crash Activity recreation (rotation/return from background).
 * Keeps ionspin an internal core detail — the app entry point calls this, not ionspin directly (see
 * [IonspinVaultCrypto]).
 */
suspend fun initializeVaultCrypto() {
    if (!LibsodiumInitializer.isInitialized()) {
        LibsodiumInitializer.initialize()
    }
}

/**
 * Single [VaultCrypto] implementation on ionspin multiplatform-crypto-libsodium-bindings — same
 * code for desktop (JVM) and Android. Follows the key hierarchy from `docs/skerry-sync-design.md`:
 * Argon2id(m=64MiB, t=3) → masterKey; XChaCha20-Poly1305 with a 24-byte nonce prefix for the
 * dataKey wrapper and for each record.
 *
 * Operations are stateless and thread-safe. libsodium requires async initialization
 * ([LibsodiumInitializer.initialize], suspend) before first use — done by the app entry point;
 * every operation here is guarded by a cheap [requireInitialized] check.
 *
 * Zero-knowledge limitation versus the earlier desktop lazysodium implementation: ionspin only
 * accepts the password as a `String` ([PasswordHash.pwhash]), so the input [CharArray] inevitably
 * produces an immutable string that can't be wiped (lives until GC). Its lifetime is minimized (a
 * local variable inside [deriveMasterKey]); wiping the [CharArray] itself remains the caller's job,
 * as before (see the [VaultCrypto.deriveMasterKey] contract).
 */
@OptIn(ExperimentalUnsignedTypes::class) // ionspin passes/returns keys and blobs as UByteArray
class IonspinVaultCrypto : VaultCrypto {

    override fun newSalt(): ByteArray {
        requireInitialized()
        return LibsodiumRandom.buf(crypto_pwhash_SALTBYTES).toByteArray()
    }

    override fun deriveSyncSalt(accountId: String): ByteArray {
        requireInitialized()
        // BLAKE2b(accountId) truncated to the Argon2id salt length — deterministic and collision-free
        // for reasonable accountIds; the same on every device (design §1: "salt = accountId").
        return GenericHash.genericHash(
            message = accountId.encodeToByteArray().toUByteArray(),
            requestedHashLength = crypto_pwhash_SALTBYTES,
        ).toByteArray()
    }

    override fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey {
        requireInitialized()
        require(salt.size == crypto_pwhash_SALTBYTES) { "salt must be $crypto_pwhash_SALTBYTES bytes" }
        // Regression versus a CharArray-based path: ionspin has no byte/CharArray pwhash variant, so
        // the password string is unavoidable and isn't wiped (see class KDoc).
        val passwordString = password.concatToString()
        val key = PasswordHash.pwhash(
            outputLength = KEY_BYTES,
            password = passwordString,
            salt = salt.toUByteArray(),
            opsLimit = OPS_LIMIT,
            memLimit = MEM_LIMIT,
            algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13,
        )
        // pwhash's UByteArray output is an intermediate key copy: wipe it after taking a ByteArray
        // copy, so only one masterKey instance remains in the heap (wiped by the MasterKey owner).
        return MasterKey(key.toByteArray()).also { key.fill(0u) }
    }

    override fun newDataKey(): DataKey {
        requireInitialized()
        return DataKey(AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfKeygen().toByteArray())
    }

    override fun deriveAuthKey(masterKey: MasterKey): ByteArray {
        requireInitialized()
        require(masterKey.bytes.size == KEY_BYTES) { "masterKey must be $KEY_BYTES bytes" }
        // libsodium crypto_kdf (BLAKE2b): a subkey from masterKey in the AUTH_CONTEXT domain. The
        // context separates authKey from any other subkey of the same masterKey (domain isolation).
        val masterCopy = masterKey.bytes.toUByteArray() // intermediate key copy — wiped below
        val subKey = Kdf.deriveFromKey(AUTH_SUBKEY_ID, KEY_BYTES, AUTH_CONTEXT, masterCopy)
        masterCopy.fill(0u)
        return subKey.toByteArray().also { subKey.fill(0u) }
    }

    override fun newTransferKey(): ByteArray {
        requireInitialized()
        return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfKeygen().toByteArray()
    }

    override fun sealDataKeyForTransfer(dataKey: DataKey, transferKey: ByteArray): ByteArray =
        aeadSeal(transferKey, dataKey.bytes, TRANSFER_AAD)

    override fun openTransferredDataKey(transferKey: ByteArray, envelope: ByteArray): DataKey? =
        aeadOpen(transferKey, envelope, TRANSFER_AAD)?.let { DataKey(it) }

    override fun wrapDataKey(masterKey: MasterKey, dataKey: DataKey): ByteArray =
        aeadSeal(masterKey.bytes, dataKey.bytes, WRAP_AAD)

    override fun unwrapDataKey(masterKey: MasterKey, wrapped: ByteArray): DataKey? =
        aeadOpen(masterKey.bytes, wrapped, WRAP_AAD)?.let { DataKey(it) }

    override fun seal(dataKey: DataKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aeadSeal(dataKey.bytes, plaintext, associatedData)

    override fun open(dataKey: DataKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray? =
        aeadOpen(dataKey.bytes, ciphertext, associatedData)

    override fun newSharingKeyPair(): SharingKeyPair {
        requireInitialized()
        val pair = Box.keypair()
        // UByteArray outputs are intermediate copies: wiped after taking ByteArray copies.
        return SharingKeyPair(pair.publicKey.toByteArray(), pair.secretKey.toByteArray()).also {
            pair.publicKey.fill(0u)
            pair.secretKey.fill(0u)
        }
    }

    override fun sharingKeyPairFromBytes(publicKey: ByteArray, secretKey: ByteArray): SharingKeyPair {
        require(publicKey.size == crypto_box_PUBLICKEYBYTES) { "publicKey must be $crypto_box_PUBLICKEYBYTES bytes" }
        require(secretKey.size == crypto_box_SECRETKEYBYTES) { "secretKey must be $crypto_box_SECRETKEYBYTES bytes" }
        return SharingKeyPair(publicKey.copyOf(), secretKey.copyOf())
    }

    override fun sealForRecipient(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray {
        requireInitialized()
        require(recipientPublicKey.size == crypto_box_PUBLICKEYBYTES) {
            "recipientPublicKey must be $crypto_box_PUBLICKEYBYTES bytes"
        }
        return Box.seal(plaintext.toUByteArray(), recipientPublicKey.toUByteArray()).toByteArray()
    }

    override fun openSealedEnvelope(keyPair: SharingKeyPair, envelope: ByteArray): ByteArray? {
        requireInitialized()
        // A short/broken envelope is an ordinary failure (null), not a throw: the envelope is
        // delivered by the server, an untrusted source (same logic as in aeadOpen).
        if (envelope.size < crypto_box_SEALBYTES) return null
        val secretCopy = keyPair.secretKey.toUByteArray()
        return try {
            Box.sealOpen(envelope.toUByteArray(), keyPair.publicKey.toUByteArray(), secretCopy).toByteArray()
        } catch (e: BoxCorruptedOrTamperedDataException) {
            null
        } finally {
            secretCopy.fill(0u)
        }
    }

    /** nonce‖XChaCha20-Poly1305(key, plaintext; ad). Nonce is random — reusing the key is safe. */
    private fun aeadSeal(key: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        requireInitialized()
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        val nonce = LibsodiumRandom.buf(NPUB_BYTES)
        val keyCopy = key.toUByteArray() // intermediate key copy — wiped in finally
        try {
            val cipher = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
                message = plaintext.toUByteArray(),
                associatedData = ad.toUByteArray(),
                nonce = nonce,
                key = keyCopy,
            )
            return nonce.toByteArray() + cipher.toByteArray()
        } finally {
            keyCopy.fill(0u)
        }
    }

    /** Reverse of [aeadSeal]; `null` means an AEAD tag failure (wrong key, tampering, or wrong ad). */
    private fun aeadOpen(key: ByteArray, blob: ByteArray, ad: ByteArray): ByteArray? {
        requireInitialized()
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        // A too-short blob is treated as an ordinary AEAD failure (null), not a programming error:
        // the blob can come from an untrusted source (a sync-server record is applied by mergeRemote
        // as-is). Throwing here would fail the whole list on the first bad read — a DoS via a malicious server.
        if (blob.size < NPUB_BYTES + ABYTES) return null
        val nonce = blob.copyOfRange(0, NPUB_BYTES).toUByteArray()
        val cipher = blob.copyOfRange(NPUB_BYTES, blob.size).toUByteArray()
        // ionspin signals a tag failure with an exception, but the VaultCrypto contract expects null —
        // this is an expected, handled outcome (wrong key/password, tampering, wrong AAD).
        val keyCopy = key.toUByteArray() // intermediate key copy — wiped in finally
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = cipher,
                associatedData = ad.toUByteArray(),
                nonce = nonce,
                key = keyCopy,
            ).toByteArray()
        } catch (_: AeadCorrupedOrTamperedDataException) {
            null
        } finally {
            keyCopy.fill(0u)
        }
    }

    // check, not require: an uninitialized libsodium is an app-startup-order violation
    // (IllegalState), not a bad call argument.
    private fun requireInitialized() =
        check(LibsodiumInitializer.isInitialized()) {
            "libsodium is not initialized; call LibsodiumInitializer.initialize() at app startup"
        }

    private companion object {
        val KEY_BYTES = crypto_aead_xchacha20poly1305_ietf_KEYBYTES   // 32 = master/data key size
        val NPUB_BYTES = crypto_aead_xchacha20poly1305_ietf_NPUBBYTES // 24
        val ABYTES = crypto_aead_xchacha20poly1305_ietf_ABYTES        // 16 (Poly1305 tag)

        // Argon2id parameters from docs/skerry-sync-design.md §1 are explicit literals, not
        // libsodium presets (INTERACTIVE/MODERATE): presets pair their own t and m, and swapping a
        // literal for a named preset would silently change the strength. Parallelism p from the
        // cryptoPwHash spec is fixed at 1 — a libsodium limitation shared by all platforms.
        const val OPS_LIMIT = 3UL                     // t = 3 iterations
        const val MEM_LIMIT: Int = 64 * 1024 * 1024   // m = 64 MiB (explicit Int: guards against overflow)

        // Domain AAD for the dataKey wrapper: separates it from records (sealed with a slot AAD) so
        // the wrapper can't be substituted for a record or vice versa even if keys match.
        val WRAP_AAD = "skerry.vault.wrapped-data-key.v1".encodeToByteArray()

        // Domain AAD for the quick-pairing envelope (variant B): separates the dataKey transfer under
        // transferKey from the masterKey wrapper and from records — the envelope can't pass as another blob.
        val TRANSFER_AAD = "skerry.pairing.transfer-key.v1".encodeToByteArray()

        // authKey derivation: context is exactly 8 bytes (crypto_kdf_CONTEXTBYTES), subkey #1.
        const val AUTH_CONTEXT = "skerryau" // 8 ASCII characters
        const val AUTH_SUBKEY_ID = 1u
    }
}
