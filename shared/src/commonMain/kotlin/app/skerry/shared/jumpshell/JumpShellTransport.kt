package app.skerry.shared.jumpshell

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.SharedConnectionPool
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection

/**
 * Reaches a destination by typing `ssh` at a jump host's prompt ([SshTarget.jumpShell]) — for
 * bastions that log a person in inside the shell (a browser login, a code) and allow nothing but a
 * shell, where ProxyJump cannot get past them. The jump host is the last hop of [SshTarget.jump],
 * dialed with that hop's own auth through the rest of the chain; the `auth` given with the target
 * belongs to nobody here and is not used.
 *
 * The jump host connection comes from [pool], so every destination behind one jump host shares
 * one SSH login, and each session is a shell channel of its own on it. Whether the bastion asks
 * its in-shell question again per shell is up to the bastion; this is as few times as a client can
 * make it.
 *
 * The session is terminal-only ([StreamOnlyConnection]): SFTP, exec and forwards would act on the
 * jump host, not on the destination the profile names.
 *
 * [enabled] is this device's own opt-in, read at every connect. The mode arrives with the profile,
 * through sync or a team space, and it hands the destination's host key check to the jump host's
 * `ssh`; a device that never turned it on refuses rather than switching routes on someone's say-so.
 */
class JumpShellTransport(
    private val pool: SharedConnectionPool,
    private val enabled: () -> Boolean,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        if (!enabled()) throw JumpShellRefusedException(JumpShellRefusedException.Reason.DISABLED)
        val hop = target.jump ?: throw SshConnectionException("Jump shell target has no jump host")
        // Checked before anything is dialed: an address that is not one never reaches the shell.
        val command = JumpShellSpec(target.host, target.port, target.username).loginCommand()
            ?: throw JumpShellRefusedException(JumpShellRefusedException.Reason.NOT_AN_ADDRESS)
        val jumpHost = SshTarget(
            host = hop.host,
            port = hop.port,
            username = hop.username,
            jump = hop.jump,
            // The first session's cadence serves them all; the pool does not split on it.
            keepAliveSeconds = target.keepAliveSeconds,
        )
        return JumpShellConnection(pool.acquire(jumpHost, hop.auth), command)
    }
}

/** Why a destination is not typed at the jump host's prompt; typed so the view can localize it. */
class JumpShellRefusedException(val reason: Reason) : SshException(reason.message) {
    enum class Reason(val message: String) {
        /** This device's setting is off (the profile came with the mode through sync or a team). */
        DISABLED("Typing ssh on the jump host is off on this device"),

        /** The destination would be read by the shell as more than a host name or address. */
        NOT_AN_ADDRESS("Destination is not a plain host name or address"),
    }
}

/**
 * One destination session over the pooled jump host connection [inner]. Connection facts describe
 * the leg this client owns, the jump host's. `disconnect()` returns the pool's lease; the jump host
 * connection itself stays for the other sessions and the linger period.
 */
internal class JumpShellConnection(
    private val inner: SshConnection,
    private val command: String,
) : StreamOnlyConnection("Jump shell") {

    override val isConnected: Boolean get() = inner.isConnected
    override val cipher: String? get() = inner.cipher
    override val serverVersion: String? get() = inner.serverVersion

    override suspend fun measureRoundTrip(): Long? = inner.measureRoundTrip()

    override suspend fun openShell(size: PtySize, term: String): ShellChannel =
        PromptLaunchChannel(inner.openShell(size, term), command)

    override suspend fun disconnect() = inner.disconnect()
}
