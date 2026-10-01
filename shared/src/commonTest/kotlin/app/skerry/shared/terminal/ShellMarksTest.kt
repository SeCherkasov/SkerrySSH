package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shell integration (OSC 133 semantic prompts + OSC 7 working directory): what the emulator
 * records, how the anchors follow the buffer (scrollback trimming, reflow, clears), and what an
 * untrusted shell cannot do with either.
 */
class ShellMarksTest {

    // ESC/BEL by number — no invisible control bytes in the source.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    private fun mark(kind: Char, params: String = "") = "$esc]133;$kind$params$bel"

    private fun cwd(uri: String) = "$esc]7;$uri$bel"

    private fun TerminalEmulator.feed(text: String) = feed(text.encodeToByteArray())

    /** Text of one absolute row, trailing blanks trimmed. */
    private fun TerminalEmulator.rowText(row: Int): String =
        lines[row].joinToString("") { it.text }.trimEnd()

    // --- Parser ------------------------------------------------------------

    @Test
    fun `mark kinds parse with their parameters`() {
        assertEquals(ShellMarkEvent.PromptStart, parseShellMark("A"))
        // Ghostty's clickable-line extension (`A;cl`) and any future parameter ride along.
        assertEquals(ShellMarkEvent.PromptStart, parseShellMark("A;cl"))
        assertEquals(ShellMarkEvent.InputStart, parseShellMark("B"))
        assertEquals(ShellMarkEvent.OutputStart, parseShellMark("C"))
    }

    @Test
    fun `command end parses the exit code and ignores the duration`() {
        assertEquals(ShellMarkEvent.CommandEnd(null), parseShellMark("D"))
        assertEquals(ShellMarkEvent.CommandEnd(0), parseShellMark("D;0"))
        assertEquals(ShellMarkEvent.CommandEnd(130), parseShellMark("D;130"))
        // WezTerm's second parameter is the wall-clock duration — not ours to keep.
        assertEquals(ShellMarkEvent.CommandEnd(7), parseShellMark("D;7;0.35"))
    }

    @Test
    fun `an unreadable exit code is unknown, not zero`() {
        // `err` (kitty allows a word) and non-ASCII digits are not statuses; 0 would lie.
        assertEquals(ShellMarkEvent.CommandEnd(null), parseShellMark("D;err"))
        assertEquals(ShellMarkEvent.CommandEnd(null), parseShellMark("D;٤"))
        assertEquals(ShellMarkEvent.CommandEnd(null), parseShellMark("D;${"9".repeat(10)}"))
        assertEquals(ShellMarkEvent.CommandEnd(null), parseShellMark("D;"))
    }

    @Test
    fun `anything but A to D is not a mark`() {
        assertNull(parseShellMark("E"))
        assertNull(parseShellMark(""))
        assertNull(parseShellMark("AB"))
        assertNull(parseShellMark("a"))
    }

    @Test
    fun `working directory parses file URIs and plain paths`() {
        assertEquals("/home/user", parseWorkingDirectory("file://host/home/user"))
        assertEquals("/var/log", parseWorkingDirectory("file:///var/log"))
        assertEquals("/plain/path", parseWorkingDirectory("/plain/path"))
        assertEquals("/my dir/x", parseWorkingDirectory("file://host/my%20dir/x"))
    }

    @Test
    fun `working directory decodes percent escapes as utf-8 bytes`() {
        // Shells percent-encode the path's UTF-8 BYTES (iTerm2/kitty/zsh integration), not its
        // characters: decoding each escape to one Char would read /home/пе as Latin-1 mojibake
        // that still passes the display check and hand the SFTP panel a directory that cannot
        // exist. A raw (unescaped) non-ASCII path decodes through the same pipe.
        assertEquals("/home/пере", parseWorkingDirectory("file://h/home/%D0%BF%D0%B5%D1%80%D0%B5"))
        assertEquals("/home/пере", parseWorkingDirectory("file://h/home/пере"))
        // Escapes that are not valid UTF-8 name no directory — reject the path outright rather
        // than quote replacement characters at the host as a location.
        assertNull(parseWorkingDirectory("/a%FFb"))
    }

