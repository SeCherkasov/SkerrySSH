package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OSC 10/11/12 and OSC 4 `?` queries: vim, neovim, delta and bat ask for the background to pick a
 * light or dark scheme, and wait for the answer before drawing.
 */
class TerminalColorQueryTest {

    private val esc = 27.toChar().toString()
    private val bel = "\u0007"
    private val st = "$esc\\"

    private val colors = TerminalColors(
        foreground = TermColor.Rgb(0xdd, 0xee, 0xff),
        background = TermColor.Rgb(0x1a, 0x2b, 0x3c),
        cursor = TermColor.Rgb(0x00, 0x80, 0xff),
        ansi = List(16) { TermColor.Rgb(it, it * 2, it * 3) },
    )

    private fun emulator(replies: MutableList<String>, withColors: Boolean = true) =
        TerminalEmulator(cols = 20, rows = 3, respond = { replies += it }).apply {
            if (withColors) applyColors(colors)
        }

    private fun TerminalEmulator.feed(text: String) = feed(text.encodeToByteArray())

    @Test
    fun `background query is answered in the terminator it came with`() {
        val replies = mutableListOf<String>()
        val emu = emulator(replies)
        emu.feed("$esc]11;?$bel")
        emu.feed("$esc]11;?$st")
        assertEquals(listOf("$esc]11;rgb:1a1a/2b2b/3c3c$bel", "$esc]11;rgb:1a1a/2b2b/3c3c$st"), replies)
    }

    @Test
    fun `foreground and cursor queries answer their own colors`() {
        val replies = mutableListOf<String>()
        val emu = emulator(replies)
        emu.feed("$esc]10;?$st$esc]12;?$st")
        assertEquals(listOf("$esc]10;rgb:dddd/eeee/ffff$st", "$esc]12;rgb:0000/8080/ffff$st"), replies)
    }

    @Test
    fun `further parameters query the following colors`() {
        val replies = mutableListOf<String>()
        emulator(replies).feed("$esc]10;?;?$st")
        assertEquals(listOf("$esc]10;rgb:dddd/eeee/ffff$st$esc]11;rgb:1a1a/2b2b/3c3c$st"), replies)
    }

    @Test
    fun `parameters past the cursor color are not answered`() {
        val replies = mutableListOf<String>()
        emulator(replies).feed("$esc]11;?;?;?$st")
        assertEquals(listOf("$esc]11;rgb:1a1a/2b2b/3c3c$st$esc]12;rgb:0000/8080/ffff$st"), replies)
    }

    @Test
    fun `nothing is answered before the colors are known`() {
        val replies = mutableListOf<String>()
        emulator(replies, withColors = false).feed("$esc]11;?$st$esc]4;1;?$st")
        assertTrue(replies.isEmpty())
    }

    @Test
    fun `a set request for a dynamic color is not answered`() {
        val replies = mutableListOf<String>()
        emulator(replies).feed("$esc]11;#000000$st")
        assertTrue(replies.isEmpty())
    }

    @Test
    fun `palette query answers the theme, then an override`() {
        val replies = mutableListOf<String>()
        val emu = emulator(replies)
        emu.feed("$esc]4;1;?$st")
        emu.feed("$esc]4;1;#ff0000$st$esc]4;1;?$st")
        assertEquals(listOf("$esc]4;1;rgb:0101/0202/0303$st", "$esc]4;1;rgb:ffff/0000/0000$st"), replies)
    }

    @Test
    fun `palette query past the theme answers the xterm cube and grayscale`() {
        val replies = mutableListOf<String>()
        emulator(replies).feed("$esc]4;196;?;232;?;256;?$st")
        assertEquals(listOf("$esc]4;196;rgb:ffff/0000/0000$st$esc]4;232;rgb:0808/0808/0808$st"), replies)
    }

    @Test
    fun `one palette sequence gets one reply of at most 256 answers`() {
        val replies = mutableListOf<String>()
        emulator(replies).feed("$esc]4;" + (1..2000).joinToString(";") { "1;?" } + st)
        assertEquals(1, replies.size)
        assertEquals(256, replies.single().split("$esc]4;").size - 1)
    }

    @Test
    fun `xterm palette covers the cube and ramp and leaves the theme sixteen alone`() {
        assertNull(XtermPalette.rgb(15))
        assertEquals(TermColor.Rgb(0, 0, 0), XtermPalette.rgb(16))
        assertEquals(TermColor.Rgb(0x5f, 0x87, 0xaf), XtermPalette.rgb(16 + 36 * 1 + 6 * 2 + 3))
        assertEquals(TermColor.Rgb(255, 255, 255), XtermPalette.rgb(231))
        assertEquals(TermColor.Rgb(238, 238, 238), XtermPalette.rgb(255))
        assertNull(XtermPalette.rgb(256))
    }
}
