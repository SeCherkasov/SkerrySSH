package app.skerry.shared.mosh

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** mosh compresses every transport instruction with plain zlib before fragmenting. */
fun moshDeflate(data: ByteArray): ByteArray {
    val deflater = Deflater()
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArray(data.size + 64)
        var size = 0
        while (!deflater.finished()) {
            if (size == out.size) return moshDeflateGrow(deflater, out, size)
            size += deflater.deflate(out, size, out.size - size)
        }
        return out.copyOf(size)
    } finally {
        deflater.end()
    }
}

private fun moshDeflateGrow(deflater: Deflater, filled: ByteArray, size: Int): ByteArray {
    var out = filled.copyOf(filled.size * 2)
    var total = size
    while (!deflater.finished()) {
        if (total == out.size) out = out.copyOf(out.size * 2)
        total += deflater.deflate(out, total, out.size - total)
    }
    return out.copyOf(total)
}

/**
 * Inflate with a hard output cap: the payload comes off the network, so a decompression
 * bomb must not be able to exhaust memory. Returns null on corrupt input or cap overflow.
 */
fun moshInflate(data: ByteArray, maxSize: Int = 8 * 1024 * 1024): ByteArray? {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        var out = ByteArray(minOf(maxSize, maxOf(256, data.size * 4)))
        var size = 0
        while (!inflater.finished()) {
            if (size == out.size) {
                if (out.size >= maxSize) return null
                out = out.copyOf(minOf(maxSize, out.size * 2))
            }
            val n = inflater.inflate(out, size, out.size - size)
            if (n == 0 && inflater.needsInput()) return null // truncated stream
            size += n
        }
        return out.copyOf(size)
    } catch (_: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}
