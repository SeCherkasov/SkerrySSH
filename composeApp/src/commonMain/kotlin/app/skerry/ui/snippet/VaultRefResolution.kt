package app.skerry.ui.snippet

import app.skerry.shared.snippet.SnippetVaultValue
import app.skerry.shared.snippet.snippetVaultValue
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.SshKeyGenerator

// Turning a `${'$'}{{vault:name}}` reference into the material a command may carry. Pure and
// non-composable — [TemplateVariables] owns the state and the rows this feeds.

/**
 * Vault reference resolution, done once when the confirmation opens.
 *
 * [Ok.secret] separates a password from public material (a key's public half, a certificate): the
 * first is masked everywhere it is quoted, the second is shown — hiding the public key a command is
 * about to append to `authorized_keys` would tell the user nothing about what they are confirming.
 */
internal sealed interface VaultRef {
    data class Ok(val value: String, val secret: Boolean) : VaultRef {
        // A resolved password is the secret itself: it must not reach a log or a crash report through
        // a generated toString, as the payload types it comes from already ensure.
        override fun toString(): String = if (secret) "Ok(redacted)" else "Ok($value)"
    }

    data object Missing : VaultRef

    /**
     * The name matches more than one entry. Nothing stops two secrets sharing a label, and a
     * reference resolves by label: picking the first would splice a password into one run and a
     * public key into the next, in whatever order the store happened to list them after a sync.
     */
    data object Ambiguous : VaultRef

    data object Unusable : VaultRef
}

/** The single entry a reference names; `null` when nothing or more than one thing answers to it. */
private fun entryNamed(name: String, entries: List<Credential>): Credential? =
    entries.filter { it.label == name }.singleOrNull()

/**
 * Whether resolving [name] has to parse a private key — the one look-up too slow for the composition
 * thread, and the reason vault references resolve asynchronously at all.
 */
internal fun vaultRefNeedsKeyParse(name: String, entries: List<Credential>): Boolean =
    entryNamed(name, entries)?.secret is CredentialSecret.PrivateKey

/**
 * Looks the entry up by label and asks [snippetVaultValue] what it may hand over. `null` [generator]
 * (no crypto provider on this platform build) simply derives no public key, so a key reference
 * reports itself unusable instead of resolving to nothing.
 */
internal fun resolveVaultRef(
    name: String,
    entries: List<Credential>,
    generator: SshKeyGenerator?,
): VaultRef {
    if (entries.count { it.label == name } > 1) return VaultRef.Ambiguous
    val entry = entryNamed(name, entries) ?: return VaultRef.Missing
    val value = snippetVaultValue(entry) { pem, passphrase ->
        runCatching { generator?.inspect(pem, passphrase)?.publicKeyOpenSsh }.getOrNull()
    }
    return when (value) {
        is SnippetVaultValue.Secret -> VaultRef.Ok(value.value, secret = true)
        is SnippetVaultValue.Public -> VaultRef.Ok(value.value, secret = false)
        SnippetVaultValue.Unusable -> VaultRef.Unusable
    }
}
