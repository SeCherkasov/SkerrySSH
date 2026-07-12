package app.skerry.shared.mosh

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Mosh transport: SSH (with the profile's full auth/jump/host-key setup) is used only to
 * launch `mosh-server` and read its `MOSH CONNECT <port> <key>` line; the SSH connection
 * is then dropped and the session itself runs over mosh's UDP protocol.
 *
 * Failure modes are reported as [MoshSetupException] with a typed reason, because the
 * common ones are environmental and fixable by the user: the `mosh` package missing on the
 * server, no UTF-8 locale, or a firewall filtering UDP 60000–61000.
 *
 * Locale: `mosh-server` requires a UTF-8 locale. The first attempt asks for `en_US.UTF-8`;
 * if the host lacks it, one retry with `C.UTF-8` (glibc ≥ 2.35 ships it out of the box).
 */
class MoshTransport(
    private val ssh: SshTransport,
    private val firstContactTimeoutMillis: Long = 10_000,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        val sshConnection = ssh.connect(target.copy(connectionType = ConnectionType.SSH), auth)
        val server = try {
            launchServer(sshConnection)
        } finally {
            // mosh-server has detached (or failed) — the SSH leg is no longer needed.
            withContext(NonCancellable) { runCatching { sshConnection.disconnect() } }
        }
        return MoshConnection(target.host, server.port, server.key, firstContactTimeoutMillis)
    }

    private suspend fun launchServer(connection: SshConnection): MoshBootstrapResult.Success {
        var result = attempt(connection, "en_US.UTF-8")
        if (result is MoshBootstrapResult.LocaleUnsupported) result = attempt(connection, "C.UTF-8")
        return when (result) {
            is MoshBootstrapResult.Success -> result
            is MoshBootstrapResult.NotInstalled -> throw MoshSetupException(
                reason = MoshSetupException.Reason.SERVER_NOT_INSTALLED,
                message = "mosh-server was not found on the host — Mosh needs the \"mosh\" " +
                    "package installed on the server side",
            )
            is MoshBootstrapResult.LocaleUnsupported -> throw MoshSetupException(
                reason = MoshSetupException.Reason.LOCALE_UNSUPPORTED,
                message = "mosh-server could not start: the host has no UTF-8 locale",
            )
            is MoshBootstrapResult.Failed -> throw MoshSetupException(
                reason = MoshSetupException.Reason.BOOTSTRAP_FAILED,
                detail = result.output.take(MAX_DETAIL_CHARS),
                message = "mosh-server failed to start",
            )
        }
    }

    private suspend fun attempt(connection: SshConnection, locale: String): MoshBootstrapResult {
        val result = connection.exec(
            "mosh-server new -s -c 256 -l LANG=$locale -l LC_ALL=$locale",
        )
        return MoshBootstrap.parse(result.exitCode, result.stdout, result.stderr)
    }

    private companion object {
        const val MAX_DETAIL_CHARS = 600
    }
}

/**
 * A "connection" whose only capability is the single interactive Mosh channel. Exec, SFTP
 * and forwarding are unsupported by design (base class throws): they belong to the SSH
 * transport, which the user can always open in parallel.
 */
internal class MoshConnection(
    private val host: String,
    private val port: Int,
    private val key: MoshKey,
    private val firstContactTimeoutMillis: Long,
) : StreamOnlyConnection("Mosh") {

    private val shellOpened = AtomicBoolean(false)
    @Volatile private var channel: MoshShellChannel? = null

    override val isConnected: Boolean
        get() = channel?.isOpen ?: true // bootstrapped, channel not opened yet

    override val cipher: String = "aes128-ocb@mosh"

    /** Mosh measures RTT on every datagram; report the smoothed value without extra traffic. */
    override suspend fun measureRoundTrip(): Long? = channel?.takeIf { it.isOpen }?.rttMs()

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Mosh connection already opened its channel" }
        val shell = MoshShellChannel(host, port, key, size, firstContactTimeoutMillis)
        try {
            shell.handshake()
        } catch (e: Throwable) {
            shell.abort()
            throw e
        }
        channel = shell
        return shell
    }

    override suspend fun disconnect() {
        channel?.close() ?: Unit
    }
}
