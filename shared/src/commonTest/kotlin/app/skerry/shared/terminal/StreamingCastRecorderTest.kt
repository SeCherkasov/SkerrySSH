package app.skerry.shared.terminal

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingCastRecorderTest {
    @Test
    fun `streams past one chunk and preserves split UTF8`() = runTest {
        val chunks = mutableListOf<ByteArray>()
        var time = 0L
        val recorder = StreamingCastRecorder(80, 24, 1, "host", { time }, { chunks += it.copyOf() }, 256)
        val glyph = "界".encodeToByteArray()
        val first = glyph.copyOfRange(0, 1)
        val second = glyph.copyOfRange(1, glyph.size)
        recorder.record(first)
        time = 100
        recorder.record(second)
        assertContentEquals(glyph.copyOfRange(0, 1), first)
        assertContentEquals(glyph.copyOfRange(1, glyph.size), second)
        repeat(30) { recorder.record("1234567890".encodeToByteArray()) }
        recorder.finish()
        assertFalse(chunks.isEmpty())
        val cast = assertNotNull(parseAsciicast(chunks.joinToString("") { it.decodeToString() }))
        assertEquals("界", cast.events.first().data)
        assertEquals(31, cast.events.size)
        assertEquals(chunks.size, recorder.chunksWritten)
    }

    @Test
    fun `sink failure wipes its temporary plaintext without wiping caller output`() = runTest {
        var borrowed: ByteArray? = null
        val recorder = StreamingCastRecorder(80, 24, 1, null, { 0 }, {
            borrowed = it
            error("sink refused")
        })
        val output = "caller output".encodeToByteArray()
        recorder.record(output)
        assertFailsWith<IllegalStateException> { recorder.finish() }
        assertContentEquals("caller output".encodeToByteArray(), output)
        assertTrue(assertNotNull(borrowed).all { it == 0.toByte() })
    }
}
