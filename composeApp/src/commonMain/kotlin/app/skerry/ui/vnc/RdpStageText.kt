package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import app.skerry.shared.rdp.RdpConnectStage
import app.skerry.shared.rdp.RdpDrop
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rdp_drop_closed
import app.skerry.ui.generated.resources.rdp_drop_refused
import app.skerry.ui.generated.resources.rdp_drop_reset
import app.skerry.ui.generated.resources.rdp_drop_timeout
import app.skerry.ui.generated.resources.rdp_drop_unresolved
import app.skerry.ui.generated.resources.rdp_stage_capabilities
import app.skerry.ui.generated.resources.rdp_stage_finalization
import app.skerry.ui.generated.resources.rdp_stage_licensing
import app.skerry.ui.generated.resources.rdp_stage_line
import app.skerry.ui.generated.resources.rdp_stage_line_drop
import app.skerry.ui.generated.resources.rdp_stage_mcs
import app.skerry.ui.generated.resources.rdp_stage_negotiation
import app.skerry.ui.generated.resources.rdp_stage_nla
import app.skerry.ui.generated.resources.rdp_stage_tcp
import app.skerry.ui.generated.resources.rdp_stage_tls
import app.skerry.ui.remote.RemoteDesktopUiState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The failure's reason, followed by the step it happened at and how the link ended when the
 * transport recorded them. The second line is what makes a silent server-side close diagnosable:
 * the reason alone reads "failed to connect" whether the server hung up before TLS or after logon.
 */
@Composable
fun remoteDesktopErrorText(ui: RemoteDesktopUiState.Error): String {
    val reason = vncFailureText(ui.failure)
    val stage = ui.stage ?: return reason
    val step = stringResource(stage.label())
    val line = ui.drop
        ?.let { stringResource(Res.string.rdp_stage_line_drop, step, stringResource(it.label())) }
        ?: stringResource(Res.string.rdp_stage_line, step)
    return "$reason\n$line"
}

private fun RdpConnectStage.label(): StringResource = when (this) {
    RdpConnectStage.Tcp -> Res.string.rdp_stage_tcp
    RdpConnectStage.Negotiation -> Res.string.rdp_stage_negotiation
    RdpConnectStage.Tls -> Res.string.rdp_stage_tls
    RdpConnectStage.Nla -> Res.string.rdp_stage_nla
    RdpConnectStage.Mcs -> Res.string.rdp_stage_mcs
    RdpConnectStage.Licensing -> Res.string.rdp_stage_licensing
    RdpConnectStage.Capabilities -> Res.string.rdp_stage_capabilities
    RdpConnectStage.Finalization -> Res.string.rdp_stage_finalization
}

private fun RdpDrop.label(): StringResource = when (this) {
    RdpDrop.Closed -> Res.string.rdp_drop_closed
    RdpDrop.Timeout -> Res.string.rdp_drop_timeout
    RdpDrop.Reset -> Res.string.rdp_drop_reset
    RdpDrop.Refused -> Res.string.rdp_drop_refused
    RdpDrop.Unresolved -> Res.string.rdp_drop_unresolved
}
