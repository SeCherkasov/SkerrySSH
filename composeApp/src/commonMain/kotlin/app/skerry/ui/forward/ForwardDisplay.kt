package app.skerry.ui.forward

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_type_local
import app.skerry.ui.generated.resources.ptail_type_remote
import app.skerry.ui.generated.resources.ptail_type_socks
import org.jetbrains.compose.resources.stringResource

/**
 * Pure helpers rendering a single forward as table columns for `TunnelsView` (source/destination
 * as separate cells). Shared source of truth so the format stays consistent across views.
 */

/** Forward type label for the table badge: `-L`->LOCAL, `-R`->REMOTE, `-D`->SOCKS. */
@Composable
fun forwardTypeLabel(direction: ForwardDirection): String = when (direction) {
    ForwardDirection.Local -> stringResource(Res.string.ptail_type_local)
    ForwardDirection.Remote -> stringResource(Res.string.ptail_type_remote)
    ForwardDirection.Dynamic -> stringResource(Res.string.ptail_type_socks)
}

/**
 * Listener port: actual ([ForwardStatus.Active.boundPort]) once up, otherwise the requested port
 * ([ForwardEntry.requestedPort]) while Starting/Failed.
 */
fun forwardListenPort(entry: ForwardEntry): Int =
    (entry.status as? ForwardStatus.Active)?.boundPort ?: entry.requestedPort

/**
 * Source address (listener side). For `-L`/`-D` the listener is local ([ForwardEntry.bindHost]);
 * for `-R` the server owns the listener, so the host is shown as `server`.
 */
fun forwardSourceText(entry: ForwardEntry): String {
    val port = forwardListenPort(entry)
    return when (entry.direction) {
        ForwardDirection.Remote -> "server:$port"
        else -> "${entry.bindHost}:$port"
    }
}

/**
 * Destination address for the DESTINATION cell, or `null` for a dynamic (`-D`) forward, which has
 * no fixed destination (the SOCKS client supplies it per connection).
 */
fun forwardDestText(entry: ForwardEntry): String? = when (entry.direction) {
    ForwardDirection.Dynamic -> null
    else -> "${entry.destHost}:${entry.destPort}"
}

/**
 * Human-readable throughput label: `B/s` below 1 KiB, whole `KB/s` below 1 MiB, otherwise `MB/s`
 * with one decimal. Base 1024.
 */
fun humanRate(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024
    if (kb < 1024) return "$kb KB/s"
    val mbTenths = bytesPerSec * 10 / (1024 * 1024)
    return "${mbTenths / 10}.${mbTenths % 10} MB/s"
}

/** Fill fraction (0..1) of the throughput meter, linear and saturating at 1 MiB/s. */
fun rateFraction(bytesPerSec: Long): Float =
    (bytesPerSec.toFloat() / (1024f * 1024f)).coerceIn(0f, 1f)