    @Test
    fun `working directory rejects what is not an absolute path`() {
        assertNull(parseWorkingDirectory("relative"))
        assertNull(parseWorkingDirectory("file://host"))
        assertNull(parseWorkingDirectory(""))
        assertNull(parseWorkingDirectory("file://host/" + "a".repeat(MAX_CWD_CHARS + 1)))
        // Control characters must not ride into the SFTP panel through a path.
        assertNull(parseWorkingDirectory("/a\u0007b"))
    }

    @Test
    fun `working directory rejects invisible and reordering characters`() {
        // A bidi override reverses how the path bar reads — the browsed location would spoof
        // itself (issue #227 class); percent-decoding must not launder one past the check.
        assertNull(parseWorkingDirectory("file://h/home/%E2%80%AEtc"))
        assertNull(parseWorkingDirectory("/home/‮evitca"))
        // C1 controls and the isolate directions ride the same way.
        assertNull(parseWorkingDirectory("/home/\u009Fx"))
        assertNull(parseWorkingDirectory("/home/⁦x"))
    }

    // --- Bookkeeping ---------------------------------------------------------

    @Test
    fun `a command is bracketed by its marks`() {
        val emu = TerminalEmulator(cols = 40, rows = 6, maxScrollback = 100)
        emu.feed("boot line\r\n")
        // As bash orders them (PS1 wraps A..B, PS0 prints C after the echoed newline).
        emu.feed(mark('A') + "user@h $ " + mark('B') + "ls\r\n" + mark('C') + "file1\r\n" + mark('D', ";0"))
        val marks = emu.shellCommandMarks()
        assertEquals(1, marks.size)
        val m = marks[0]
        assertEquals("user@h $ ls", emu.rowText(m.promptRow).trim())
        assertEquals(9, m.inputCol) // the typed command begins right after the prompt ("user@h $ ")
        val outputRow = assertNotNull(m.outputRow)
        assertEquals("file1", emu.rowText(outputRow).trim())
        assertEquals(0, m.exitCode)
        assertTrue(m.endRow != null && m.endRow >= outputRow)
    }

    @Test
    fun `a failing command keeps its exit code`() {
        val emu = TerminalEmulator(cols = 40, rows = 6, maxScrollback = 100)
        emu.feed(mark('A') + "$ " + mark('B') + "false" + mark('C') + mark('D', ";1"))
        assertEquals(1, emu.shellCommandMarks().single().exitCode)
    }

    @Test
    fun `a D without an A is nothing`() {
        val emu = TerminalEmulator(cols = 40, rows = 6, maxScrollback = 100)
        emu.feed("noise\r\n" + mark('D', ";0"))
        assertTrue(emu.shellCommandMarks().isEmpty())
    }

    @Test
    fun `a new prompt implicitly closes an unfinished one`() {
        val emu = TerminalEmulator(cols = 40, rows = 6, maxScrollback = 100)
        emu.feed(mark('A') + "$ " + mark('B') + "ls") // no C, no D — the line was abandoned
        emu.feed("\r\n" + mark('A') + "$ " + mark('B') + "pwd" + mark('C') + mark('D', ";0"))
        val marks = emu.shellCommandMarks()
        assertEquals(2, marks.size)
        // The abandoned mark ended where it stood, with no exit code to show.
        assertNull(marks[0].exitCode)
        assertNull(marks[0].outputRow)
        assertEquals(0, marks[1].exitCode)
    }

