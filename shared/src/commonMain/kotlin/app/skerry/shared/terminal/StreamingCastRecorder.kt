package app.skerry.shared.terminal

import app.skerry.shared.team.TeamRecordingCrypto
import kotlinx.serialization.json.JsonPrimitive

/** Streams bounded plaintext buffers; [writeChunk] must persist before its borrowed bytes are wiped. */
class StreamingCastRecorder(
    columns: Int,
    rows: Int,
    startedAtEpochSeconds: Long,
    title: String?,
    private val now: () -> Long,
    private val writeChunk: suspend (ByteArray) -> Unit,
    private val maxChunkBytes: Int = TeamRecordingCrypto.MAX_CHUNK_PLAINTEXT,
    private val maxChunks: Int = TeamRecordingCrypto.MAX_CHUNKS,
) {
    private val startedAt = now()
    private var lastElapsed = 0L
    private var pending = ByteArray(0)
    private val buffer = StringBuilder()
    private var bytes = 0
    var chunksWritten: Int = 0
        private set
    var eventsWritten: Long = 0
        private set

    init {
        require(columns in 1..1000 && rows in 1..500)
        require(maxChunkBytes in 256..TeamRecordingCrypto.MAX_CHUNK_PLAINTEXT && maxChunks > 0)
        val header = buildString {
            append("{\"version\":2,\"width\":").append(columns)
            append(",\"height\":").append(rows)
            append(",\"timestamp\":").append(startedAtEpochSeconds)
            title?.take(256)?.takeIf { it.isNotBlank() }?.let { append(",\"title\":").append(JsonPrimitive(it)) }
            append("}\n")
        }
        require(utf8Length(header) < maxChunkBytes)
        buffer.append(header)
        bytes = utf8Length(header)
    }

    suspend fun record(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val previous = pending
        val ownsData = previous.isNotEmpty()
        val data = if (ownsData) previous + chunk else chunk
        try {
            val cut = completeUtf8Length(data)
            pending = if (cut == data.size) ByteArray(0) else data.copyOfRange(cut, data.size)
            if (cut > 0) recordText(data.decodeToString(0, cut))
        } finally {
            previous.fill(0)
            if (ownsData) data.fill(0)
        }
    }

    private suspend fun recordText(text: String) {
        val maxChars = maxOf(1, (maxChunkBytes - 64) / 8)
        var position = 0
        while (position < text.length) {
            var end = minOf(text.length, position + maxChars)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val escaped = JsonPrimitive(text.substring(position, end)).toString()
            val elapsed = (now() - startedAt).coerceAtLeast(lastElapsed)
            lastElapsed = elapsed
            val line = "[${secondsLiteral(elapsed)},\"o\",$escaped]\n"
            val size = utf8Length(line)
            if (bytes + size > maxChunkBytes) flush()
            check(size <= maxChunkBytes) { "one cast event exceeds chunk limit" }
            buffer.append(line)
            bytes += size
            eventsWritten++
            position = end
        }
    }

    suspend fun finish() {
        if (pending.isNotEmpty()) {
            val tail = pending
            pending = ByteArray(0)
            try { recordText(tail.decodeToString()) } finally { tail.fill(0) }
        }
        flush()
    }

    private suspend fun flush() {
        if (bytes == 0) return
        check(chunksWritten < maxChunks) { "recording size limit reached" }
        val clear = buffer.toString().encodeToByteArray()
        try { writeChunk(clear) } finally { clear.fill(0) }
        buffer.clear()
        bytes = 0
        chunksWritten++
    }
}
