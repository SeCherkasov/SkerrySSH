package app.skerry.ui.forward

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_rate_bytes
import app.skerry.ui.generated.resources.ptail_rate_kb
import app.skerry.ui.generated.resources.ptail_rate_mb
import org.jetbrains.compose.resources.stringResource

/**
 * Throughput formatting shared by the tunnel table and panels, the host monitor and the mobile
 * terminal header. Anything with a decimal separator is `@Composable` and reads a localized
 * template; the numeric decomposition behind it stays pure and unit-tested.
 */

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