    @Test
    fun `marks survive a resize by following their text`() {
        val emu = TerminalEmulator(cols = 20, rows = 8, maxScrollback = 200)
        // Long boot lines put the command at nonzero rows AND wrap at the narrower width, so an
        // un-remapped anchor provably lands on boot text instead of on its own command.
        emu.feed("boot zero aaaa bbbb\r\nboot one aaaa bbbb\r\n")
        emu.feed(mark('A') + "$ " + mark('B') + "echo aaaa bbbb" + mark('C') + "\r\n" + mark('D', ";0"))
        val before = emu.shellCommandMarks().single()
        assertTrue(before.promptRow > 0, "fixture sanity: the command must not sit at row 0")
        emu.resize(10, 8)
        val narrowed = emu.shellCommandMarks().single()
        assertTrue(
            emu.rowText(narrowed.promptRow).trim().startsWith("$ echo"),
            "prompt anchor followed the narrowed text, was '${emu.rowText(narrowed.promptRow)}'",
        )
        assertEquals(0, narrowed.exitCode)
        // And back out: a widened re-split must carry the anchors just the same.
        emu.resize(20, 8)
        val widened = emu.shellCommandMarks().single()
        assertTrue(
            emu.rowText(widened.promptRow).trim().startsWith("$ echo"),
            "prompt anchor followed the widened text, was '${emu.rowText(widened.promptRow)}'",
        )
        assertEquals(0, widened.exitCode)
    }

    @Test
    fun `a mark whose prompt left with reflow-trimmed history dies`() {
        val emu = TerminalEmulator(cols = 20, rows = 8, maxScrollback = 6)
        // An old command at the head of the buffer, then enough boot lines that the narrowing
        // re-split overflows the scrollback cap: the trimmed front takes the old prompt with it.
        emu.feed(mark('A') + "$ " + mark('B') + "old" + mark('C') + "\r\n" + mark('D', ";0"))
        repeat(6) { n -> emu.feed("boot $n aaaa bbbb\r\n") }
        emu.feed(mark('A') + "$ " + mark('B') + "new" + mark('C') + "\r\n" + mark('D', ";0"))
        val before = emu.shellCommandMarks()
        assertEquals(2, before.size)
        assertTrue(before[0].promptRow < before[1].promptRow, "fixture sanity: old command first")
        emu.resize(10, 8)
        val after = emu.shellCommandMarks()
        // Only the late command survives, on its own text: the old one's prompt row went with
        // the trimmed front, and a mark that cannot point at its own prompt would point at
        // someone else's text (same contract as scrollback trimming).
        assertEquals(
            1,
            after.size,
            "was ${after.map { it.promptRow to emu.rowText(it.promptRow).trim() }}",
        )
        assertTrue(emu.rowText(after.single().promptRow).trim().startsWith("$ new"))
    }

