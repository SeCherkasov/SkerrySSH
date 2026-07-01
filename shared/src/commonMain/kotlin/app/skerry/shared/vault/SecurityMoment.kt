package app.skerry.shared.vault

/**
 * Разобранный локальный момент события безопасности для UI: сколько целых суток назад ([daysAgo],
 * ≥ 0) и время суток ([timeOfDay], «HH:MM» в локальной зоне). Гранулярность — сутки: подпись строит
 * UI на локализованных строках («сегодня»/«вчера»/«N дней назад»), а не java.time-локали, чтобы
 * язык совпадал с языком интерфейса.
 */
data class SecurityMoment(val daysAgo: Int, val timeOfDay: String)

/**
 * Разобрать ISO-8601 штамп события ([SecurityEvent.at], как его пишет [Vault]-часами) в локальный
 * [SecurityMoment]. `null`, если строка не парсится — UI тогда показывает сырой штамп или скрывает
 * относительное время (без выдумывания). Реализация — тонкая обёртка над платформенными часами/зоной
 * (JVM: java.time), поэтому [expect]/[actual] в общем JVM-узле.
 */
expect fun securityMoment(iso: String): SecurityMoment?
