package app.skerry.ui.metrics

import kotlin.math.roundToInt

/**
 * Снимок состояния хоста для info-панели терминала: ресурсы (CPU/память/диск) плюс факты хоста
 * (аптайм, load average, ОС, ядро, число CPU) — всё за один round-trip [HostMetricsController].
 * Проценты CPU/диска — 0..100; память — в байтах. Доли ([cpuFraction]/[memFraction]/[diskFraction]) —
 * для прогресс-баров (0..1). Поля-факты опциональны: `null`, если соответствующей секции в выводе нет
 * (старый сервер, не-Linux) — UI тогда показывает «…», а не мусор.
 */
data class HostMetrics(
    val cpuPercent: Int,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val diskPercent: Int,
    val uptimeSeconds: Long? = null,
    val loadAverage: String? = null,
    val osName: String? = null,
    val kernel: String? = null,
    val cpuCount: Int? = null,
) {
    val cpuFraction: Float get() = (cpuPercent / 100f).coerceIn(0f, 1f)
    val memFraction: Float get() = if (memTotalBytes > 0) (memUsedBytes.toFloat() / memTotalBytes).coerceIn(0f, 1f) else 0f
    val diskFraction: Float get() = (diskPercent / 100f).coerceIn(0f, 1f)
}

/** Секунды аптайма → `HH:MM:SS` (с префиксом `Nd `, если ≥ суток). Отрицательное зажимается в ноль. */
fun formatUptime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val days = s / 86_400
    val h = (s % 86_400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun pad(v: Long) = v.toString().padStart(2, '0')
    val hms = "${pad(h)}:${pad(m)}:${pad(sec)}"
    return if (days > 0) "${days}d $hms" else hms
}

/**
 * Разобрать вывод [HostMetricsController.METRICS_COMMAND] в [HostMetrics]. Формат (Linux):
 *
 * ```
 * cpu  <jiffies…>        # первая выборка /proc/stat
 * cpu  <jiffies…>        # вторая выборка (после короткой паузы)
 * @MEM
 * <free -b: строка Mem: total used …>
 * @DISK
 * <df -Pk /: строка данных с колонкой Use%>
 * ```
 *
 * CPU считается по дельте двух выборок /proc/stat (доля непростоя за интервал); при единственной
 * выборке — мгновенно от старта системы. Возвращает `null`, если памяти или диска в выводе нет
 * (например, не-Linux сервер) — UI тогда показывает «нет данных», а не мусор.
 */
fun parseHostMetrics(raw: String): HostMetrics? {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    // Каждую метрику ищем строго внутри своей секции (между её маркером и следующим маркером),
    // чтобы случайный %-токен из соседней секции (или строка чужой точки монтирования df) не
    // подменил метрику. CPU — строки `cpu …` /proc/stat, всегда в начале до @MEM.
    val cpuPercent = cpuPercentFromStat(lines.filter { it.startsWith("cpu ") })

    // Память и диск обязательны: их отсутствие — признак не-Linux/обрезанного вывода → null целиком.
    val memSection = sectionOrAll(lines, "@MEM")
    val memLine = memSection.firstOrNull { it.startsWith("Mem:") } ?: return null
    val memTokens = memLine.split(WHITESPACE)
    val memTotal = memTokens.getOrNull(1)?.toLongOrNull() ?: return null
    val memUsed = memTokens.getOrNull(2)?.toLongOrNull() ?: return null

    val diskPercent = diskPercentFromDf(sectionOrAll(lines, "@DISK")) ?: return null

    // Факты хоста опциональны: их секций может не быть (старый сервер) — тогда поле остаётся null.
    val uptimeSeconds = section(lines, "@UPTIME").firstOrNull()
        ?.split(WHITESPACE)?.firstOrNull()?.toDoubleOrNull()?.toLong()
    val loadAverage = section(lines, "@LOAD").firstOrNull()
        ?.split(WHITESPACE)?.take(3)?.takeIf { it.size == 3 }?.joinToString(" ")
    // ОС/ядро приходят от удалённого (доверенного, но потенциально неисправного) хоста — режем
    // длину, чтобы аномально длинная строка не ломала вёрстку info-панели (defence-in-depth).
    val osName = section(lines, "@OS").firstOrNull { it.startsWith("PRETTY_NAME=") }
        ?.removePrefix("PRETTY_NAME=")?.trim('"')?.take(FACT_MAX_LEN)?.takeIf { it.isNotBlank() }
    val kernel = section(lines, "@KERNEL").firstOrNull()?.take(FACT_MAX_LEN)?.takeIf { it.isNotBlank() }
    val cpuCount = section(lines, "@CPU").firstOrNull()?.toIntOrNull()

    return HostMetrics(
        cpuPercent = cpuPercent,
        memUsedBytes = memUsed,
        memTotalBytes = memTotal,
        diskPercent = diskPercent,
        uptimeSeconds = uptimeSeconds,
        loadAverage = loadAverage,
        osName = osName,
        kernel = kernel,
        cpuCount = cpuCount,
    )
}

