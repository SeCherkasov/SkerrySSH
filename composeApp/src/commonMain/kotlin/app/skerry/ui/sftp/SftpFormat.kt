package app.skerry.ui.sftp

import androidx.compose.runtime.Composable
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.LocalFileTime
import app.skerry.shared.files.localFileTime
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_size_bytes
import app.skerry.ui.generated.resources.ftail_size_gb
import app.skerry.ui.generated.resources.ftail_size_kb
import app.skerry.ui.generated.resources.ftail_size_mb
import app.skerry.ui.generated.resources.ftail_size_pb
import app.skerry.ui.generated.resources.ftail_size_tb
import app.skerry.ui.generated.resources.sftp_date_day
import app.skerry.ui.generated.resources.sftp_date_old
import app.skerry.ui.generated.resources.sftp_date_recent
import app.skerry.ui.generated.resources.sftp_months
import app.skerry.ui.sync.nowMillis
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
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

// Permissions column (ls -l long format).

private val PERMISSION_TRIPLETS = listOf(6, 3, 0) // shift of the owner/group/other rwx triplet

/**
 * POSIX mode bits as the classic `ls -l` string ("drwxr-xr-x"): type char (`d`/`l`/`-`), then
 * three rwx triplets with setuid/setgid/sticky taking the execute slots (`s`/`S`, `t`/`T`).
 * `null` [permissions] (source doesn't report them — the local okio browser) renders nothing.
 */
fun permissionsText(type: FileItemType, permissions: Int?): String? {
    if (permissions == null) return null
    val typeChar = when (type) {
        FileItemType.Directory -> 'd'
        FileItemType.Symlink -> 'l'
        FileItemType.File, FileItemType.Other -> '-'
    }
    // Special bits in owner/group/other execute slots: setuid 0o4000, setgid 0o2000, sticky 0o1000.
    val special = listOf(0x800, 0x400, 0x200)
    val specialChar = listOf('s', 's', 't')
    return buildString(10) {
        append(typeChar)
        PERMISSION_TRIPLETS.forEachIndexed { i, shift ->
            val bits = (permissions shr shift) and 0b111
            append(if (bits and 0b100 != 0) 'r' else '-')
            append(if (bits and 0b010 != 0) 'w' else '-')
            val exec = bits and 0b001 != 0
            append(
                when {
                    permissions and special[i] != 0 -> if (exec) specialChar[i] else specialChar[i].uppercaseChar()
                    exec -> 'x'
                    else -> '-'
                },
            )
        }
    }
}

// Modified-date column. The pure decomposition is unit-tested; the visible string goes through
// localized templates (month name + component order differ per locale).

/** Modified column parts: same calendar year shows day+time, an older stamp shows the year. */
sealed interface FileDateParts {
    data class Recent(val month: Int, val day: Int, val time: String) : FileDateParts
    data class Old(val month: Int, val day: Int, val year: Int) : FileDateParts
}

/**
 * Decomposes mtime for the modified column: `0` means the source didn't report it → `null`
 * (render nothing); the same calendar year as [now] → [FileDateParts.Recent] with a zero-padded
 * "HH:mm"; anything else → [FileDateParts.Old]. [at] converts epoch seconds to local components
 * (injected so tests pin the timezone).
 */
fun fileDateParts(
    modifiedEpochSeconds: Long,
    now: LocalFileTime,
    at: (Long) -> LocalFileTime,
): FileDateParts? {
    if (modifiedEpochSeconds == 0L) return null
    val ts = at(modifiedEpochSeconds)
    return if (ts.year == now.year) {
        val hh = ts.hour.toString().padStart(2, '0')
        val mm = ts.minute.toString().padStart(2, '0')
        FileDateParts.Recent(ts.month, ts.day, "$hh:$mm")
    } else {
        FileDateParts.Old(ts.month, ts.day, ts.year)
    }
}

/** Localized abbreviated month name (1-based [month]) from the `sftp_months` array. */
@Composable
private fun monthName(month: Int): String =
    stringArrayResource(Res.array.sftp_months).getOrNull(month - 1) ?: month.toString()

/**
 * Modified column text for [modifiedEpochSeconds]: "Jun 20 14:30" this year, "Jun 20 2024"
 * otherwise (component order is per-locale), empty when unreported. [withTime] drops the time in
 * the compact (mobile) form: "Jun 20".
 */
@Composable
fun fileDateText(modifiedEpochSeconds: Long, withTime: Boolean = true): String {
    val parts = fileDateParts(
        modifiedEpochSeconds,
        now = localFileTime(nowMillis() / 1000),
        at = ::localFileTime,
    ) ?: return ""
    return when (parts) {
        is FileDateParts.Recent ->
            if (withTime) stringResource(Res.string.sftp_date_recent, monthName(parts.month), parts.day, parts.time)
            else stringResource(Res.string.sftp_date_day, monthName(parts.month), parts.day)
        is FileDateParts.Old ->
            stringResource(Res.string.sftp_date_old, monthName(parts.month), parts.day, parts.year)
    }
}
