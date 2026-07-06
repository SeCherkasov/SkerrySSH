package app.skerry.shared.vault

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * JVM (desktop + Android): parses an ISO instant into local time via java.time. Computes the
 * difference in calendar days (not 24h buckets) so "yesterday 23:00" and "today 01:00" differ by
 * date, as expected. Any parse failure returns `null`.
 */
actual fun securityMoment(iso: String): SecurityMoment? = runCatching {
    val instant = Instant.parse(iso)
    val zone = ZoneId.systemDefault()
    val local = instant.atZone(zone)
    val days = ChronoUnit.DAYS.between(local.toLocalDate(), LocalDate.now(zone)).toInt().coerceAtLeast(0)
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    SecurityMoment(days, "$hh:$mm")
}.getOrNull()
