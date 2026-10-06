package app.skerry.server.routes

import app.skerry.server.db.TeamRecordingRepository
import io.ktor.server.plugins.BadRequestException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** Never allocate or read more than the validated chunk length and one overflow byte. */
internal suspend fun readRecordingChunk(channel: ByteReadChannel, length: Int): ByteArray {
    if (length !in 40..TeamRecordingRepository.MAX_CHUNK_BYTES) throw BadRequestException("bad chunk length")
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = channel.readAvailable(bytes, offset, length - offset)
        if (read == -1) throw BadRequestException("chunk length mismatch")
        offset += read
    }
    val overflow = ByteArray(1)
    if (channel.readAvailable(overflow, 0, 1) != -1) throw BadRequestException("chunk length mismatch")
    return bytes
}
