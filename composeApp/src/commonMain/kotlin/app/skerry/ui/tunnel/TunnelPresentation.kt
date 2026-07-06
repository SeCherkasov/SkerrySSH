package app.skerry.ui.tunnel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.design.D
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_type_local
import app.skerry.ui.generated.resources.ports_type_local_display
import app.skerry.ui.generated.resources.ports_type_remote
import app.skerry.ui.generated.resources.ports_type_remote_display
import app.skerry.ui.generated.resources.ports_type_socks
import app.skerry.ui.generated.resources.ports_type_socks_display
import org.jetbrains.compose.resources.stringResource

/**
 * Tunnel direction presentation (badge/label/colors) — single source of truth for desktop
 * ([TunnelsView]) and mobile (`MobilePortsView`), following [app.skerry.ui.forward.forwardTypeLabel].
 */

/** Badge label for the tunnel direction: `-L`→LOCAL, `-R`→REMOTE, `-D`→SOCKS. */
@Composable
fun TunnelDirection.badgeLabel(): String = when (this) {
    TunnelDirection.Local -> stringResource(Res.string.ports_type_local)
    TunnelDirection.Remote -> stringResource(Res.string.ports_type_remote)
    TunnelDirection.Dynamic -> stringResource(Res.string.ports_type_socks)
}

/** Full type label for the select: "Local forward (-L)", etc. */
@Composable
fun TunnelDirection.displayLabel(): String = when (this) {
    TunnelDirection.Local -> stringResource(Res.string.ports_type_local_display)
    TunnelDirection.Remote -> stringResource(Res.string.ports_type_remote_display)
    TunnelDirection.Dynamic -> stringResource(Res.string.ports_type_socks_display)
}

/** Badge colors for the direction: background (translucent accent) plus text. */
fun TunnelDirection.badgeColors(): Pair<Color, Color> = when (this) {
    TunnelDirection.Local -> D.cyan.copy(alpha = 0.12f) to D.cyanBright
    TunnelDirection.Remote -> D.amber.copy(alpha = 0.14f) to D.amber
    TunnelDirection.Dynamic -> D.moss.copy(alpha = 0.14f) to D.moss
}