    @Test
    fun `an anchor written after an in-window trim keeps its own row`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 8)
        // Saturate history first: from here on, every scrolled line trims one row.
        repeat(12) { n -> emu.feed("boot $n aaaa bbbb\r\n") }
        // One feed = one publish window: the echo and output trim rows mid-command, and the C/D
        // anchors are recorded AFTER those trims, in already-post-trim coordinates. The owed
        // shift must not be subtracted from them a second time — each anchor names its own text.
        emu.feed(mark('A') + "$ " + mark('B') + "seq\r\n" + mark('C') + "out1\r\nout2\r\n" + mark('D', ";0"))
        val m = emu.shellCommandMarks().single()
        assertEquals("$ seq", emu.rowText(m.promptRow).trim(), "was ${emu.rowText(m.promptRow)}")
        assertEquals("out1", emu.rowText(m.outputRow!!).trim(), "was ${emu.rowText(m.outputRow!!)}")
        val endRow = m.endRow!!
        assertTrue(endRow > m.outputRow!!, "the end is on the fresh row after the output, was end=$endRow output=${m.outputRow}")
        assertEquals("", emu.rowText(endRow).trim())
    }

    @Test
    fun `marks shift with scrollback trimming and vanish once fully scrolled out`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 8)
        repeat(6) { n ->
            emu.feed(mark('A') + "$ " + mark('B') + "cmd$n" + mark('C') + "\r\nout$n\r\n" + mark('D', ";$n"))
        }
        // Six commands × ~3 rows each > scrollback: the oldest are gone entirely…
        val marks = emu.shellCommandMarks()
        assertTrue(marks.size in 2..5, "expected the tail of the commands, got ${marks.size}")
        // …and every surviving anchor still points at its own text.
        marks.forEach { m ->
            assertTrue(emu.rowText(m.promptRow).trim().startsWith("$ cmd"), "row ${m.promptRow}: '${emu.rowText(m.promptRow)}'")
        }
        val newest = marks.last()
        assertEquals("$ cmd5", emu.rowText(newest.promptRow).trim())
    }

    @Test
    fun `clearing history keeps the marks that are still on screen`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 100)
        emu.feed(mark('A') + "$ old" + mark('B') + mark('C') + "\r\nold out\r\n" + mark('D', ";0"))
        emu.feed(mark('A') + "$ new" + mark('B') + mark('C') + "\r\nnew out\r\n" + mark('D', ";0"))
        emu.feed("${esc}[3J") // `clear`: history gone, screen rows stay
        val marks = emu.shellCommandMarks()
        assertTrue(marks.isNotEmpty(), "a mark on the visible screen survives the history clear")
        marks.forEach { m ->
            assertTrue(emu.rowText(m.promptRow).trim().startsWith("$ new"), "row ${m.promptRow}: '${emu.rowText(m.promptRow)}'")
        }
    }

    @Test
    fun `a full reset drops the marks`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 100)
        emu.feed(mark('A') + "$ " + mark('B') + "ls" + mark('C') + mark('D', ";0"))
        emu.feed("${esc}c")
        assertTrue(emu.shellCommandMarks().isEmpty())
    }

    @Test
    fun `marks on the alternate screen are not recorded`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 100)
        emu.feed("${esc}[?1049h") // enter the alt screen
        emu.feed(mark('A') + "$ " + mark('B') + "vim noise" + mark('C') + mark('D', ";0"))
        assertTrue(emu.shellCommandMarks().isEmpty())
    }

    @Test
    fun `a command finished on the alt screen keeps its exit code but not its end`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 100)
        emu.feed(mark('A') + "$ " + mark('B') + "watch -n1 date" + mark('C'))
        emu.feed("${esc}[?1049h") // the command took the screen
        emu.feed("TUI frame\r\n")
        emu.feed(mark('D', ";0")) // reported from inside the TUI, while it still owns the screen
        emu.feed("${esc}[?1049l")
        val m = emu.shellCommandMarks().single()
        assertEquals(0, m.exitCode)
        assertNull(m.endRow, "an alt-screen cursor position is not this command's end")
    }

    @Test
    fun `the mark list is capped`() {
        val emu = TerminalEmulator(cols = 20, rows = 4, maxScrollback = 1000)
        repeat(MAX_COMMAND_MARKS + 20) { n ->
            emu.feed(mark('A') + mark('B') + "c$n" + mark('C') + mark('D', ";0"))
        }
        assertEquals(MAX_COMMAND_MARKS, emu.shellCommandMarks().size)
        // The NEWEST survive — the cap keeps the tail, not the head, so the last mark must sit
        // on the last command's text (the commands wrap onto shared rows here, hence endsWith).
        assertTrue(emu.rowText(emu.shellCommandMarks().last().promptRow).trim().endsWith("c${MAX_COMMAND_MARKS + 19}"))
    }

    // --- Working directory ---------------------------------------------------

    @Test
    fun `osc 7 sets the working directory`() {
        val emu = TerminalEmulator(cols = 20, rows = 4)
        assertNull(emu.workingDirectory)
        emu.feed(cwd("file://web-1/home/deploy"))
        assertEquals<String?>("/home/deploy", emu.workingDirectory)
        emu.feed(cwd("file://web-1/tmp"))
        assertEquals<String?>("/tmp", emu.workingDirectory)
    }

    @Test
    fun `a bad osc 7 leaves the directory alone`() {
        val emu = TerminalEmulator(cols = 20, rows = 4)
        emu.feed(cwd("file://web-1/home/deploy"))
        emu.feed(cwd("not-a-path"))
        assertEquals<String?>("/home/deploy", emu.workingDirectory)
    }

    @Test
    fun `osc 7 draws nothing`() {
        val emu = TerminalEmulator(cols = 20, rows = 4)
        emu.feed(cwd("file://web-1/home") + "after")
        assertEquals("after", emu.lines.joinToString("") { row -> row.joinToString("") { it.text } }.trim())
    }
}
