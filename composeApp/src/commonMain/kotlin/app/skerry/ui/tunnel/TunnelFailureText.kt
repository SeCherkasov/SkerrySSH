package app.skerry.ui.tunnel

import androidx.compose.runtime.Composable
import app.skerry.ui.connection.jumpProblemText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_tunnel_error_host_missing
import app.skerry.ui.generated.resources.conn_tunnel_error_no_credential
import app.skerry.ui.generated.resources.conn_tunnel_error_link_lost
import org.jetbrains.compose.resources.stringResource

/** Localized reason a tunnel can't be dialled. */
@Composable
fun tunnelUnavailableText(reason: TunnelUnavailable): String = when (reason) {
    TunnelUnavailable.HostNotFound -> stringResource(Res.string.conn_tunnel_error_host_missing)
    TunnelUnavailable.NoCredential -> stringResource(Res.string.conn_tunnel_error_no_credential)
    is TunnelUnavailable.Jump -> jumpProblemText(reason.problem)
    // Its own line, not the dial failure's: a tunnel that came up and died is a different
    // fact from one that never connected, and the row is all the user has to tell them apart.
    TunnelUnavailable.LinkLost -> stringResource(Res.string.conn_tunnel_error_link_lost)
}

/** Text under a failed tunnel row: the typed reason if there is one, else the transport message. */
@Composable
fun tunnelFailureText(status: TunnelStatus.Failed): String =
    status.reason?.let { tunnelUnavailableText(it) } ?: status.message
