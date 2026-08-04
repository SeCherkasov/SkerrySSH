package app.skerry.ui.runbook

import kotlin.math.roundToLong

/**
 * How long a step took, in the two shapes the run screen shows: tenths of a second for the quick
 * ones, whole minutes and seconds once a step runs long enough that the tenth stops meaning
 * anything. Only the numbers live here — the units are words, and words are translated.
 */
sealed interface RunbookDuration {

    /** Under a minute: [text] is the seconds with one decimal, e.g. `9.7`. */
    data class Seconds(val text: String) : RunbookDuration

    /** A minute or more, split into whole [minutes] and [seconds]. */
    data class Minutes(val minutes: Int, val seconds: Int) : RunbookDuration
}

/** [RunbookDuration] of [millis]; a negative reading (a system clock change) counts as zero. */
fun runbookDuration(millis: Long): RunbookDuration {
    val safe = millis.coerceAtLeast(0)
    val tenths = (safe / 100.0).roundToLong()
    if (tenths < MINUTE_TENTHS) return RunbookDuration.Seconds("${tenths / 10}.${tenths % 10}")
    val totalSeconds = ((tenths + 5) / 10).toInt()
    return RunbookDuration.Minutes(totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

private const val MINUTE_TENTHS = 600L
private const val SECONDS_PER_MINUTE = 60