private val WHITESPACE = Regex("\\s+")

/** Предел длины строковых фактов хоста (ОС/ядро) от удалённого сервера — защита вёрстки info-панели. */
private const val FACT_MAX_LEN = 120

/** Все маркеры секций вывода — по ним [section] определяет границу «своей» секции. */
private val SECTION_MARKERS = setOf("@MEM", "@DISK", "@UPTIME", "@LOAD", "@OS", "@KERNEL", "@CPU")

/** Строки секции [marker] — от него (не включая) до следующего маркера (или конца). Пусто, если нет. */
private fun section(lines: List<String>, marker: String): List<String> {
    val start = lines.indexOf(marker)
    if (start < 0) return emptyList()
    val end = (start + 1 until lines.size).firstOrNull { lines[it] in SECTION_MARKERS } ?: lines.size
    return lines.subList(start + 1, end)
}

/** Как [section], но при отсутствии маркера ищет по всему выводу — обратная совместимость с форматом без маркеров. */
private fun sectionOrAll(lines: List<String>, marker: String): List<String> =
    if (marker in lines) section(lines, marker) else lines

/** total и idle (idle+iowait) джиффи из строки `cpu …` /proc/stat, либо null если чисел мало. */
private fun cpuTotalsFromStatLine(line: String): Pair<Long, Long>? {
    val t = line.split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
    if (t.size < 5) return null
    return t.sum() to (t[3] + t[4])
}

private fun cpuPercentFromStat(cpuLines: List<String>): Int {
    if (cpuLines.size >= 2) {
        val a = cpuTotalsFromStatLine(cpuLines[0]) ?: return 0
        val b = cpuTotalsFromStatLine(cpuLines[1]) ?: return 0
        val deltaTotal = b.first - a.first
        val deltaIdle = b.second - a.second
        if (deltaTotal <= 0) return 0
        return (100.0 * (deltaTotal - deltaIdle) / deltaTotal).roundToInt().coerceIn(0, 100)
    }
    val one = cpuLines.firstOrNull()?.let(::cpuTotalsFromStatLine) ?: return 0
    if (one.first <= 0) return 0
    return (100.0 * (one.first - one.second) / one.first).roundToInt().coerceIn(0, 100)
}

/**
 * Use% из секции `df -Pk /` ([diskSection] — строки после маркера `@DISK`): первый токен вида
 * `87%`. Заголовок `Filesystem … Capacity Mounted on` без `%` пропускается, поэтому берётся строка
 * данных корня. null, если строки данных нет.
 */
private fun diskPercentFromDf(diskSection: List<String>): Int? {
    fun percentToken(line: String): Int? =
        line.split(WHITESPACE).firstNotNullOfOrNull { tok ->
            if (tok.endsWith("%")) tok.dropLast(1).toIntOrNull() else null
        }
    return diskSection.firstNotNullOfOrNull { percentToken(it) }
}
