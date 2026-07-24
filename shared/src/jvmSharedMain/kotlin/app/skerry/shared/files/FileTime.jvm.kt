package app.skerry.shared.files

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// LocalDateTime's representable range (tighter than Instant's), with a day of margin so no zone
// offset can push the zoned result back out of range.
private val MIN_EPOCH_SECONDS = LocalDateTime.MIN.toEpochSecond(ZoneOffset.UTC) + 86_400
private val MAX_EPOCH_SECONDS = LocalDateTime.MAX.toEpochSecond(ZoneOffset.UTC) - 86_400

/**
 * JVM (desktop + Android): epoch seconds → local calendar components via java.time.
 * [epochSeconds] is clamped into the representable range: mtime is server-supplied, and an
 * out-of-range value must degrade to a nonsense date rather than throw `DateTimeException` in the
 * middle of a listing render. (The shipped sshj backend happens to confine mtime to 32 bits, but
 * nothing at this call site should rely on that.)
 */
actual fun localFileTime(epochSeconds: Long, zoneId: String?): LocalFileTime {
    val zone = zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
    val secs = epochSeconds.coerceIn(MIN_EPOCH_SECONDS, MAX_EPOCH_SECONDS)
    val local = Instant.ofEpochSecond(secs).atZone(zone)
    return LocalFileTime(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute)
}
