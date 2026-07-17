package app.skerry.shared.vnc

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmInflaterTest {

    /** Deflate [data] with a persistent deflater, flushing so the output can be fed incrementally. */
    private fun deflateChunk(deflater: Deflater, data: ByteArray): ByteArray {
        deflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        // SYNC_FLUSH emits all input as a flushed block, mirroring how an RFB server frames a message.
        var n: Int
        do {
            n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            out.write(buf, 0, n)
        } while (n > 0 && !deflater.needsInput())
        return out.toByteArray()
    }

    @Test
    fun inflates_a_single_message() {
        val deflater = Deflater()
        val payload = "hello vnc".encodeToByteArray()
        val compressed = deflateChunk(deflater, payload)
        val inflater = JvmInflater()
        assertContentEquals(payload, inflater.inflate(compressed))
        inflater.close()
    }

    @Test
    fun preserves_stream_history_across_two_messages() {
        // One deflate stream, two flushed messages; one JvmInflater must decode both in sequence,
        // relying on the shared sliding window (RFB's persistent-stream requirement).
        val deflater = Deflater()
        val first = "the quick brown fox ".encodeToByteArray()
        val second = "jumps over the lazy dog".encodeToByteArray()
        val c1 = deflateChunk(deflater, first)
        val c2 = deflateChunk(deflater, second)
        deflater.finish()

        val inflater = JvmInflater()
        assertContentEquals(first, inflater.inflate(c1))
        assertContentEquals(second, inflater.inflate(c2))
        inflater.close()
    }

    @Test
    fun back_reference_across_messages_decodes() {
        // The second message repeats the first's text — only decodable if the window carried over.
        val deflater = Deflater()
        val text = "abcdefghij_abcdefghij_abcdefghij".encodeToByteArray()
        val c1 = deflateChunk(deflater, text)
        val c2 = deflateChunk(deflater, text) // repeat: compresses against history
        val inflater = JvmInflater()
        inflater.inflate(c1)
        assertContentEquals(text, inflater.inflate(c2))
        assertTrue(c2.size < text.size) // proved it used back-references into the shared window
        inflater.close()
    }

    @Test
    fun rejects_a_zlib_bomb_instead_of_buffering_it() {
        // A zlib bomb expands a tiny chunk into an OOM on an untrusted stream. Proven at 1:1000 scale
        // (a 64 KB run of zeros against a 1 KB cap) so the test doesn't have to allocate a real one.
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = deflateChunk(deflater, ByteArray(64 * 1024))
        assertTrue(compressed.size < 1024) // it really is a "small chunk, huge output"
        val inflater = JvmInflater(maxOutput = 1024)
        assertFailsWith<VncProtocolException> { inflater.inflate(compressed) }
        inflater.close()
    }
}
