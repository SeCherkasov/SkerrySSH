package app.skerry.ui.agent

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.agent.SshAgentActivity
import app.skerry.shared.agent.SshAgentActivityLog
import app.skerry.shared.agent.SshAgentKeyMaterial
import app.skerry.shared.agent.SshAgentUsage
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.identity.CredentialManagerController

/**
 * Settings → SSH agent: which vault keys the built-in agent offers, whether it is reachable from
 * other programs on this machine, and what it has been asked to do lately.
 *
 * The controller owns the *policy*; the protocol lives in `shared` ([app.skerry.shared.agent]).
 * [keyMaterial] is the single gate between the vault and everything that can use a key — a
 * forwarded session or the local socket — and it closes on three independent conditions: the agent
 * switched off, the vault locked, or the key not put in the agent. Terminal sessions deliberately
 * survive a vault lock, so the lock check is not a formality: without it a server whose session is
 * still open could keep signing behind the lock screen.
 *
 * [socket] is absent on Android (no `SSH_AUTH_SOCK`, no local programs to serve) — the section then
 * shows only forwarding.
 */
@Stable
class SshAgentController(
    private val credentials: CredentialManagerController,
    private val isVaultUnlocked: () -> Boolean,
    private val socket: SshAgentSocket? = null,
    /** Forget keys already parsed into memory (the agent keyring's cache). */
    private val dropParsedKeys: () -> Unit = {},
    private val activityLog: SshAgentActivityLog = SshAgentActivityLog(),
    initialEnabled: Boolean = false,
    private val persistEnabled: (Boolean) -> Unit = {},
    initialSocketEnabled: Boolean = false,
    private val persistSocketEnabled: (Boolean) -> Unit = {},
) {
    /** Master switch. Off means the agent answers with an empty key list, wherever it is asked. */
    var enabled: Boolean by mutableStateOf(initialEnabled)
        private set

    /** Whether the user wants the local socket; [socketPath] tells whether it is actually up. */
    var socketEnabled: Boolean by mutableStateOf(initialSocketEnabled)
        private set

    /** Path to hand to `SSH_AUTH_SOCK`, or `null` when the socket is not listening. */
    var socketPath: String? by mutableStateOf(null)
        private set

    /** The socket was asked for but could not be bound — shown instead of a silent no-op. */
    var socketFailed: Boolean by mutableStateOf(false)
        private set

    /** Recent agent activity, newest first. In memory only — see [SshAgentActivityLog]. */
    var activity: List<SshAgentActivity> by mutableStateOf(activityLog.recent())
        private set

    /** Whether a local agent socket is possible at all here (desktop, POSIX filesystem). */
    val socketSupported: Boolean get() = socket?.isSupported == true

    /** Whether the agent would offer anything at all right now (on, unlocked, at least one key). */
    val hasKeys: Boolean get() = keyMaterial().isNotEmpty()

    /** Keychain secrets that can act as agent keys — passwords cannot. */
    val agentKeys: List<Credential>
        get() = credentials.credentials.filter { it.secret !is CredentialSecret.Password }

    fun enable(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        persistEnabled(enabled)
        // Turning the agent off must take effect now, not at the next request: drop the keys it
        // already parsed and close the door other programs came through.
        dropParsedKeys()
        if (!enabled) stopSocket() else syncSocket()
    }

    /** Put a keychain key in the agent, or take it out ([Credential.agentEnabled]). */
    fun setKeyInAgent(id: String, inAgent: Boolean) {
        credentials.setAgentEnabled(id, inAgent)
        // The keyring caches parsed keys by credential id; a key just removed must stop being
        // offered immediately.
        dropParsedKeys()
    }

    fun exposeSocket(enabled: Boolean) {
        socketEnabled = enabled
        persistSocketEnabled(enabled)
        syncSocket()
    }

    /**
     * Key material for the agent keyring. Called on the agent's IO threads for every request, so it
     * re-reads the current state instead of trusting a snapshot taken at startup.
     */
    fun keyMaterial(): List<SshAgentKeyMaterial> {
        if (!enabled || !isVaultUnlocked()) return emptyList()
        return credentials.credentials.filter { it.agentEnabled }.mapNotNull { credential ->
            when (val secret = credential.secret) {
                is CredentialSecret.PrivateKey -> SshAgentKeyMaterial(
                    id = credential.id,
                    comment = credential.label,
                    privateKeyPem = secret.privateKeyPem,
                    passphrase = secret.passphrase,
                )
                is CredentialSecret.Certificate -> SshAgentKeyMaterial(
                    id = credential.id,
                    comment = credential.label,
                    privateKeyPem = secret.privateKeyPem,
                    passphrase = secret.passphrase,
                    certificate = secret.certificate,
                )
                // A stored password is not a key: the agent protocol has nothing to do with it.
                is CredentialSecret.Password -> null
            }
        }
    }

    /** Record one thing the agent did (called from the agent's own threads). */
    fun record(usage: SshAgentUsage) {
        activityLog.record(usage) { activity = it }
    }

    /** Vault locked: forget parsed keys and stop serving until it is opened again. */
    fun onVaultLocked() {
        dropParsedKeys()
        stopSocket()
    }

    /** Vault opened: bring the socket back if the user had it on. */
    fun onVaultUnlocked() = syncSocket()

    /** Start or stop the socket to match the current policy (agent on, socket wanted, vault open). */
    private fun syncSocket() {
        val wanted = socket != null && enabled && socketEnabled && isVaultUnlocked()
        if (!wanted) return stopSocket()
        if (socketPath != null) return
        val path = socket?.start()
        socketPath = path
        socketFailed = path == null
    }

    private fun stopSocket() {
        socket?.stop()
        socketPath = null
        socketFailed = false
    }
}

/**
 * The local agent socket, as the UI sees it. Implemented on desktop over a unix socket
 * ([app.skerry.shared.agent.UnixSocketSshAgent]); absent on Android.
 */
interface SshAgentSocket {
    /** Whether this platform can host the socket with owner-only access. */
    val isSupported: Boolean

    /** Start listening; returns the socket path, or `null` if it could not be bound. */
    fun start(): String?

    fun stop()
}
