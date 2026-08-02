package app.skerry.ui.tunnel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_type_local
import app.skerry.ui.generated.resources.ports_type_local_display
import app.skerry.ui.generated.resources.ports_type_remote
import app.skerry.ui.generated.resources.ports_type_remote_display
import app.skerry.ui.generated.resources.ports_type_socks
import app.skerry.ui.generated.resources.ports_type_socks_display
import app.skerry.ui.generated.resources.ports_ago_minutes
import app.skerry.ui.generated.resources.ports_ago_seconds
import app.skerry.ui.generated.resources.ports_event_recovered
import app.skerry.ui.generated.resources.ports_fail_auth
import app.skerry.ui.generated.resources.ports_fail_connection
import app.skerry.ui.generated.resources.ports_fail_forward
import app.skerry.ui.generated.resources.ports_fail_host_key
import app.skerry.ui.generated.resources.ports_fail_unavailable
import app.skerry.ui.generated.resources.ports_status_active
import app.skerry.ui.generated.resources.ports_status_connecting
import app.skerry.ui.generated.resources.ports_status_failed
import app.skerry.ui.generated.resources.ports_count_active
import app.skerry.ui.generated.resources.ports_count_stopped
import app.skerry.ui.generated.resources.ports_status_stopped
import app.skerry.ui.generated.resources.ports_tunnel_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Tunnel direction presentation (badge/label/colors) — single source of truth for desktop
 * ([TunnelsView]) and mobile (`MobilePortsView`).
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
@Composable
@ReadOnlyComposable
fun TunnelDirection.badgeColors(): Pair<Color, Color> = when (this) {
    TunnelDirection.Local -> Skerry.colors.cyan.copy(alpha = 0.12f) to Skerry.colors.cyanBright
    TunnelDirection.Remote -> Skerry.colors.amber.copy(alpha = 0.14f) to Skerry.colors.amber
    TunnelDirection.Dynamic -> Skerry.colors.moss.copy(alpha = 0.14f) to Skerry.colors.moss
}

/** Which way traffic crosses the tunnel — the arrow between the LISTEN and TARGET columns. */
enum class TunnelFlow { Outbound, Inbound }

/**
 * `-L`/`-D` listen here and reach out to the target; `-R` listens on the server and traffic arrives
 * from there, so the arrow points back at the local target.
 */
fun TunnelDirection.flow(): TunnelFlow =
    if (this == TunnelDirection.Remote) TunnelFlow.Inbound else TunnelFlow.Outbound

/**
 * Whether a listener on [bindHost] is reachable from outside this machine. Drives the editor's
 * warning: a forward bound to a wildcard or a LAN address hands whatever is on the other end to
 * anyone who can reach the interface, and autostart can now raise such a forward with no click.
 *
 * An empty value is quiet — the form substitutes loopback for it. A name we cannot resolve is
 * treated as reachable: the warning must not fall silent exactly where we are unsure.
 */
fun bindsBeyondLoopback(bindHost: String): Boolean {
    val host = bindHost.trim().removeSurrounding("[", "]").lowercase()
    return when {
        host.isEmpty() -> false
        host == "localhost" || host == "::1" -> false
        else -> !isLoopbackIpv4(host)
    }
}

/**
 * True only for a clean dotted quad inside `127.0.0.0/8`. Deliberately not a prefix test: a DNS
 * label may begin with "127." and resolve anywhere its owner chooses, and an octet with a leading
 * zero is read as octal by some resolvers — neither is something we can call loopback.
 */
