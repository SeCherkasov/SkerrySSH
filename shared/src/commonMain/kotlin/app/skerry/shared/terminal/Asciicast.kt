package app.skerry.shared.terminal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Geometry and event caps for a parsed recording — a `.cast` file is untrusted input. */
const val MAX_CAST_COLUMNS = 1000
const val MAX_CAST_ROWS = 500
const val MAX_CAST_EVENTS = 200_000

/** One chunk of output at [at] seconds from the start of the recording. */
data class CastEvent(val at: Double, val data: String)

/**
 * A parsed [asciicast v2](https://docs.asciinema.org/manual/asciicast/v2/) recording: terminal
 * geometry plus the output events, ready to be replayed into an emulator.
 *
 * [truncated] means the file held more events than [MAX_CAST_EVENTS] and the rest was dropped.
 */
data class Asciicast(
    val columns: Int,
    val rows: Int,
    val title: String?,
    val events: List<CastEvent>,
    val truncated: Boolean = false,
) {
    /** Length of the recording in seconds (0 when there is nothing to play). */
    val duration: Double get() = events.lastOrNull()?.at ?: 0.0
}

private val json = Json { ignoreUnknownKeys = true; isLenient = false }

/**
 * Parses an asciicast v2 file, or returns `null` if [text] isn't one (wrong version, no geometry,
 * not JSON at all).
 *
 * The file is user-supplied, so damage is contained rather than fatal: a malformed event line is
 * skipped instead of failing the whole recording, geometry is clamped to something renderable, and
 * the event count is capped. Only `"o"` (output) events are kept — Skerry replays what the terminal
 * printed, not what was typed.
 *
 * Timestamps are made monotonic: a line whose time goes backwards inherits the previous one, so a
 * hand-edited or concatenated file can't stall playback.
 */
fun parseAsciicast(text: String): Asciicast? {
    val lines = text.lineSequence().iterator()
    var header: JsonObject? = null
    while (lines.hasNext()) {
        val line = lines.next().trim()
        if (line.isEmpty()) continue
        header = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        break
    }
    if (header == null) return null
    if (header.int("version") != 2) return null
    val columns = header.int("width")?.takeIf { it > 0 }?.coerceAtMost(MAX_CAST_COLUMNS) ?: return null
    val rows = header.int("height")?.takeIf { it > 0 }?.coerceAtMost(MAX_CAST_ROWS) ?: return null

    val events = ArrayList<CastEvent>()
    var previous = 0.0
    var truncated = false
    while (lines.hasNext()) {
        if (events.size >= MAX_CAST_EVENTS) { truncated = true; break }
        val event = parseEvent(lines.next(), previous) ?: continue
        previous = event.at
        events.add(event)
    }
    return Asciicast(
        columns = columns,
        rows = rows,
        title = (header["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        events = events,
        truncated = truncated,
    )
}

/** One `[time, "o", data]` line, or `null` if it is malformed or isn't an output event. */
private fun parseEvent(line: String, previous: Double): CastEvent? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val array = runCatching { json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull() ?: return null
    if (array.size < 3) return null
    val at = (array[0] as? JsonPrimitive)?.doubleOrNull ?: return null
    if (at.isNaN() || at.isInfinite()) return null
    if ((array[1] as? JsonPrimitive)?.contentOrNull != "o") return null
    val data = (array[2] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return CastEvent(at = maxOf(at, previous), data = data)
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
