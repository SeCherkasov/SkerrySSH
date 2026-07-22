package app.skerry.ui.identity

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore

/** Kind of keychain secret in the form; expands into [CredentialSecret]. */
enum class CredentialKind { PASSWORD, PRIVATE_KEY, CERTIFICATE }

/**
 * Editable fields of a keychain secret, without [Credential.id]. Fields for all kinds are kept
 * side by side (switching kind in the form doesn't lose input); only the active [kind] expands
 * into [CredentialSecret]. [id] == null creates a new secret, otherwise updates an existing one.
 * [certificate] is a `*-cert.pub` string (for [CredentialKind.CERTIFICATE]; the private key comes
 * from [privateKeyPem]).
 */
data class CredentialDraft(
    val id: String? = null,
    val label: String,
    val kind: CredentialKind,
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
    val certificate: String = "",
) {
    fun toSecret(): CredentialSecret = when (kind) {
        CredentialKind.PASSWORD -> CredentialSecret.Password(password)
        CredentialKind.PRIVATE_KEY -> CredentialSecret.PrivateKey(privateKeyPem, passphrase.ifBlank { null })
        CredentialKind.CERTIFICATE -> CredentialSecret.Certificate(privateKeyPem, certificate, passphrase.ifBlank { null })
    }

    // Secrets must not leak into logs/exception messages: only metadata is exposed.
    override fun toString(): String = "CredentialDraft(id=$id, label=redacted, kind=$kind, secrets=redacted)"
}

/**
 * List state for keychain secrets ([Credential]) over [CredentialStore]: holds the list as
 * Compose state and reduces mutations to store calls, reloading after each. Synchronous (vault
 * CRUD is rare). Requires an unlocked vault (lives behind the master password gate).
 */
@Stable
class CredentialManagerController(
    private val store: CredentialStore,
    private val newId: () -> String,
) {
    var credentials by mutableStateOf(emptyList<Credential>())
        private set

    /** Reloads the list from the vault. Requires an unlocked vault (call after unlock). */
    fun reload() {
        credentials = store.all()
    }

    fun find(id: String?): Credential? = id?.let { wanted -> credentials.firstOrNull { it.id == wanted } }

    /** Creates (if [CredentialDraft.id] == null) or updates a secret; returns the assigned id. */
    fun save(draft: CredentialDraft): String {
        val id = draft.id ?: newId()
        store.put(Credential(id = id, label = draft.label, secret = draft.toSecret()))
        credentials = store.all()
        return id
    }

    /**
     * Renames a secret in place — keeps its id and secret material, changing only the label — and
     * reloads the list. A no-op if [id] is missing/deleted. The rename propagates to sync on its own
     * (it's a re-put of the same record; see [CredentialStore.rename]).
     */
    fun rename(id: String, label: String) {
        store.rename(id, label)
        credentials = store.all()
    }

    fun delete(id: String) {
        store.remove(id)
        credentials = store.all()
    }
}
