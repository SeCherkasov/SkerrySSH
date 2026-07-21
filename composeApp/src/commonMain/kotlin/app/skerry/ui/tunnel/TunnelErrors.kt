package app.skerry.ui.tunnel

import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_err_auth_failed
import app.skerry.ui.generated.resources.ptail_err_connection_failed
import app.skerry.ui.generated.resources.ptail_err_forward_failed
import app.skerry.ui.generated.resources.ptail_err_host_not_trusted
import org.jetbrains.compose.resources.getString

/**
 * Friendly message for a failure while dialling a host on the tunnel path (raising a forward or
 * scanning for services). Raw exception text (addresses, sshj internals) is never shown in the UI,
 * only generic messages, as in runConnectionTest. Host key rejection is called out separately:
 * tunnels use the probe verifier, so this is the expected outcome for a not-yet-trusted host.
 */
internal suspend fun friendlyTunnelError(e: Throwable): String = when (e) {
    is SshHostKeyRejectedException -> getString(Res.string.ptail_err_host_not_trusted)
    is SshAuthenticationException -> getString(Res.string.ptail_err_auth_failed)
    is PortForwardException -> getString(Res.string.ptail_err_forward_failed)
    is SshConnectionException -> getString(Res.string.ptail_err_connection_failed)
    else -> getString(Res.string.ptail_err_connection_failed)
}
