package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_all_verified
import app.skerry.ui.generated.resources.ptail_changed
import app.skerry.ui.generated.resources.ptail_dynamic_proxy
import app.skerry.ui.generated.resources.ptail_ports_active
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelStatus
import org.jetbrains.compose.resources.stringResource

/** Tunnel card arrow icon: dynamic (`-D`) maps to `all_inclusive`, otherwise `arrow_forward`. */
fun mobileTunnelArrow(direction: TunnelDirection): String =
    if (direction == TunnelDirection.Dynamic) "all_inclusive" else "arrow_forward"

/**
 * Card destination text: explicit `host:port`, or `dynamic proxy` for `-D` (SOCKS has no fixed
 * destination — the client sets it).
 */
@Composable
fun mobileTunnelDest(tunnel: Tunnel): String =
    if (tunnel.direction == TunnelDirection.Dynamic) stringResource(Res.string.ptail_dynamic_proxy)
    else "${tunnel.destHost}:${tunnel.destPort}"

/** Count of active (enabled) saved tunnels, for the Port forwarding row subtitle in More. */
fun mobileActiveTunnelCount(tunnels: List<TunnelEntry>): Int =
    tunnels.count { it.status is TunnelStatus.Active }

/**
 * Subtitle for the Port forwarding row in More: active tunnel count of the connected session, or
 * empty string if there is no active session ([count]=null).
 */
@Composable
fun mobileMorePortsSubtitle(count: Int?): String =
    if (count == null) "" else stringResource(Res.string.ptail_ports_active, count)

/** Subtitle for the Known hosts row in More: count of unresolved key changes, or "All verified" if none. */
@Composable
fun mobileMoreKnownSubtitle(changed: Int): String =
    if (changed == 0) stringResource(Res.string.ptail_all_verified)
    else stringResource(Res.string.ptail_changed, changed)
