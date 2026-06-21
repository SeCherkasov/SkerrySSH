package app.skerry.ui.metrics

import kotlin.math.roundToInt

/**
 * Снимок ресурсов хоста для info-панели терминала. Проценты CPU/диска — 0..100; память — в байтах.
 * Доли ([cpuFraction]/[memFraction]/[diskFraction]) — для прогресс-баров (0..1).
 */
data class HostMetrics(
    val cpuPercent: Int,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val diskPercent: Int,
) {
    val cpuFraction: Float get() = (cpuPercent / 100f).coerceIn(0f, 1f)
    val memFraction: Float get() = if (memTotalBytes > 0) (memUsedBytes.toFloat() / memTotalBytes).coerceIn(0f, 1f) else 0f
    val diskFraction: Float get() = (diskPercent / 100f).coerceIn(0f, 1f)
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

    // Секции разделены маркерами @MEM/@DISK — память и диск ищем строго внутри своих секций,
    // чтобы случайный %-токен из соседней секции (или строка чужой точки монтирования df) не
    // подменил метрику. CPU — строки `cpu …` /proc/stat, всегда в начале до @MEM.
    val memMarker = lines.indexOf("@MEM")
    val diskMarker = lines.indexOf("@DISK")

    val cpuPercent = cpuPercentFromStat(lines.filter { it.startsWith("cpu ") })

    val memSection = if (memMarker >= 0) lines.subList(memMarker + 1, if (diskMarker > memMarker) diskMarker else lines.size) else lines
    val memLine = memSection.firstOrNull { it.startsWith("Mem:") } ?: return null
    val memTokens = memLine.split(WHITESPACE)
    val memTotal = memTokens.getOrNull(1)?.toLongOrNull() ?: return null
    val memUsed = memTokens.getOrNull(2)?.toLongOrNull() ?: return null

    val diskSection = if (diskMarker >= 0) lines.subList(diskMarker + 1, lines.size) else lines
    val diskPercent = diskPercentFromDf(diskSection) ?: return null

    return HostMetrics(cpuPercent, memUsed, memTotal, diskPercent)
}

private val WHITESPACE = Regex("\\s+")

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