private fun isLoopbackIpv4(host: String): Boolean {
    val octets = host.split(".")
    if (octets.size != 4) return false
    if (octets.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return false
    if (octets.any { it.length > 1 && it[0] == '0' }) return false
    val numbers = octets.map { it.toInt() }
    return numbers.all { it in 0..255 } && numbers[0] == 127
}

/** STATUS column states. Connecting and Failed are their own — "not active" isn't the whole story. */
enum class TunnelStatusBadge { Active, Connecting, Failed, Stopped }

fun TunnelStatus.badge(): TunnelStatusBadge = when (this) {
    is TunnelStatus.Active -> TunnelStatusBadge.Active
    TunnelStatus.Connecting -> TunnelStatusBadge.Connecting
    is TunnelStatus.Failed -> TunnelStatusBadge.Failed
    TunnelStatus.Inactive -> TunnelStatusBadge.Stopped
}

/** Header tally: carrying traffic vs everything else (off, dialling, or failed). */
data class TunnelCounts(val active: Int, val stopped: Int)

fun tunnelCounts(entries: List<TunnelEntry>): TunnelCounts {
    val active = entries.count { it.status is TunnelStatus.Active }
    return TunnelCounts(active = active, stopped = entries.size - active)
}

/**
 * Hosts with autostart tunnels and how many each has, in first-seen order — the dashboard card
 * answering "what comes up on its own after unlock". Hosts without one are absent, not zero.
 */
fun autostartByHost(entries: List<TunnelEntry>): List<Pair<String, Int>> =
    entries.filter { it.tunnel.autostart }
        .groupBy { it.tunnel.hostId }
        .map { (hostId, tunnels) -> hostId to tunnels.size }

/** STATUS cell text. */
@Composable
fun TunnelStatusBadge.label(): String = stringResource(
    when (this) {
        TunnelStatusBadge.Active -> Res.string.ports_status_active
        TunnelStatusBadge.Connecting -> Res.string.ports_status_connecting
        TunnelStatusBadge.Failed -> Res.string.ports_status_failed
        TunnelStatusBadge.Stopped -> Res.string.ports_status_stopped
    },
)

/** STATUS cell colors: background (translucent accent) plus text. */
@Composable
@ReadOnlyComposable
fun TunnelStatusBadge.colors(): Pair<Color, Color> = when (this) {
    TunnelStatusBadge.Active -> Skerry.colors.moss.copy(alpha = 0.14f) to Skerry.colors.moss
    TunnelStatusBadge.Connecting -> Skerry.colors.amber.copy(alpha = 0.14f) to Skerry.colors.amber
    TunnelStatusBadge.Failed -> Skerry.colors.sunset.copy(alpha = 0.14f) to Skerry.colors.sunset
    TunnelStatusBadge.Stopped -> Skerry.colors.overlayMed to Skerry.colors.dim
}

/** One-word cause in the events card. */
@Composable
fun TunnelFailureKind.label(): String = stringResource(
    when (this) {
        TunnelFailureKind.HostKey -> Res.string.ports_fail_host_key
        TunnelFailureKind.Auth -> Res.string.ports_fail_auth
        TunnelFailureKind.Forward -> Res.string.ports_fail_forward
        TunnelFailureKind.Connection -> Res.string.ports_fail_connection
        TunnelFailureKind.Unavailable -> Res.string.ports_fail_unavailable
    },
)

/** Right-hand word of an events-card row. */
@Composable
fun TunnelEventKind.label(): String = when (this) {
    is TunnelEventKind.Failed -> kind.label()
    TunnelEventKind.Recovered -> stringResource(Res.string.ports_event_recovered)
}

/** Recovered reads as good news, a failure as bad; the host-key case is the loudest of them. */
@Composable
@ReadOnlyComposable
fun TunnelEventKind.color(): Color = when (this) {
    TunnelEventKind.Recovered -> Skerry.colors.moss
    is TunnelEventKind.Failed -> when (kind) {
        TunnelFailureKind.Forward, TunnelFailureKind.Unavailable -> Skerry.colors.amber
        else -> Skerry.colors.sunset
    }
}

/** Age of an event: seconds under a minute, whole minutes above it. */
@Composable
fun eventAgeText(seconds: Long): String =
    if (seconds < 60) stringResource(Res.string.ports_ago_seconds, seconds)
    else stringResource(Res.string.ports_ago_minutes, seconds / 60)

/**
 * Tunnel count for the autostart card. A hand-rolled one/other split is only right in English —
 * Russian needs a third form for 2–4 ("3 туннеля", not "3 туннелей"), so the choice belongs to the
 * resource's plural rules, not to this function.
 */
@Composable
fun tunnelCountText(count: Int): String = pluralStringResource(Res.plurals.ports_tunnel_count, count, count)

/**
 * Section header tally. Both halves are plurals for the same reason as [tunnelCountText] — "1
 * активных" is what a single non-plural template produces, and English hides it because the
 * adjective doesn't inflect. The two are joined by a separator, not by grammar.
 */
@Composable
fun tunnelCountsSubtitle(counts: TunnelCounts): String {
    val active = pluralStringResource(Res.plurals.ports_count_active, counts.active, counts.active)
    val stopped = pluralStringResource(Res.plurals.ports_count_stopped, counts.stopped, counts.stopped)
    return "$active · $stopped"
}
