package app.skerry.ui.desktop

import app.skerry.shared.host.Host
import app.skerry.shared.host.needsOwnCredential
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.identity.CredentialManagerController

/**
 * Result of resolving a host's authentication before connecting: either a ready [SshAuth], or an
 * SSH host with no bound keychain secret — the UI must ask the user for a password.
 */
sealed interface HostAuthResolution {
    /** Authentication resolved without user involvement. */
    data class Resolved(val auth: SshAuth) : HostAuthResolution

    /** SSH host with no bound secret — a password prompt is needed before connecting. */
    data object NeedsPassword : HostAuthResolution
}

/**
 * Single-level "host → auth method" resolution, shared by connecting to a new tab, a split pane,
 * and "Run snippet on host": Telnet/Serial need no auth (auth is ignored — an empty password
 * placeholder); an SSH host with a bound secret has its [app.skerry.shared.vault.Credential] from
 * the keychain expanded into [SshAuth]; an SSH host with no binding → [HostAuthResolution.NeedsPassword].
 */
fun resolveHostAuth(host: Host, credentials: CredentialManagerController?): HostAuthResolution = when {
    // Telnet/Serial need no auth — connect right away, no password prompt (auth is ignored). Nor
    // does a host typed through its jump host's shell: the jump host's auth rides in the chain.
    // SSH and Mosh otherwise authenticate over SSH and take the credential/prompt path below.
    !host.needsOwnCredential -> HostAuthResolution.Resolved(SshAuth.Password(""))
    // The server does the asking: connect straight away and let it raise its own prompt, rather than
    // demanding a password the profile deliberately doesn't have.
    host.interactiveAuth -> HostAuthResolution.Resolved(SshAuth.Interactive)
    else ->
        // useForConnect, not find: resolving a binding here is the moment the secret authenticates
        // something, and that is what the Vault panel reports as "last used".
        credentials?.useForConnect(host.credentialId)?.let { HostAuthResolution.Resolved(it.toSshAuth()) }
            ?: HostAuthResolution.NeedsPassword
}
