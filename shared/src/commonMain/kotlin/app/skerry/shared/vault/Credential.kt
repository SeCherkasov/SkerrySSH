package app.skerry.shared.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Authentication secret in the keychain — raw material (key/password/certificate) held by
 * [Credential]. Polymorphically serialized inside a vault record's encrypted payload; `@SerialName`
 * fixes a stable wire name in the discriminator so package renames or minification (R8) don't
 * make already-written blobs unreadable. The discriminator names ("password"/"private_key"/
 * "certificate") are inherited from the former `IdentityAuth`, so secrets written before the
 * keychain/host split read without a payload migration.
 *
 * `toString` is redacted — the secret must not leak into logs/crash reports. Secrets are held as
 * `String`: on the JVM they can't be zeroed (they live on the heap until GC); an accepted
 * limitation.
 */
@Serializable
sealed interface CredentialSecret {
    /** User password. */
    @Serializable
    @SerialName("password")
    data class Password(val password: String) : CredentialSecret {
        override fun toString(): String = "Password(redacted)"
    }

    /** Private key in PEM (OpenSSH/PKCS) and an optional passphrase to decrypt it. */
    @Serializable
    @SerialName("private_key")
    data class PrivateKey(val privateKeyPem: String, val passphrase: String? = null) : CredentialSecret {
        override fun toString(): String = "PrivateKey(redacted)"
    }

    /**
     * SSH certificate: private key ([privateKeyPem]) plus a CA-issued certificate ([certificate],
     * a `*-cert.pub` string like `ssh-…-cert-v01@openssh.com <base64> [comment]`). During
     * authentication the client presents the certificate and proves possession of the private
     * key, so both are stored together. [passphrase] decrypts the private key if encrypted. The
     * certificate is public, but the private key/passphrase are secret, so `toString` redacts
     * the whole thing.
     */
    @Serializable
    @SerialName("certificate")
    data class Certificate(
        val privateKeyPem: String,
        val certificate: String,
        val passphrase: String? = null,
    ) : CredentialSecret {
        override fun toString(): String = "Certificate(redacted)"
    }

    /**
     * Key (and optional certificate) kept *outside* the vault, referenced by location and re-read on
     * every connection. This is what makes short-lived CA certificates usable: an external issuer
     * (`tsh login`, `vault write ssh/sign`, `step ssh certificate`) rewrites the file every few
     * hours and the profile keeps working — a certificate pasted into the vault as text would have
     * to be pasted again each time.
     *
     * [privateKeyRef]/[certificateRef] are opaque to this model and interpreted by the platform
     * reader ([SecretFileReader]): a filesystem path on desktop, a `content://` document Uri on
     * Android. A null/blank [certificateRef] means "look for the OpenSSH sibling"
     * (`<privateKeyRef>-cert.pub`) and, failing that, authenticate with the plain key.
     *
     * The refs are locations, not secrets, but [passphrase] is one — and so is the fact of which
     * files a user keeps keys in, so `toString` redacts everything as the other secrets do. The
     * refs are device-local by nature: synced to a device where that file doesn't exist, the
     * profile fails to connect with "file not found" rather than silently authenticating as
     * someone else.
     */
    @Serializable
    @SerialName("key_file")
    data class KeyFile(
        val privateKeyRef: String,
        val certificateRef: String? = null,
        val passphrase: String? = null,
    ) : CredentialSecret {
        override fun toString(): String = "KeyFile(redacted)"
    }
}

/**
 * A keychain record — a reusable secret (key/password/certificate). Hosts reference it directly
 * ([app.skerry.shared.host.Host.credentialId]): one secret can serve multiple hosts. The whole
 * thing — including [label] — lives in the encrypted payload of a [RecordType.CREDENTIAL] record:
 * [VaultRecord]'s plaintext metadata must not reveal key names or types (zero-knowledge). For the
 * same reason `toString` redacts [label], [note] and [secret], leaving only [id] (already plaintext
 * in the metadata).
 *
 * [note] is an optional free-form remark ("temp access for the audit, delete in May"), the keychain's
 * counterpart of [app.skerry.shared.host.Host.notes] and normalized the same way
 * ([app.skerry.shared.text.normalizeNotes]). A record written before the field existed reads back
 * with `null` — unknown keys are ignored and the default fills in, so no migration is needed. It is
 * a remark about a secret, not a second secret, but it sits in the same encrypted payload as the
 * material it describes and is redacted from logs with it.
 */
@Serializable
data class Credential(
    val id: String,
    val label: String,
    val secret: CredentialSecret,
    val note: String? = null,
) {
    override fun toString(): String = "Credential(id=$id, label=redacted, note=redacted, secret=redacted)"
}
