package app.skerry.shared.files

import java.time.Instant
import java.time.ZoneId

/** JVM (desktop + Android): epoch seconds → local calendar components via java.time. */
actual fun localFileTime(epochSeconds: Long, zoneId: String?): LocalFileTime {
    val zone = zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
    val local = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    return LocalFileTime(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute)
}
