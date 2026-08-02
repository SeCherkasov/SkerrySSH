package app.skerry.ui.identity

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.CredentialUsageLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Kind of keychain secret in the form; expands into [CredentialSecret]. */
enum class CredentialKind { PASSWORD, PRIVATE_KEY, CERTIFICATE, KEY_FILE }

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
    /** Location of a key kept outside the vault (for [CredentialKind.KEY_FILE]); path or `content://` Uri. */
    val privateKeyRef: String = "",
    /** Location of its certificate; blank means "use the `<key>-cert.pub` sibling, if there is one". */
    val certificateRef: String = "",
) {
    fun toSecret(): CredentialSecret = when (kind) {
        CredentialKind.PASSWORD -> CredentialSecret.Password(password)
        CredentialKind.PRIVATE_KEY -> CredentialSecret.PrivateKey(privateKeyPem, passphrase.ifBlank { null })
        CredentialKind.CERTIFICATE -> CredentialSecret.Certificate(privateKeyPem, certificate, passphrase.ifBlank { null })
        CredentialKind.KEY_FILE ->
            CredentialSecret.KeyFile(privateKeyRef.trim(), certificateRef.trim().ifBlank { null }, passphrase.ifBlank { null })
    }

    // Secrets must not leak into logs/exception messages: only metadata is exposed.
    override fun toString(): String = "CredentialDraft(id=$id, label=redacted, kind=$kind, secrets=redacted)"
}

/**
 * List state for keychain secrets ([Credential]) over [CredentialStore]: holds the list as
 * Compose state and reduces mutations to store calls, reloading after each. Synchronous (vault
 * CRUD is rare). Requires an unlocked vault (lives behind the master password gate).
 *
 * [usage] is the per-device trail the Vault panel reports (added / last used / copied). It is
 * optional: without it every call below still works, the panel just has nothing to show. The trail
 * lives in a file, so it is mirrored into Compose state here and written on [scope] — the panel asks
 * for a secret's dates on every frame it draws, and a copy is recorded from a click handler.
 */
/**
 * The one lane every usage-log event runs on (see [CredentialManagerController.scope]). Built once:
 * a `limitedParallelism` view is cheap but there is no reason to make a new one per controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun usageDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

@Stable
class CredentialManagerController(
    private val store: CredentialStore,
    private val usage: CredentialUsageLog? = null,
    /**
     * Scope for the usage log's file IO — writes must not block a click or a connect, so they leave
     * the caller's thread. Its dispatcher is deliberately **single-threaded**
     * (`limitedParallelism(1)`): events are read-modify-writes of both the file and the [usageById]
     * mirror, so two of them running in parallel would lose one another's update, and a `forget`
     * could land before a still-in-flight `recordUsed` and resurrect the entry it just dropped.
     * One lane keeps them in the order they were submitted. Tests pass an unconfined scope so a
     * recorded event is visible the moment the call returns.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + usageDispatcher()),
    private val newId: () -> String,
) {
    var credentials by mutableStateOf(emptyList<Credential>())
        private set

    /**
     * In-memory mirror of the usage log, keyed by credential id. Compose state, so the panel
     * re-reads it for free and redraws when a date actually changes.
     */
    private var usageById by mutableStateOf(emptyMap<String, CredentialUsage>())

    /** Reloads the list from the vault. Requires an unlocked vault (call after unlock). */
    fun reload() {
        credentials = store.all()
        val log = usage ?: return
        // Same best-effort rule as `record`: an unreadable trail leaves the panel without dates, it
        // does not stop the keychain from loading.
        scope.launch { runCatching { log.all() }.onSuccess { all -> usageById = all.associateBy { it.credentialId } } }
    }

    fun find(id: String?): Credential? = id?.let { wanted -> credentials.firstOrNull { it.id == wanted } }

    /**
     * [find] for the connect path: the same lookup, plus a note that the secret authenticated
     * something. Every place that turns a host binding into an [app.skerry.shared.ssh.SshAuth] goes
     * through here, so "last used" means what it says instead of "last rendered in a list".
     */
    fun useForConnect(id: String?): Credential? = find(id)?.also { record(it.id, CredentialUsageLog::recordUsed) }

    /**
     * Note that a secret was copied to the clipboard. Only passwords are counted: for a key or a
     * certificate what leaves the vault is the public half, which is not a secret and not worth an
     * audit line.
     */
    fun recordCopied(id: String) = record(id, CredentialUsageLog::recordCopied)

    /** Usage trail of a secret: added / last used / copies. `null` when nothing was ever recorded. */
    fun usageOf(id: String): CredentialUsage? = usageById[id]

    /**
     * Applies one usage event off the caller's thread and mirrors the result into [usageById].
     *
     * A failed write is swallowed on purpose: the trail is an audit convenience, and a full disk must
     * not turn a saved secret, a copied password or an opened connection into an error. The cost is
     * that the panel's dates fall behind until the next successful write.
     */
    private fun record(id: String, event: CredentialUsageLog.(String) -> CredentialUsage) {
        val log = usage ?: return
        scope.launch { runCatching { log.event(id) }.onSuccess { usageById = usageById + (id to it) } }
    }

    /** Creates (if [CredentialDraft.id] == null) or updates a secret; returns the assigned id. */
    fun save(draft: CredentialDraft): String {
        val id = draft.id ?: newId()
        val secret = draft.toSecret()
        // Compared before the write: afterwards the store already holds the new material.
        val rotated = draft.id != null && find(draft.id)?.secret?.let { it != secret } == true
        store.put(Credential(id = id, label = draft.label, secret = secret))
        // Only a new secret is born here; replacing the material of an existing one is a rotation,
        // which is what the row reports as "rotated 12 days ago". Re-saving the same material is
        // neither — a rename must not read as a key change.
        when {
            draft.id == null -> record(id, CredentialUsageLog::recordAdded)
            rotated -> record(id, CredentialUsageLog::recordChanged)
        }
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

    /**
     * Persist a batch of already-built secrets (ids assigned by the caller, e.g. an `ssh_config`
     * import whose hosts already reference them) and reload once.
     */
    fun importCredentials(imported: List<Credential>) {
        for (credential in imported) {
            store.put(credential)
            record(credential.id, CredentialUsageLog::recordAdded)
        }
        credentials = store.all()
    }

    fun delete(id: String) {
        store.remove(id)
        // The trail dies with the secret: keeping it would let a deleted key's history reappear
        // under a recycled id, and it is of no use to anyone once the material is gone.
        usage?.let { log -> scope.launch { runCatching { log.forget(id) }; usageById = usageById - id } }
        credentials = store.all()
    }
}
