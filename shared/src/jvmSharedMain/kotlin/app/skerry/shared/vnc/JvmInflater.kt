package app.skerry.shared.vnc

import java.io.ByteArrayOutputStream

/**
 * [Inflater] over `java.util.zip.Inflater` (zlib, available on both JVM targets). Reusing the SAME
 * `java.util.zip.Inflater` instance across [inflate] calls preserves the sliding window/dictionary —
 * which is exactly RFB's requirement that a ZRLE/Tight zlib stream be persistent for its lifetime.
 * Standard zlib header (NOT nowrap): RFB streams carry the zlib wrapper.
 */
class JvmInflater(
    // Cap on the output of one inflate call, injectable so tests can prove the guard with a small
    // payload instead of allocating a real multi-hundred-MB bomb.
    private val maxOutput: Int = MAX_OUTPUT,
) : Inflater {
    private val z = java.util.zip.Inflater()
    private val buf = ByteArray(16 * 1024)

    override fun inflate(input: ByteArray): ByteArray {
        z.setInput(input)
        val out = ByteArrayOutputStream(input.size * 2)
        while (!z.needsInput() && !z.finished()) {
            val n = z.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
            // Bound the output of one call: a small, highly compressible chunk must not be allowed to
            // expand to gigabytes (zlib bomb) on an untrusted stream. Larger than any real rect's data.
            if (out.size() > maxOutput) {
                throw VncProtocolException("inflate output exceeds $maxOutput bytes")
            }
        }
        return out.toByteArray()
    }

    override fun close() = z.end()

    companion object {
        const val MAX_OUTPUT = 64 * 1024 * 1024
    }
}

/** Factory for persistent [JvmInflater] streams (one per ZRLE connection / per Tight zlib slot). */
object JvmInflaterFactory : InflaterFactory {
    override fun create(): Inflater = JvmInflater()
}
