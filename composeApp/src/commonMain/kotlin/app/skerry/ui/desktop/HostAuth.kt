package app.skerry.ui.desktop

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.usesSshAuth
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
    // Telnet/Serial need no auth — connect right away, no password prompt (auth is ignored).
    // SSH and Mosh both authenticate over SSH and take the credential/prompt path below.
    !host.connectionType.usesSshAuth -> HostAuthResolution.Resolved(SshAuth.Password(""))
    else ->
        credentials?.find(host.credentialId)?.let { HostAuthResolution.Resolved(it.toSshAuth()) }
            ?: HostAuthResolution.NeedsPassword
}
