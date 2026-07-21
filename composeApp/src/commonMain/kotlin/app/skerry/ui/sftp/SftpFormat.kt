package app.skerry.ui.sftp

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_size_bytes
import app.skerry.ui.generated.resources.ftail_size_gb
import app.skerry.ui.generated.resources.ftail_size_kb
import app.skerry.ui.generated.resources.ftail_size_mb
import app.skerry.ui.generated.resources.ftail_size_pb
import app.skerry.ui.generated.resources.ftail_size_tb
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/** Binary size unit (1 KB = 1024 B), as file managers conventionally use. */
enum class SizeUnit { Bytes, KB, MB, GB, TB, PB }

/**
 * A size split into unit and digits, so the visible string comes from a localized template
 * (decimal separator included) rather than concatenation. [Bytes] uses [whole] alone; the scaled
 * units render as `whole`+separator+`tenths`. Both digits are always below 1024, hence [Int].
 */
data class SizeParts(val unit: SizeUnit, val whole: Int, val tenths: Int = 0)

private val SCALED_UNITS = listOf(SizeUnit.KB, SizeUnit.MB, SizeUnit.GB, SizeUnit.TB, SizeUnit.PB)

/**
 * Human-readable size decomposition: below 1 KiB, raw bytes; above, one decimal digit with a
 * binary unit. Pure (no resources), so it stays unit-testable; [sizeText] renders it.
 */
fun sizeParts(bytes: Long): SizeParts {
    if (bytes < 1024) return SizeParts(SizeUnit.Bytes, bytes.toInt())
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < SCALED_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    var tenths = (value * 10).roundToLong()
    // Rounding can push the value to 1024.0 of the current unit (e.g. 1048575 B -> 1024.0 KB);
    // bump to the next unit so it shows "1.0 MB" instead of "1024.0 KB".
    if (tenths >= 10_240 && unit < SCALED_UNITS.lastIndex) {
        unit++
        tenths = 10
    }
    return SizeParts(SCALED_UNITS[unit], (tenths / 10).toInt(), (tenths % 10).toInt())
}

/** Localized template for a scaled unit; [SizeUnit.Bytes] has its own single-argument template. */
private fun scaledTemplate(unit: SizeUnit): StringResource = when (unit) {
    SizeUnit.KB -> Res.string.ftail_size_kb
    SizeUnit.MB -> Res.string.ftail_size_mb
    SizeUnit.GB -> Res.string.ftail_size_gb
    SizeUnit.TB -> Res.string.ftail_size_tb
    SizeUnit.PB -> Res.string.ftail_size_pb
    SizeUnit.Bytes -> Res.string.ftail_size_bytes
}

/** Renders [parts] through the localized size template. */
@Composable
fun sizeText(parts: SizeParts): String = when (parts.unit) {
    SizeUnit.Bytes -> stringResource(Res.string.ftail_size_bytes, parts.whole)
    else -> stringResource(scaledTemplate(parts.unit), parts.whole, parts.tenths)
}

/** Human-readable size of [bytes] ("96 B", "1.5 KB", "418.0 MB"). */
@Composable
fun humanSize(bytes: Long): String = sizeText(sizeParts(bytes))
