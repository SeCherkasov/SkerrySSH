package app.skerry.shared.io

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

private const val READ_CHUNK_BYTES = 16 * 1024

/**
 * The channel's bytes, or null when it holds more than [limit]; never takes more than `limit + 1`.
 * Not `readRemaining(max)`: it reads the buffer size and moves the buffer in separate looks, and a
 * writer flushing between them has its whole flush taken past the cap (#383). The buffer grows as
 * bytes arrive, so a generous cap costs nothing for the small bodies that are the norm.
 */
internal suspend fun ByteReadChannel.readAtMost(limit: Int): ByteArray? {
    var buffer = ByteArray(minOf(READ_CHUNK_BYTES, limit + 1))
    var filled = 0
    while (filled <= limit) {
        if (filled == buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, limit + 1))
        val read = readAvailable(buffer, filled, buffer.size - filled)
        if (read == -1) {
            // A cancelled channel reads as -1 too; without this a body cut off mid-way reads as whole.
            closedCause?.let { throw it }
            break
        }
        filled += read
    }
    return if (filled > limit) null else buffer.copyOf(filled)
}
