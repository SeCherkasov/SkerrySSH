package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsciicastTest {

    private val header = """{"version":2,"width":100,"height":30,"timestamp":1700000000,"title":"root@alpha"}"""

    @Test
    fun `reads the header`() {
        val cast = parseAsciicast("$header\n[0.1,\"o\",\"hi\"]\n")!!
        assertEquals(100, cast.columns)
        assertEquals(30, cast.rows)
        assertEquals("root@alpha", cast.title)
    }

    @Test
    fun `reads events in order`() {
        val cast = parseAsciicast("$header\n[0.5,\"o\",\"a\"]\n[1.25,\"o\",\"b\"]\n")!!
        assertEquals(listOf(0.5, 1.25), cast.events.map { it.at })
        assertEquals(listOf("a", "b"), cast.events.map { it.data })
        assertEquals(1.25, cast.duration)
    }

    @Test
    fun `keeps output events only`() {
        // "i" (input) and marker events exist in the format; a player of session output ignores them.
        val cast = parseAsciicast("$header\n[0.1,\"i\",\"ls\"]\n[0.2,\"o\",\"out\"]\n[0.3,\"m\",\"chapter\"]\n")!!
        assertEquals(listOf("out"), cast.events.map { it.data })
    }

    @Test
    fun `skips malformed lines instead of failing`() {
        // The file comes from outside the app: one bad line must not cost the whole recording.
        val cast = parseAsciicast("$header\n[0.1,\"o\",\"a\"]\nnot json\n[]\n[\"x\",\"o\",\"b\"]\n[0.4,\"o\",\"c\"]\n")!!
        assertEquals(listOf("a", "c"), cast.events.map { it.data })
    }

    @Test
    fun `decodes escapes, control bytes and non-ASCII`() {
        val cast = parseAsciicast("$header\n[0.1,\"o\",\"\\u001b[31mпривет\\r\\n\"]\n")!!
        assertEquals("\u001b[31mпривет\r\n", cast.events.single().data)
    }

    @Test
    fun `rejects a file that is not asciicast v2`() {
        assertNull(parseAsciicast(""))
        assertNull(parseAsciicast("   \n\n"))
        assertNull(parseAsciicast("hello world"))
        assertNull(parseAsciicast("""{"version":1,"width":80,"height":24}"""))
        assertNull(parseAsciicast("""{"version":2,"height":24}"""))
        assertNull(parseAsciicast("""{"version":2,"width":0,"height":24}"""))
    }

    @Test
    fun `a header without a title is fine`() {
        val cast = parseAsciicast("""{"version":2,"width":80,"height":24}""")!!
        assertNull(cast.title)
        assertTrue(cast.events.isEmpty())
        assertEquals(0.0, cast.duration)
    }

    @Test
    fun `clamps an oversized geometry to something renderable`() {
        val cast = parseAsciicast("""{"version":2,"width":999999,"height":999999}""")!!
        assertTrue(cast.columns in 1..MAX_CAST_COLUMNS)
        assertTrue(cast.rows in 1..MAX_CAST_ROWS)
    }

    @Test
    fun `stops after the event cap`() {
        val body = (1..(MAX_CAST_EVENTS + 10)).joinToString("\n") { "[0.$it,\"o\",\"x\"]" }
        val cast = parseAsciicast("$header\n$body")!!
        assertEquals(MAX_CAST_EVENTS, cast.events.size)
        assertTrue(cast.truncated)
    }

    @Test
    fun `time never goes backwards`() {
        // A hand-edited or concatenated file can carry a lower timestamp; playback must not wait forever.
        val cast = parseAsciicast("$header\n[2.0,\"o\",\"a\"]\n[1.0,\"o\",\"b\"]\n")!!
        assertEquals(listOf(2.0, 2.0), cast.events.map { it.at })
    }

    @Test
    fun `round-trips what the recorder writes`() {
        var clock = 1_000L
        val recorder = SessionRecorder(90, 40, 1_700_000_000, "root@alpha", now = { clock })
        recorder.record("привет\r\n")
        clock += 250
        recorder.record("\u001b[31mred\u001b[0m")
        val cast = parseAsciicast(recorder.finish())!!

        assertEquals(90, cast.columns)
        assertEquals(40, cast.rows)
        assertEquals("root@alpha", cast.title)
        assertEquals(listOf("привет\r\n", "\u001b[31mred\u001b[0m"), cast.events.map { it.data })
        assertEquals(0.25, cast.events[1].at)
    }
}
