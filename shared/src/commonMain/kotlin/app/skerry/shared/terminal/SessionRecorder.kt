package app.skerry.shared.terminal

import kotlinx.serialization.json.JsonPrimitive

/**
 * Records a terminal session in the [asciinema v2](https://docs.asciinema.org/manual/asciicast/v2/)
 * format: a JSON header line followed by one `[time, "o", data]` line per output chunk. The result
 * plays in any asciinema player, so Skerry doesn't have to ship one to make a recording useful.
 *
 * The whole recording is held in memory and written out once on [finish] — a growing file would mean
 * streaming appends from the PTY collector, and terminal output is untrusted, unbounded traffic
 * (`cat` a big file and it never stops). [maxEvents]/[maxBytes] bound it instead: past either limit
 * recording stops and [truncated] says so, rather than the app quietly eating memory. [maxBytes] is
 * measured on the escaped form actually held in memory, not on the source text.
 *
 * Not thread-safe by itself: every call must come from the single coroutine that owns it (in the app,
 * the terminal's command loop — start, stop and output all reach it from there).
 *
 * Recordings contain whatever the server printed — tokens, file contents, key material echoed by a
 * careless command. They are never written anywhere on their own: the user exports one explicitly.
 */
class SessionRecorder(
    private val columns: Int,
    private val rows: Int,
    private val startedAtEpochSeconds: Long,
    private val title: String?,
    private val now: () -> Long,
    private val maxEvents: Int = 200_000,
    private val maxBytes: Int = 32 * 1024 * 1024,
) {
    private val startedAtMillis: Long = now()
    private val events = StringBuilder()
    private var bytes = 0

    // Last timestamp written. [now] can step backwards (NTP correction, suspend/resume), and an
    // asciicast whose time goes back stalls players. [parseAsciicast] repairs that on read; the
    // writer must not produce a file that needs the repair in the first place.
    private var lastElapsed = 0L

    var eventCount: Int = 0
        private set

    /** Whether a limit was hit and recording stopped early. */
    var truncated: Boolean = false
        private set

    // Bytes of a multi-byte character split across PTY chunks: decoding a chunk on its own would
    // turn the halves into replacement characters, so the tail waits for its continuation bytes.
    private var pending = ByteArray(0)

    /**
     * Append a raw PTY [chunk]. A trailing incomplete UTF-8 sequence is held back until the next
     * chunk completes it.
     */
    fun record(chunk: ByteArray) {
        if (chunk.isEmpty() || truncated) return
        val data = if (pending.isEmpty()) chunk else pending + chunk
        val cut = completeUtf8Length(data)
        pending = if (cut == data.size) ByteArray(0) else data.copyOfRange(cut, data.size)
        if (cut > 0) record(data.decodeToString(0, cut))
    }

    /** Append an output [chunk]. Blank chunks and anything past the limits are dropped. */
    fun record(chunk: String) {
        if (chunk.isEmpty() || truncated) return
        if (eventCount >= maxEvents) {
            truncated = true
            return
        }
        // The cap is measured on the escaped UTF-8 form — what the buffer actually holds. Counting
        // source characters would let it grow several times past [maxBytes]: an ESC is one character
        // but six once escaped, and a box-drawing or CJK glyph is one character but three bytes.
        val escaped = JsonPrimitive(chunk).toString()
        val size = utf8Length(escaped)
        if (bytes + size > maxBytes) {
            truncated = true
            return
        }
        val elapsed = (now() - startedAtMillis).coerceAtLeast(lastElapsed)
        lastElapsed = elapsed
        events.append('[').append(secondsLiteral(elapsed)).append(",\"o\",").append(escaped).append("]\n")
        bytes += size
        eventCount++
    }

    /**
     * The finished asciicast: header line + recorded events. Safe to call more than once. A dangling
     * incomplete sequence is flushed as-is — better a replacement character at the very end than a
     * silently dropped byte.
     */
    fun finish(): String {
        if (pending.isNotEmpty()) {
            val tail = pending
            pending = ByteArray(0)
            record(tail.decodeToString())
        }
        return render()
    }

    private fun render(): String = buildString {
        append("{\"version\":2,\"width\":").append(columns)
            .append(",\"height\":").append(rows)
            .append(",\"timestamp\":").append(startedAtEpochSeconds)
        if (!title.isNullOrBlank()) append(",\"title\":").append(JsonPrimitive(title).toString())
        append("}\n")
        append(events)
    }.trimEnd('\n')
}

/**
 * Milliseconds as the seconds literal asciinema expects (`0.5`, `12.043`). Formatted by hand
 * because Kotlin/Common has no locale-independent decimal formatting, and a comma separator from a
 * platform default would produce an unparseable cast.
 */
private fun secondsLiteral(millis: Long): String {
    val whole = millis / 1000
    val fraction = (millis % 1000).toInt()
    return when {
        fraction == 0 -> "$whole.0"
        fraction % 100 == 0 -> "$whole.${fraction / 100}"
        fraction % 10 == 0 -> "$whole.${(fraction / 10).toString().padStart(2, '0')}"
        else -> "$whole.${fraction.toString().padStart(3, '0')}"
    }
}

/** UTF-8 byte length of [s], counted without allocating an encoded copy. */
private fun utf8Length(s: String): Int {
    var n = 0
    for (ch in s) {
        val c = ch.code
        n += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            // Half of a surrogate pair: two halves make the four bytes of an astral character.
            c in 0xD800..0xDFFF -> 2
            else -> 3
        }
    }
    return n
}

/**
 * Length of the prefix of [data] that ends on a complete UTF-8 sequence. Only a *trailing* truncated
 * sequence is held back; malformed bytes in the middle are left to the decoder's replacement
 * handling, exactly as the terminal emulator treats them.
 */
private fun completeUtf8Length(data: ByteArray): Int {
    // A sequence is at most 4 bytes, so the boundary is within the last 3.
    var i = data.size - 1
    val floor = maxOf(0, data.size - 3)
    while (i >= floor) {
        val b = data[i].toInt() and 0xFF
        if (b and 0x80 == 0) return data.size // ASCII: nothing pending
        if (b and 0xC0 == 0xC0) { // lead byte of a multi-byte sequence
            val expected = when {
                b and 0xF8 == 0xF0 -> 4
                b and 0xF0 == 0xE0 -> 3
                else -> 2
            }
            return if (data.size - i >= expected) data.size else i
        }
        i-- // continuation byte: keep walking back to its lead
    }
    return data.size
}

/**
 * File name suggested when exporting a recording: `skerry-<host>-<stamp>.cast`. The host label comes
 * from user data and travels into a Save-As dialog, so anything that isn't a letter, digit or dash
 * is collapsed — a label with a slash must not read as a path.
 */
fun castFileName(hostLabel: String, stamp: String): String {
    val safe = hostLabel.map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-').filter { it.isNotEmpty() }.joinToString("-")
        .lowercase()
        .ifBlank { "session" }
    return "skerry-$safe-$stamp.cast"
}
