package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Synchronized output (DEC mode 2026): the application brackets a frame so it is drawn whole. */
class SynchronizedOutputTest {

    private val esc = 27.toChar().toString()

    private fun TerminalEmulator.feed(text: String) = feed(text.encodeToByteArray())

    @Test
    fun `mode 2026 is set and reset by the application`() {
        val emu = TerminalEmulator()
        assertFalse(emu.synchronizedOutput)
        emu.feed("$esc[?2026h")
        assertTrue(emu.synchronizedOutput)
        emu.feed("$esc[?2026l")
        assertFalse(emu.synchronizedOutput)
    }

    @Test
    fun `DECRQM reports mode 2026 as supported`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[?2026\$p")
        emu.feed("$esc[?2026h$esc[?2026\$p")
        assertEquals(listOf("$esc[?2026;2\$y", "$esc[?2026;1\$y"), replies)
    }

    @Test
    fun `each frame opened is a new frame`() {
        val emu = TerminalEmulator()
        emu.feed("$esc[?2026h")
        val first = emu.synchronizedFrame
        emu.feed("$esc[?2026l$esc[?2026h")
        assertNotEquals(first, emu.synchronizedFrame)
        val second = emu.synchronizedFrame
        emu.feed("$esc[?2026h")
        assertEquals(second, emu.synchronizedFrame)
    }

    @Test
    fun `a full reset ends a frame left open`() {
        val emu = TerminalEmulator()
        emu.feed("$esc[?2026h${esc}c")
        assertFalse(emu.synchronizedOutput)
    }
}
