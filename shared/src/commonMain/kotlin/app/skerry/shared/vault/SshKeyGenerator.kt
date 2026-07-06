package app.skerry.shared.vault

/**
 * Algorithm of a generated key pair. [label] is the display badge in the vault manager.
 * Deliberately narrow: modern default ([ED25519]) and legacy-compatible RSA ([RSA_4096]) — no
 * other options in the UI.
 */
enum class SshKeyType(val label: String) {
    ED25519("ED25519"),
    RSA_4096("RSA-4096"),
}

/**
 * Public (non-secret) key pair metadata for display: `authorized_keys`-format public key string
 * ([publicKeyOpenSsh]), OpenSSH fingerprint ([fingerprintSha256], `SHA256:`+base64, no padding),
 * and type label ([keyTypeLabel], as in [SshKeyType.label]). Computed from the public part —
 * no secret needed or held here.
 */
data class SshPublicKeyInfo(
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val keyTypeLabel: String,
)

/**
 * A generated key pair: PEM private key ([privateKeyPem], `OPENSSH PRIVATE KEY` format, no
 * passphrase — the vault encrypts it at rest) and public metadata ([info]). [privateKeyPem] is
 * a secret, stored in [CredentialSecret.PrivateKey] inside the vault record's encrypted payload.
 */
data class GeneratedSshKey(
    val privateKeyPem: String,
    val info: SshPublicKeyInfo,
) {
    // Private key must never leak into logs/exceptions (as with [CredentialSecret.PrivateKey]).
    override fun toString(): String = "GeneratedSshKey(redacted, fingerprint=${info.fingerprintSha256})"
}

/**
 * SSH key generator/inspector. Platform implementation is injected (desktop — BouncyCastle over
 * the sshj format); the UI only depends on this contract. Coroutine-free: generation is
 * synchronous and rare (triggered by user action).
 */
interface SshKeyGenerator {
    /**
     * Generate a new pair of [type]. [comment] is appended to the public key string (like
     * `user@host` in `ssh-keygen`); empty means no comment.
     */
    fun generate(type: SshKeyType, comment: String = ""): GeneratedSshKey

    /**
     * Extract public metadata from private key [privateKeyPem] (to show fingerprint/type for an
     * already-stored identity). [passphrase] decrypts the key if it's encrypted. Returns `null`
     * if the key can't be parsed (malformed PEM / wrong passphrase / unsupported type) — a
     * corrupt key must not crash the list.
     */
    fun inspect(privateKeyPem: String, passphrase: String? = null): SshPublicKeyInfo?
}
