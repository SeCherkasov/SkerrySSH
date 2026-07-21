package app.skerry.ui.forward

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_forward_raise_failed
import app.skerry.ui.generated.resources.ptail_rate_bytes
import app.skerry.ui.generated.resources.ptail_rate_kb
import app.skerry.ui.generated.resources.ptail_rate_mb
import app.skerry.ui.generated.resources.ptail_source_server
import app.skerry.ui.generated.resources.ptail_type_local
import app.skerry.ui.generated.resources.ptail_type_remote
import app.skerry.ui.generated.resources.ptail_type_socks
import org.jetbrains.compose.resources.stringResource

/**
 * Helpers rendering a single forward as table columns for `TunnelsView` (source/destination as
 * separate cells). Shared source of truth so the format stays consistent across views. Anything
 * with visible words or a decimal separator is `@Composable` and reads a localized template; the
 * numeric decomposition behind it stays pure and unit-tested.
 */

/** Forward type label for the table badge: `-L`->LOCAL, `-R`->REMOTE, `-D`->SOCKS. */
@Composable
fun forwardTypeLabel(direction: ForwardDirection): String = when (direction) {
    ForwardDirection.Local -> stringResource(Res.string.ptail_type_local)
    ForwardDirection.Remote -> stringResource(Res.string.ptail_type_remote)
    ForwardDirection.Dynamic -> stringResource(Res.string.ptail_type_socks)
}

/** Localized text for a [ForwardStatus.Failed] reason. */
@Composable
fun forwardFailureText(failure: ForwardFailure): String = stringResource(
    when (failure) {
        ForwardFailure.RaiseFailed -> Res.string.ptail_forward_raise_failed
    },
)

/**
 * Listener port: actual ([ForwardStatus.Active.boundPort]) once up, otherwise the requested port
 * ([ForwardEntry.requestedPort]) while Starting/Failed.
 */
fun forwardListenPort(entry: ForwardEntry): Int =
    (entry.status as? ForwardStatus.Active)?.boundPort ?: entry.requestedPort

/**
 * Host of the listener side, or `null` for `-R`, where the server owns the listener and the host is
 * named by a localized word rather than an address. Pure counterpart of [forwardSourceText].
 */
fun forwardSourceHost(entry: ForwardEntry): String? =
    if (entry.direction == ForwardDirection.Remote) null else entry.bindHost

/**
 * Source address (listener side). For `-L`/`-D` the listener is local ([ForwardEntry.bindHost]);
 * for `-R` the server owns the listener, so the host is a localized "server".
 */
@Composable
fun forwardSourceText(entry: ForwardEntry): String {
    val port = forwardListenPort(entry)
    return forwardSourceHost(entry)?.let { "$it:$port" }
        ?: stringResource(Res.string.ptail_source_server, port)
}

/**
 * Destination address for the DESTINATION cell, or `null` for a dynamic (`-D`) forward, which has
 * no fixed destination (the SOCKS client supplies it per connection).
 */
fun forwardDestText(entry: ForwardEntry): String? = when (entry.direction) {
    ForwardDirection.Dynamic -> null
    else -> "${entry.destHost}:${entry.destPort}"
}

/** Throughput unit picked by [rateParts]; `B/s` and `KB/s` are whole, `MB/s` carries one decimal. */
enum class RateUnit { Bytes, KB, MB }

/**
 * Throughput split into unit and digits, so the visible string comes from a localized template
 * (decimal separator included) rather than concatenation. [tenths] is used by [RateUnit.MB] only.
 */
data class RateParts(val unit: RateUnit, val whole: Long, val tenths: Long = 0)

/**
 * Human-readable throughput decomposition: `B/s` below 1 KiB, whole `KB/s` below 1 MiB, otherwise
 * `MB/s` with one decimal. Base 1024. Pure (no resources), so it stays unit-testable.
 */
fun rateParts(bytesPerSec: Long): RateParts {
    if (bytesPerSec < 1024) return RateParts(RateUnit.Bytes, bytesPerSec)
    val kb = bytesPerSec / 1024
    if (kb < 1024) return RateParts(RateUnit.KB, kb)
    val mbTenths = bytesPerSec * 10 / (1024 * 1024)
    return RateParts(RateUnit.MB, mbTenths / 10, mbTenths % 10)
}

/** Renders [parts] through the localized throughput template. */
@Composable
fun rateText(parts: RateParts): String = when (parts.unit) {
    RateUnit.Bytes -> stringResource(Res.string.ptail_rate_bytes, parts.whole)
    RateUnit.KB -> stringResource(Res.string.ptail_rate_kb, parts.whole)
    RateUnit.MB -> stringResource(Res.string.ptail_rate_mb, parts.whole, parts.tenths)
}

/** Human-readable throughput label ("512 B/s", "42 KB/s", "1.1 MB/s"). */
@Composable
fun humanRate(bytesPerSec: Long): String = rateText(rateParts(bytesPerSec))

/** Fill fraction (0..1) of the throughput meter, linear and saturating at 1 MiB/s. */
fun rateFraction(bytesPerSec: Long): Float =
    (bytesPerSec.toFloat() / (1024f * 1024f)).coerceIn(0f, 1f)
