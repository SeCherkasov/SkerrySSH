package app.skerry.shared.container

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection

/**
 * Container exec transport: connects over [ssh] exactly as the host's own SSH profile would (same
 * address/auth/ProxyJump/keep-alive) and runs the container's `exec` command on the session channel
 * instead of the login shell ([SshTarget.shellCommand]). No container-daemon protocol is spoken —
 * the host's `docker`/`kubectl` CLI does the work, which is also why nothing here is
 * platform-specific: desktop and Android behave identically.
 *
 * The resulting session is terminal-only ([ContainerConnection]): SFTP, port forwarding and exec
 * channels would silently act on the *host* rather than the container, so they're refused.
 */
class ContainerTransport(private val ssh: SshTransport) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        val spec = target.container?.normalized()
        if (spec == null || !spec.isComplete) {
            throw SshConnectionException("Container profile has no container selected")
        }
        val hop = target.copy(
            // The SSH leg is a plain SSH connection; the spec is already a command by now, so it
            // isn't carried further (a transport reading it again would double-wrap the exec).
            connectionType = ConnectionType.SSH,
            container = null,
            shellCommand = containerExecArgv(spec),
        )
        return ContainerConnection(ssh.connect(hop, auth))
    }
}

/**
 * A container session over the host's SSH connection. Terminal-side calls pass through to [inner]
 * (the PTY carries the container's shell); connection facts — cipher, server ident, round-trip —
 * describe the SSH leg and pass through too, so the status bar and keep-alive work as on SSH.
 * Host-level channels (exec/SFTP/forwards) are refused by [StreamOnlyConnection]: they'd operate on
 * the host, not on the container the profile points at.
 */
internal class ContainerConnection(private val inner: SshConnection) :
    StreamOnlyConnection("Container exec") {

    override val isConnected: Boolean get() = inner.isConnected
    override val cipher: String? get() = inner.cipher
    override val serverVersion: String? get() = inner.serverVersion

    override suspend fun measureRoundTrip(): Long? = inner.measureRoundTrip()

    override suspend fun openShell(size: PtySize, term: String): ShellChannel = inner.openShell(size, term)

    override suspend fun disconnect() = inner.disconnect()
}
