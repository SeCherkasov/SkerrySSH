package app.skerry.shared.snippet

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret

/**
 * What a `${'$'}{{vault:name}}` reference puts on the command line.
 *
 * The rule is the one the Vault panel already shows, minus what a shell would read as syntax: a
 * password for a password, the OpenSSH public line for a key, and a certificate's type and blob —
 * not the trailing comment the panel's Copy button includes (see [certificateFields]). Nothing else
 * leaves the vault: the private half of a key is never spliced into a command, whatever the template
 * asks for.
 */
sealed interface SnippetVaultValue {
    /**
     * Material that must not be printed: masked in every preview and confirmation
     * ([app.skerry.shared.snippet] callers pass it through the mask), sent only on confirm.
     */
    data class Secret(val value: String) : SnippetVaultValue {
        override fun toString(): String = "Secret(redacted)"
    }

    /**
     * Public material — a key's public half, a certificate. Shown in clear in the preview: masking it
     * would hide which key a command is about to authorize while pretending to protect something
     * that is published by design.
     */
    data class Public(val value: String) : SnippetVaultValue

    /**
     * The entry exists but has nothing a command line can carry: a file-backed secret (the material
     * is on disk, and the path is device-local), or a key whose public half cannot be derived here
     * (unreadable PEM, or a passphrase the vault does not hold).
     */
    data object Unusable : SnippetVaultValue
}

/**
 * Resolves one `${'$'}{{vault:name}}` reference. [publicKeyOf] derives the OpenSSH public line of a
 * private key ([app.skerry.shared.vault.SshKeyGenerator.inspect] in production) — passed in rather
 * than called here because that parse is expensive and platform-backed, while this decision is pure
 * and has to be testable without a crypto provider. A blank derivation counts as no derivation.
 */
fun snippetVaultValue(
    credential: Credential,
    publicKeyOf: (privateKeyPem: String, passphrase: String?) -> String?,
): SnippetVaultValue = when (val secret = credential.secret) {
    is CredentialSecret.Password -> SnippetVaultValue.Secret(secret.password)
    is CredentialSecret.PrivateKey ->
        publicKeyOf(secret.privateKeyPem, secret.passphrase)?.takeIf { it.isNotBlank() }
            ?.let { SnippetVaultValue.Public(it) } ?: SnippetVaultValue.Unusable
    // The certificate string is the whole point of a certificate entry, and it is public — but only
    // its type and blob are handed over, never its trailing comment.
    is CredentialSecret.Certificate ->
        certificateFields(secret.certificate)?.let { SnippetVaultValue.Public(it) } ?: SnippetVaultValue.Unusable
    is CredentialSecret.KeyFile -> SnippetVaultValue.Unusable
}

/**
 * Characters an OpenSSH key type may hold (`ssh-ed25519-cert-v01@openssh.com`, `rsa-sha2-512`), and
 * never a leading `-`: nothing validates this field on import, and a value that starts with a dash
 * is an option to whatever the template hands it to, not an argument.
 */
private val CERTIFICATE_TYPE = Regex("[A-Za-z0-9._@][A-Za-z0-9._@-]*")

/** Characters a base64 blob may hold. */
private val CERTIFICATE_BLOB = Regex("[A-Za-z0-9+/=]+")

/**
 * A `*-cert.pub` line reduced to its type and base64 blob, or `null` when it is not one.
 *
 * The rest of the line is a comment written by whoever issued the certificate — an SSH CA operator,
 * `step`, Teleport, `vault write ssh/sign` — and this value is spliced into a shell line the user
 * then confirms. [sanitizeSnippetValue] flattens a value; it does not quote it, so `;`, `|` and
 * `$(…)` in that comment would reach the remote shell as syntax. The key path already hands over a
 * comment-less line (the generator builds one from the public blob); this makes the two agree.
 *
 * Both fields are checked character by character, not merely counted. Shell syntax needs no
 * whitespace around it, so a type field of `ssh-ed25519-cert-v01@openssh.com;curl…|sh` would survive
 * a field count — and the importer never looks at that field, it only base64-decodes the blob. The
 * character rule also makes it irrelevant which whitespace set the split used: a line whose halves
 * were welded together by an exotic separator no longer matches either pattern.
 */
private fun certificateFields(certificate: String): String? {
    val fields = certificate.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (fields.size < 2) return null
    val (type, blob) = fields
    if (!CERTIFICATE_TYPE.matches(type) || !CERTIFICATE_BLOB.matches(blob)) return null
    return "$type $blob"
}
