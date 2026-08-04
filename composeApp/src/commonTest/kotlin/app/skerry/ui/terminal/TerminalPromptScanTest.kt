package app.skerry.ui.terminal

import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalPromptScanTest {

    /** A grid row of [width] columns holding [text], padded with blanks like the emulator's own. */
    private fun row(text: String, width: Int = 40): List<TermCell> {
        val cells = ArrayList<TermCell>(width)
        for (ch in text) cells.add(TermCell(ch))
        while (cells.size < width) cells.add(TermCell(' '))
        return cells
    }

    private fun screenOf(vararg lines: String, width: Int = 40) = lines.map { row(it, width) }

    private fun sliceText(screen: List<List<TermCell>>, slices: List<CommandLineSlice>) =
        commandLineText(screen, slices).text

    @Test
    fun `cuts the prompt off the cursor row`() {
        val screen = screenOf("user@host:~$ git status")
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(0, 23), altScreen = false))
        assertEquals("git status", sliceText(screen, slices))
    }

    @Test
    fun `root prompt is cut too`() {
        val screen = screenOf("root@pve:~# systemctl restart nginx")
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(0, 35), altScreen = false))
        assertEquals("systemctl restart nginx", sliceText(screen, slices))
    }

    @Test
    fun `alt screen has no shell line`() {
        val screen = screenOf("user@host:~$ vim")
        assertTrue(commandLineSlices(HighlightSource(screen, TerminalPos(0, 16), altScreen = true)).isEmpty())
    }

    @Test
    fun `a row without a prompt terminator is not a command line`() {
        val screen = screenOf("total 48")
        assertTrue(commandLineSlices(HighlightSource(screen, TerminalPos(0, 8), altScreen = false)).isEmpty())
    }

    @Test
    fun `output containing a prompt-like dollar is ignored when the cursor is elsewhere`() {
        val screen = screenOf(
            "echo '$ rm -rf /' >> notes.txt",
            "user@host:~$ ls",
        )
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(1, 15), altScreen = false))
        assertEquals("ls", sliceText(screen, slices))
        assertEquals(listOf(1), slices.map { it.row })
    }

    @Test
    fun `a row filled to the edge joins the next one`() {
        val width = 20
        val screen = listOf(
            row("user@host:~$ grep -rn", width),
            row("pattern /var/log", width),
        )
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(1, 16), altScreen = false))
        assertEquals(listOf(0, 1), slices.map { it.row })
        assertEquals("grep -rnpattern /var/log", sliceText(screen, slices))
    }

    @Test
    fun `the cursor inside the prompt yields nothing`() {
        val screen = screenOf("Password for user: ")
        assertTrue(commandLineSlices(HighlightSource(screen, TerminalPos(0, 5), altScreen = false)).isEmpty())
    }

    @Test
    fun `an empty prompt has no command line`() {
        val screen = screenOf("user@host:~$ ")
        assertTrue(commandLineSlices(HighlightSource(screen, TerminalPos(0, 13), altScreen = false)).isEmpty())
    }

    @Test
    fun `the slice runs to the end of the row, not to the cursor`() {
        val screen = screenOf("user@host:~$ git status --short")
        // Cursor parked mid-line (the user pressed Home / arrowed left).
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(0, 16), altScreen = false))
        assertEquals("git status --short", sliceText(screen, slices))
    }

    @Test
    fun `a cursor row outside the screen yields nothing`() {
        val screen = screenOf("user@host:~$ ls")
        assertTrue(commandLineSlices(HighlightSource(screen, TerminalPos(5, 0), altScreen = false)).isEmpty())
        assertTrue(commandLineSlices(HighlightSource(emptyList(), TerminalPos(0, 0), altScreen = false)).isEmpty())
    }

    @Test
    fun `a wide glyph in the prompt does not shift the cut`() {
        val cells = ArrayList<TermCell>()
        cells.add(TermCell("你", width = CellWidth.Wide))
        cells.add(TermCell("", width = CellWidth.Continuation))
        for (ch in "$ ls -la") cells.add(TermCell(ch))
        while (cells.size < 40) cells.add(TermCell(' '))
        val screen = listOf(cells)
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(0, 12), altScreen = false))
        assertEquals("ls -la", sliceText(screen, slices))
        assertEquals(4, slices.single().startCol)
    }

    @Test
    fun `character positions map back to their columns`() {
        val screen = screenOf("user@host:~$ ls")
        val slices = commandLineSlices(HighlightSource(screen, TerminalPos(0, 15), altScreen = false))
        val flat = commandLineText(screen, slices)
        assertEquals("ls", flat.text)
        assertEquals(13, flat.colAt(0))
        assertEquals(14, flat.colAt(1))
        assertEquals(0, flat.rowAt(0))
    }
}
