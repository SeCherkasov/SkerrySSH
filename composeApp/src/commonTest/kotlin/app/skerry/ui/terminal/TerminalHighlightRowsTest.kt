package app.skerry.ui.terminal

import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.highlight.HighlightKind
import app.skerry.shared.terminal.highlight.SessionVocabulary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalHighlightRowsTest {

    private val vocabulary = SessionVocabulary()

    private fun row(text: String, style: TermStyle = TermStyle(), width: Int = 40): List<TermCell> {
        val cells = ArrayList<TermCell>(width)
        for (ch in text) cells.add(TermCell(ch.toString(), style))
        while (cells.size < width) cells.add(TermCell(' '))
        return cells
    }

    private fun highlight(
        screen: List<List<TermCell>>,
        cursorRow: Int = 0,
        cursorCol: Int = 0,
        altScreen: Boolean = false,
        settings: TerminalHighlight = TerminalHighlight(commandLine = true, output = true),
        executed: Set<String> = emptySet(),
    ) = highlightRows(
        HighlightSource(screen, TerminalPos(cursorRow, cursorCol), altScreen, executed),
        screen.indices,
        settings,
        vocabulary,
    )

    /** Categories of [text]'s columns in row 0, as a string per category for readable assertions. */
    private fun kindsOf(screen: List<List<TermCell>>, rowIndex: Int, map: Map<Int, RowHighlight>): String {
        val rh = map[rowIndex] ?: return ""
        val row = screen[rowIndex]
        return row.indices.joinToString("") { col ->
            when (rh.kindAt(col)) {
                HighlightKind.None -> "."
                HighlightKind.Command -> "C"
                HighlightKind.Subcommand -> "s"
                HighlightKind.Option -> "o"
                HighlightKind.PathLit -> "p"
                HighlightKind.LevelError -> "E"
                else -> "?"
            }
        }.trimEnd('.')
    }

    @Test
    fun `command line is highlighted around the cursor`() {
        val screen = listOf(row("user@host:~$ git status"))
        val map = highlight(screen, cursorCol = 23)
        assertEquals(HighlightKind.Command, map.getValue(0).kindAt(13))
        assertEquals(HighlightKind.Subcommand, map.getValue(0).kindAt(17))
        // The prompt itself is never touched.
        assertEquals(HighlightKind.None, map.getValue(0).kindAt(0))
    }

    @Test
    fun `cells the server already colored are left alone`() {
        val colored = TermStyle(fg = TermColor.Indexed(1))
        val screen = listOf(row("user@host:~$ git status", style = colored))
        assertTrue(highlight(screen, cursorCol = 23).isEmpty())
    }

    @Test
    fun `a colored background is respected too`() {
        val screen = listOf(row("user@host:~$ git status", style = TermStyle(bg = TermColor.Indexed(4))))
        assertTrue(highlight(screen, cursorCol = 23).isEmpty())
    }

    @Test
    fun `reverse video is respected`() {
        val screen = listOf(row("user@host:~$ git status", style = TermStyle(inverse = true)))
        assertTrue(highlight(screen, cursorCol = 23).isEmpty())
    }

    @Test
    fun `hidden cells stay hidden`() {
        val screen = listOf(row("user@host:~$ git status", style = TermStyle(hidden = true)))
        assertTrue(highlight(screen, cursorCol = 23).isEmpty())
    }

    @Test
    fun `a partly colored command keeps highlighting on its plain cells`() {
        val cells = ArrayList<TermCell>()
        for (ch in "user@host:~$ ") cells.add(TermCell(ch.toString(), TermStyle(fg = TermColor.Indexed(6))))
        for (ch in "git status") cells.add(TermCell(ch))
        while (cells.size < 40) cells.add(TermCell(' '))
        val map = highlight(listOf(cells), cursorCol = 23)
        assertEquals(HighlightKind.Command, map.getValue(0).kindAt(13))
    }

    @Test
    fun `output levels are highlighted`() {
        val screen = listOf(row("user@host:~$ x"), row("ERROR failed to bind"))
        val map = highlight(screen, cursorRow = 0, cursorCol = 14)
        assertEquals(HighlightKind.LevelError, map.getValue(1).kindAt(0))
        assertEquals(HighlightKind.None, map.getValue(1).kindAt(6))
    }

    @Test
    fun `the command row is not scanned as output`() {
        // "ERROR" typed at the prompt must read as a command-line token, not as a log level.
        val screen = listOf(row("user@host:~$ grep ERROR /var/log/syslog"))
        val map = highlight(screen, cursorCol = 38)
        assertEquals(HighlightKind.None, map.getValue(0).kindAt(18))
        assertEquals(HighlightKind.PathLit, map.getValue(0).kindAt(24))
    }

    @Test
    fun `both switches off means no work at all`() {
        val screen = listOf(row("user@host:~$ git status"), row("ERROR failed"))
        val settings = TerminalHighlight(commandLine = false, output = false)
        assertTrue(highlight(screen, cursorCol = 23, settings = settings).isEmpty())
    }

    @Test
    fun `output switch alone leaves the command line plain`() {
        val screen = listOf(row("user@host:~$ git status"), row("ERROR failed"))
        val map = highlight(screen, cursorCol = 23, settings = TerminalHighlight(commandLine = false, output = true))
        assertNull(map[0])
        assertEquals(HighlightKind.LevelError, map.getValue(1).kindAt(0))
    }

    @Test
    fun `command switch alone leaves output plain`() {
        val screen = listOf(row("user@host:~$ git status"), row("ERROR failed"))
        val map = highlight(screen, cursorCol = 23, settings = TerminalHighlight(commandLine = true, output = false))
        assertEquals(HighlightKind.Command, map.getValue(0).kindAt(13))
        assertNull(map[1])
    }

    @Test
    fun `alt screen is left entirely alone`() {
        val screen = listOf(row("user@host:~$ git status"), row("ERROR failed"))
        assertTrue(highlight(screen, cursorCol = 23, altScreen = true).isEmpty())
    }

    @Test
    fun `only rows of the window are scanned for output`() {
        val screen = listOf(row("user@host:~$ x"), row("ERROR one"), row("ERROR two"))
        val map = highlightRows(
            HighlightSource(screen, TerminalPos(0, 14), altScreen = false, executedCommands = emptySet()),
            window = 0..1,
            settings = TerminalHighlight(commandLine = true, output = true),
            vocabulary = vocabulary,
        )
        assertTrue(1 in map)
        assertNull(map[2])
    }

    @Test
    fun `a continuation cell carries no category`() {
        val cells = ArrayList<TermCell>()
        for (ch in "user@host:~$ ") cells.add(TermCell(ch))
        cells.add(TermCell("你", width = CellWidth.Wide))
        cells.add(TermCell("", width = CellWidth.Continuation))
        while (cells.size < 40) cells.add(TermCell(' '))
        val map = highlight(listOf(cells), cursorCol = 15)
        // Nothing recognizable was typed, so no span may cover the continuation column either.
        assertEquals(HighlightKind.None, (map[0] ?: RowHighlight.Empty).kindAt(14))
    }

    @Test
    fun `lookup outside every span is None`() {
        val screen = listOf(row("user@host:~$ git status"))
        val rh = highlight(screen, cursorCol = 23).getValue(0)
        assertEquals(HighlightKind.None, rh.kindAt(0))
        assertEquals(HighlightKind.None, rh.kindAt(39))
        assertEquals(HighlightKind.None, RowHighlight.Empty.kindAt(3))
    }

    @Test
    fun `adjacent columns of one token form a single span`() {
        val screen = listOf(row("user@host:~$ ls -la /var"))
        val map = highlight(screen, cursorCol = 24)
        assertEquals("CC.ooo.pppp", kindsOf(screen, 0, map).drop(13))
    }

    @Test
    fun `a command that already ran keeps its colors`() {
        // The regression this exists for: after Enter the cursor moves to the next prompt, and the
        // command just executed must not go plain.
        val screen = listOf(row("user@host:~$ git status"), row("user@host:~$ "))
        val map = highlight(screen, cursorRow = 1, cursorCol = 13, executed = setOf("git status"))
        assertEquals(HighlightKind.Command, map.getValue(0).kindAt(13))
        assertEquals(HighlightKind.Subcommand, map.getValue(0).kindAt(17))
    }

    @Test
    fun `a past line is not colored unless this session ran it`() {
        val screen = listOf(row("user@host:~$ git status"), row("user@host:~$ "))
        assertNull(highlight(screen, cursorRow = 1, cursorCol = 13, executed = emptySet())[0])
    }

    @Test
    fun `output that merely looks like a prompt is never colored`() {
        // The command text is in history, but the output line is not equal to it — no match, no color.
        val screen = listOf(row("echo '$ git status' >> notes"), row("user@host:~$ "))
        assertNull(highlight(screen, cursorRow = 1, cursorCol = 13, executed = setOf("git status"))[0])
    }

    @Test
    fun `a wrapped executed command is colored across both rows`() {
        val width = 20
        val screen = listOf(
            row("user@host:~$ grep -rn", width = width),
            row("pattern /var/log", width = width),
            row("user@host:~$ ", width = width),
        )
        val map = highlight(
            screen, cursorRow = 2, cursorCol = 13,
            executed = setOf("grep -rnpattern /var/log"),
        )
        assertEquals(HighlightKind.Command, map.getValue(0).kindAt(13))
        assertEquals(HighlightKind.PathLit, map.getValue(1).kindAt(8))
    }

    @Test
    fun `executed commands follow the command-line switch, not the output one`() {
        val screen = listOf(row("user@host:~$ git status"), row("user@host:~$ "))
        val map = highlight(
            screen, cursorRow = 1, cursorCol = 13,
            settings = TerminalHighlight(commandLine = false, output = true),
            executed = setOf("git status"),
        )
        assertNull(map[0])
    }

    @Test
    fun `a token is split around cells the server colored mid-word`() {
        // grep --color paints only the match inside a word: the client must color what is left of
        // the token on either side and leave the server's cells alone, not give up on the token.
        val cells = ArrayList<TermCell>()
        for (ch in "user@host:~$ ") cells.add(TermCell(ch))
        for (ch in "/var/") cells.add(TermCell(ch))
        for (ch in "lo") cells.add(TermCell(ch.toString(), TermStyle(fg = TermColor.Indexed(1))))
        for (ch in "g") cells.add(TermCell(ch))
        while (cells.size < 40) cells.add(TermCell(' '))

        val rh = highlight(listOf(cells), cursorCol = 22).getValue(0)
        assertEquals(HighlightKind.PathLit, rh.kindAt(13))
        assertEquals(HighlightKind.None, rh.kindAt(18), "the server's cell keeps its own color")
        assertEquals(HighlightKind.None, rh.kindAt(19))
        assertEquals(HighlightKind.PathLit, rh.kindAt(20), "the token resumes after the hole")
    }

    @Test
    fun `a wide glyph inside an argument keeps the grid aligned`() {
        val cells = ArrayList<TermCell>()
        for (ch in "user@host:~$ ") cells.add(TermCell(ch))
        for (ch in "echo \"a") cells.add(TermCell(ch))
        cells.add(TermCell("好", width = CellWidth.Wide))
        cells.add(TermCell("", width = CellWidth.Continuation))
        cells.add(TermCell('"'))
        while (cells.size < 40) cells.add(TermCell(' '))

        val rh = highlight(listOf(cells), cursorCol = 24).getValue(0)
        assertEquals(HighlightKind.Command, rh.kindAt(13), "echo")
        assertEquals(HighlightKind.StringLit, rh.kindAt(18), "the opening quote")
        assertEquals(HighlightKind.StringLit, rh.kindAt(20), "the wide glyph's own column")
        assertEquals(HighlightKind.StringLit, rh.kindAt(22), "the closing quote after the continuation")
    }

    @Test
    fun `a recalled command is colored as the live line, not as a past one`() {
        // Arrow-up puts an earlier command back on the prompt. It is where the cursor is, so the
        // live path owns it; the executed-command matcher must not shadow that.
        val screen = listOf(row("user@host:~$ git status"))
        val map = highlight(screen, cursorCol = 23, executed = setOf("git status"))
        assertEquals(HighlightKind.Command, map.getValue(0).kindAt(13))
    }
}
