package app.skerry.ui.sftp

import kotlin.math.roundToLong

/** Binary size units (1 KB = 1024 B), as file managers conventionally use. */
private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB", "PB")

/**
 * Human-readable size: below 1 KiB, raw bytes ("96 B"); above, one decimal digit with a binary
 * unit ("1.5 KB", "418.0 MB"). No `String.format` (unavailable in commonMain); tenths computed by hand.
 */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < SIZE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    var tenths = (value * 10).roundToLong()
    // Rounding can push the value to 1024.0 of the current unit (e.g. 1048575 B -> 1024.0 KB);
    // bump to the next unit so it shows "1.0 MB" instead of "1024.0 KB".
    if (tenths >= 10_240 && unit < SIZE_UNITS.lastIndex) {
        unit++
        tenths = 10
    }
    return "${tenths / 10}.${tenths % 10} ${SIZE_UNITS[unit]}"
}
