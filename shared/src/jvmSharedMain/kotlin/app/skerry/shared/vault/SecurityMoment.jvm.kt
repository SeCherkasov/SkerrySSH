package app.skerry.shared.vault

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * JVM (desktop + Android): разбор ISO-инстанта в локальную зону через java.time. Считаем разницу в
 * календарных сутках (не в делении на 24 ч), чтобы «вчера в 23:00» и «сегодня в 01:00» различались
 * по датам, как ожидает человек. Любая ошибка парсинга → `null`.
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
