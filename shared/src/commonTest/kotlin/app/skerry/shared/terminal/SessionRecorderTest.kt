package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRecorderTest {

    private class Clock(var now: Long = 1_000L) {
        fun read(): Long = now
    }

    private fun recorder(clock: Clock, cap: Int = 1000, maxBytes: Int = 1 shl 20) =
        SessionRecorder(
            columns = 80,
            rows = 24,
            startedAtEpochSeconds = 1_760_000_000L,
            title = "root@alpha",
            now = clock::read,
            maxEvents = cap,
            maxBytes = maxBytes,
        )

    @Test
    fun header_is_the_first_line_and_describes_the_terminal() {
        val cast = recorder(Clock()).finish()

        val header = cast.lineSequence().first()
        assertTrue(header.contains("\"version\":2"), header)
        assertTrue(header.contains("\"width\":80"), header)
        assertTrue(header.contains("\"height\":24"), header)
        assertTrue(header.contains("\"timestamp\":1760000000"), header)
        assertTrue(header.contains("\"title\":\"root@alpha\""), header)
    }

    @Test
    fun events_carry_seconds_since_the_recording_started() {
        val clock = Clock(1_000L)
        val r = recorder(clock)

        r.record("first")
        clock.now = 1_500L
        r.record("second")

        val lines = r.finish().lines()
        assertEquals("[0.0,\"o\",\"first\"]", lines[1])
        assertEquals("[0.5,\"o\",\"second\"]", lines[2])
    }

    @Test
    fun control_bytes_and_quotes_are_json_escaped() {
        val r = recorder(Clock())

        r.record("\u001b[31mred\u001b[0m \"quoted\"\r\n")

        val event = r.finish().lines()[1]
        assertTrue(event.contains("\\u001b[31mred"), event)
        assertTrue(event.contains("\\\"quoted\\\""), event)
        assertTrue(event.contains("\\r\\n"), event)
    }

    @Test
    fun empty_chunks_are_not_recorded() {
        val r = recorder(Clock())

        r.record("")

        assertEquals(1, r.finish().lines().size) // header only
        assertEquals(0, r.eventCount)
    }

    @Test
    fun recording_stops_at_the_event_cap_and_says_so() {
        val r = recorder(Clock(), cap = 2)

        r.record("a")
        r.record("b")
        r.record("c")

        assertTrue(r.truncated)
        assertEquals(2, r.eventCount)
    }

    @Test
    fun recording_stops_at_the_byte_cap() {
        val r = recorder(Clock(), maxBytes = 8)

        r.record("12345")
        r.record("67890") // would cross the cap

        assertTrue(r.truncated)
        assertEquals(1, r.eventCount)
    }

    @Test
    fun the_byte_cap_counts_the_escaped_form_not_source_characters() {
        val r = recorder(Clock(), maxBytes = 16)

        r.record("\u001b\u001b\u001b\u001b") // four characters, 24 once escaped

        assertTrue(r.truncated)
        assertEquals(0, r.eventCount)
    }

    @Test
    fun the_byte_cap_counts_utf8_bytes_of_multibyte_text() {
        val r = recorder(Clock(), maxBytes = 8)

        r.record("шшшш") // four characters, eight UTF-8 bytes plus quotes

        assertTrue(r.truncated)
        assertEquals(0, r.eventCount)
    }

    @Test
    fun a_clock_that_steps_backwards_never_moves_an_event_back_in_time() {
        val clock = Clock(1_000L)
        val r = recorder(clock)

        r.record("first")
        clock.now = 1_500L
        r.record("second")
        clock.now = 1_100L // NTP step back mid-recording
        r.record("third")

        assertEquals("[0.5,\"o\",\"third\"]", r.finish().lines()[3])
    }

    @Test
    fun a_fresh_recorder_is_neither_truncated_nor_populated() {
        val r = recorder(Clock())

        assertFalse(r.truncated)
        assertEquals(0, r.eventCount)
    }

    @Test
    fun a_character_split_across_chunks_is_reassembled() {
        val r = recorder(Clock())
        val bytes = "привет".encodeToByteArray()

        r.record(bytes.copyOfRange(0, 5)) // cuts the third character in half
        r.record(bytes.copyOfRange(5, bytes.size))

        val text = r.finish().lines().drop(1).joinToString("") { it.substringAfter("\"o\",\"").substringBeforeLast("\"]") }
        assertFalse(text.contains('�'), text)
        assertEquals("привет", text)
    }

    @Test
    fun a_dangling_incomplete_sequence_is_still_flushed_on_finish() {
        val r = recorder(Clock())
        val bytes = "я".encodeToByteArray()

        r.record(bytes.copyOfRange(0, 1)) // lead byte only, continuation never arrives

        assertEquals(2, r.finish().lines().size) // header + one event, nothing swallowed
    }

    @Test
    fun ascii_chunks_are_not_held_back() {
        val r = recorder(Clock())

        r.record("ls -la\n".encodeToByteArray())

        assertEquals(1, r.eventCount)
    }

    @Test
    fun suggested_file_name_is_filesystem_safe() {
        assertEquals("skerry-root-alpha-20251009-114640.cast", castFileName("root@alpha", "20251009-114640"))
        assertEquals("skerry-session-20251009-114640.cast", castFileName("   ", "20251009-114640"))
        assertEquals("skerry-a-b-c-20251009-114640.cast", castFileName("a/b\\c", "20251009-114640"))
    }
}
