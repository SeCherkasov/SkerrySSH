package app.skerry.shared.terminal

import app.skerry.shared.runbook.RunbookMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step marks (OSC 8375): the out-of-band channel a runbook step reports its status through. The
 * emulator must draw nothing for them, hand the runner the exit code, and cut the step's own output
 * out of the buffer between the opening and the closing mark.
 */
class TerminalStepMarkTest {

    // ESC/BEL by number — no invisible control bytes in the source.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    private val marks = mutableListOf<TerminalStepMark>()

    /** An emulator already waiting on [token] — the runner declares a step before sending it. */
    private fun emulator(cols: Int = 80, rows: Int = 24, scrollback: Int = 1000, token: String = "t") =
        TerminalEmulator(cols = cols, rows = rows, maxScrollback = scrollback, onStepMark = { marks += it })
            .apply { expectStep(token) }

    private fun TerminalEmulator.feed(text: String) = feed(text.encodeToByteArray())

    /** Visible screen text: rows joined by \n, trailing spaces and empty rows trimmed. */
    private fun TerminalEmulator.asText(): String =
        lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }.trimEnd('\n')

    private fun open(token: String = "t") = "$esc]$STEP_MARK_OSC;$token;$bel"

    private fun close(token: String, exitCode: Int) = "$esc]$STEP_MARK_OSC;$token;$exitCode$bel"

    @Test
    fun `a step reports its exit code and prints nothing`() {
        val emu = emulator(token = "sk_run_0")
        emu.feed("host:~# echo hi; probe\r\n")
        emu.feed(open("sk_run_0"))
        emu.feed("hi\r\n")
        emu.feed(close("sk_run_0", 0))

        assertEquals(listOf(TerminalStepMark("sk_run_0", 0, "hi")), marks)
        assertEquals("host:~# echo hi; probe\nhi", emu.asText())
    }

    @Test
    fun `output is everything printed between the two marks, in order`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("Reading package lists...\r\nBuilding dependency tree...\r\n2 upgraded\r\n")
        emu.feed(close("t", 0))

        assertEquals("Reading package lists...\nBuilding dependency tree...\n2 upgraded", marks.single().output)
    }

    @Test
    fun `a step that printed nothing reports empty output rather than the prompt around it`() {
        val emu = emulator()
        emu.feed("host:~# true; probe\r\n")
        emu.feed(open())
        emu.feed(close("t", 0))
        // The next prompt lands on the very row the mark closed on; it must not become the output.
        emu.feed("host:~# ")

        assertEquals("", marks.single().output)
    }

    @Test
    fun `a non-zero status is reported as it is`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("curl: (7) Failed to connect\r\n")
        emu.feed(close("t", 7))

        assertEquals(7, marks.single().exitCode)
        assertEquals("curl: (7) Failed to connect", marks.single().output)
    }

    @Test
    fun `output stops at the cursor, so a partial last line is not padded with the row`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("no newline here")
        emu.feed(close("t", 0))

        assertEquals("no newline here", marks.single().output)
    }

    @Test
    fun `a soft-wrapped line is joined back into one`() {
        val emu = emulator(cols = 10)
        emu.feed(open())
        emu.feed("0123456789abcde\r\n")
        emu.feed(close("t", 0))

        assertEquals("0123456789abcde", marks.single().output)
    }

    @Test
    fun `a mark that shares its chunk with the next prompt is cut at the mark, not at the chunk`() {
        // A PTY read respects no boundary of ours: the shell writes the closing probe and the next
        // prompt in one go, so both land in a single feed. The output must end where the mark did —
        // and the bytes after it must go on being drawn as ordinary text.
        val emu = emulator()
        emu.feed(open() + "hi\r\n" + close("t", 0) + "host:~# ")

        assertEquals(TerminalStepMark("t", 0, "hi"), marks.single())
        assertEquals("hi\nhost:~#", emu.asText())
    }

    @Test
    fun `a closing mark without an opening one still reports the status`() {
        // The opening probe can be lost (a shell that swallowed it, a run resumed after a reset);
        // the exit code is the part the run cannot do without.
        val emu = emulator()
        emu.feed("output nobody framed\r\n")
        emu.feed(close("t", 3))

        assertEquals(TerminalStepMark("t", 3, null), marks.single())
    }

    @Test
    fun `a flood of output is cut to its tail at a row boundary`() {
        val emu = emulator(scrollback = 10_000)
        emu.feed(open())
        repeat(5_000) { emu.feed("line ${it + 1}\r\n") }
        emu.feed(close("t", 0))

        val output = assertNotNull(marks.single().output)
        assertTrue(output.length <= STEP_MARK_OUTPUT_LIMIT, "kept ${output.length} chars")
        assertTrue(output.endsWith("line 5000"), "the tail is the part worth keeping")
        assertTrue(output.lineSequence().first().startsWith("line "), "a cut must not leave half a line")
    }

    @Test
    fun `output that outlived the scrollback keeps what is left instead of nothing`() {
        val emu = emulator(rows = 4, scrollback = 8)
        emu.feed(open())
        repeat(40) { emu.feed("line ${it + 1}\r\n") }
        emu.feed(close("t", 0))

        val output = assertNotNull(marks.single().output)
        assertTrue(output.endsWith("line 40"), output)
        assertTrue(output.lineSequence().count() <= 12, "only what the buffer still holds: $output")
    }

    @Test
    fun `a status that is not a number ends the step instead of leaving the run waiting`() {
        // Non-ASCII digits are not digits either: an exit code is what the shell printed, not a
        // number some locale would parse. The mark still carries this step's token, and the probe
        // that emitted it has already run — dropping it would hang the run until the watchdog.
        val emu = emulator()
        emu.feed(open())
        emu.feed("$esc]$STEP_MARK_OSC;t;٤$bel")

        assertEquals(UNREADABLE_STATUS, marks.single().exitCode)
    }

    @Test
    fun `a status too long to be a number ends the step too`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("$esc]$STEP_MARK_OSC;t;${"9".repeat(40)}$bel")

        assertEquals(UNREADABLE_STATUS, marks.single().exitCode)
    }

    @Test
    fun `an overlong token is refused rather than parked in the terminal`() {
        val emu = emulator(token = "t".repeat(MAX_STEP_MARK_TOKEN + 1))
        emu.feed(open())
        emu.feed("$esc]$STEP_MARK_OSC;${"t".repeat(MAX_STEP_MARK_TOKEN + 1)};0$bel")

        assertTrue(marks.isEmpty())
    }

    @Test
    fun `the mark is terminated by ST as well as by BEL`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("done\r\n")
        emu.feed("$esc]$STEP_MARK_OSC;t;0$esc\\")

        assertEquals(TerminalStepMark("t", 0, "done"), marks.single())
    }

    @Test
    fun `a step that ran inside a fullscreen program reports no output`() {
        // The alt screen has no scrollback and its rows are the TUI's, not the step's: reporting
        // them as "what the command printed" would be an invention.
        val emu = emulator()
        emu.feed(open())
        emu.feed("$esc[?1049h")
        emu.feed("vim is drawing here")
        emu.feed(close("t", 0))

        assertEquals(TerminalStepMark("t", 0, null), marks.single())
    }

    @Test
    fun `clearing history drops the capture instead of quoting whatever survived`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("before the clear\r\n")
        emu.feed("$esc[3J")
        emu.feed(close("t", 0))

        assertNull(marks.single().output, "lost is not the same as empty: the panel says so")
    }

    @Test
    fun `a resize between the two marks drops the capture`() {
        // Reflow rebuilds history, so the row the opening mark pointed at no longer exists.
        val emu = emulator(cols = 20, rows = 6)
        emu.feed(open())
        emu.feed("before the resize\r\n")
        emu.resize(40, 6)
        emu.feed(close("t", 0))

        assertNull(marks.single().output)
    }

    @Test
    fun `a full reset between the marks still reports the status`() {
        // RIS wipes the buffer the capture pointed into, but the step itself is unaffected: its
        // probe has yet to run, and the run needs the status far more than the text.
        val emu = emulator()
        emu.feed(open())
        emu.feed("before the reset\r\n")
        emu.feed("${esc}c")
        emu.feed(close("t", 0))

        assertEquals(TerminalStepMark("t", 0, null), marks.single())
    }

    @Test
    fun `a step that printed nothing is told apart from a capture that was lost`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed(close("t", 0))

        assertEquals("", marks.single().output, "the command really did print nothing")
    }

    @Test
    fun `output surviving a scrollback overrun starts at the row it kept, not at a stale column`() {
        // The step opened mid-row (the prompt is still on it), so the mark carries a column. Once
        // that row is trimmed away the column belongs to a row that no longer exists, and keeping it
        // would eat the first characters — here the whole first line — of the row that survived.
        // The prompt is the only difference between the two runs, and it is in the part that went.
        assertEquals(
            overrunOutput(prompt = ""),
            overrunOutput(prompt = "root@host:~# "),
            "the column of a trimmed-away row must not cut the surviving head row",
        )
    }

    /** A step that printed far past the scrollback, opened after [prompt] on the same row. */
    private fun overrunOutput(prompt: String): String {
        val captured = mutableListOf<TerminalStepMark>()
        val emu = TerminalEmulator(cols = 80, rows = 4, maxScrollback = 8, onStepMark = { captured += it })
        emu.expectStep("t")
        emu.feed(prompt + open())
        repeat(40) { emu.feed("line ${it + 1}\r\n") }
        emu.feed(close("t", 0))
        return assertNotNull(captured.single().output)
    }

    @Test
    fun `a second step captures from its own opening mark`() {
        val emu = emulator(token = "a")
        emu.feed(open("a"))
        emu.feed("one\r\n")
        emu.feed(close("a", 0))
        emu.expectStep("b")
        emu.feed("host:~# echo two; probe\r\n")
        emu.feed(open("b"))
        emu.feed("two\r\n")
        emu.feed(close("b", 0))

        assertEquals(listOf("one", "two"), marks.map { it.output })
    }

    @Test
    fun `the echo of a step's probes never reaches the screen`() {
        // The two sides put together: the runbook builds the line, the PTY echoes it as typed, and
        // the screen must show the operator's command alone. This is the whole of issue #158 — the
        // status used to be a printed line, and the probes were noise on top of it.
        val token = RunbookMarker.token("run", 0)
        val command = "ls -la"
        val emu = TerminalEmulator(onStepMark = { marks += it })
        emu.expectStep(token, RunbookMarker.echoFragments(command, token))

        emu.feed("root@host:~# ")
        emu.feed(RunbookMarker.probeLine(command, token) + "\r\n") // the PTY echoing the typed line
        emu.feed(open(token) + "total 188\r\n" + close(token, 0))

        assertEquals("root@host:~# ls -la\ntotal 188", emu.asText())
        assertEquals(TerminalStepMark(token, 0, "total 188"), marks.single())
    }

    @Test
    fun `the echo of a multi-line step's probes never reaches the screen either`() {
        // What a real PTY sends, and what the first attempt at this got wrong: every newline comes
        // back as CR LF, and the shell prints its continuation prompt before the next line. A hidden
        // fragment spanning a line break would never match, and the closing probe would be on screen.
        val token = RunbookMarker.token("run", 0)
        val command = "cd /opt\nls -la"
        val emu = TerminalEmulator(onStepMark = { marks += it })
        emu.expectStep(token, RunbookMarker.echoFragments(command, token))

        emu.feed("root@host:~# ")
        emu.feed(RunbookMarker.probeLine(command, token).replace("\n", "\r\n> ") + "\r\n")
        emu.feed(open(token) + "total 4\r\n" + close(token, 0))

        assertFalse(emu.asText().contains("printf"), emu.asText())
        assertTrue(emu.asText().contains("cd /opt"), emu.asText())
        assertTrue(emu.asText().contains("ls -la"), emu.asText())
    }

    @Test
    fun `the closing probe stays hidden when the opening mark lands between the echoed lines`() {
        // The real order for a multi-line step: the shell runs its first line — which emits the
        // opening mark — while the rest of the line, closing probe and all, is still being echoed
        // back. Tearing the filter down at the opening mark put that probe back on the screen.
        val token = RunbookMarker.token("run", 0)
        val command = "cd /opt\nls -la"
        val emu = TerminalEmulator(onStepMark = { marks += it })
        emu.expectStep(token, RunbookMarker.echoFragments(command, token))
        val echoed = RunbookMarker.probeLine(command, token).split("\n")

        emu.feed("root@host:~# " + echoed[0] + "\r\n")
        emu.feed(open(token))
        emu.feed(echoed.drop(1).joinToString("\r\n> ", prefix = "> ") + "\r\n")
        emu.feed("total 4\r\n" + close(token, 0))

        assertFalse(emu.asText().contains("printf"), emu.asText())
        assertTrue(emu.asText().contains("cd /opt"), emu.asText())
        // The capture opens before the rest of the line is echoed, so the operator's own continuation
        // lines are part of it — the protocol is not.
        val output = assertNotNull(marks.single().output)
        assertTrue(output.endsWith("total 4"), output)
        assertFalse(output.contains("printf"), output)
    }

    @Test
    fun `an echo and both marks arriving in one read are still handled`() {
        // A fast local command comes back in a single PTY read: the echo of the line, the opening
        // mark, the output and the closing mark all in one feed, so the filter has to switch off
        // inside the same byte loop the parser is running in.
        val token = RunbookMarker.token("run", 0)
        val command = "echo hi"
        val emu = TerminalEmulator(onStepMark = { marks += it })
        emu.expectStep(token, RunbookMarker.echoFragments(command, token))

        emu.feed(
            "root@host:~# " + RunbookMarker.probeLine(command, token) + "\r\n" +
                open(token) + "hi\r\n" + close(token, 0) + "root@host:~# ",
        )

        assertEquals(TerminalStepMark(token, 0, "hi"), marks.single())
        assertEquals("root@host:~# echo hi\nhi\nroot@host:~#", emu.asText())
    }

    @Test
    fun `a shell that redraws the line mid-probe shows it rather than losing the text`() {
        // zsh's syntax highlighting re-renders what is being typed, so the echo can arrive with an
        // escape sequence through the middle of a probe. The filter must hand back what it held —
        // a probe on screen is a blemish, a swallowed command would be a lie.
        val token = RunbookMarker.token("run", 0)
        val emu = TerminalEmulator(onStepMark = { marks += it })
        emu.expectStep(token, RunbookMarker.echoFragments("ls", token))
        val probe = RunbookMarker.startProbe(token)

        emu.feed(probe.take(10) + "$esc[0m" + probe.drop(10) + "; ls\r\n")

        assertTrue(emu.asText().startsWith(probe.take(10)), emu.asText())
    }

    @Test
    fun `a mark for a step nobody is waiting on is ignored`() {
        // The token rides in plaintext on the very screen the host controls, so a hostile one can
        // repeat it. Closing a window this client never opened would blank the output the run screen
        // shows; reporting a status it invented would advance the run past a command still running.
        val emu = emulator(token = "mine")
        emu.feed(open("mine"))
        emu.feed("real output\r\n")
        emu.feed(close("theirs", 0))

        assertTrue(marks.isEmpty(), "a foreign token reports nothing")

        emu.feed(close("mine", 0))
        assertEquals(
            TerminalStepMark("mine", 0, "real output"),
            marks.single(),
            "and it did not take the capture window with it",
        )
    }

    @Test
    fun `a terminal expecting nothing captures nothing`() {
        val emu = emulator(token = "t")
        emu.expectStep(null)
        emu.feed(open())
        emu.feed("output of an ordinary session\r\n")
        emu.feed(close("t", 0))

        assertTrue(marks.isEmpty())
    }

    @Test
    fun `dropping the expectation drops a capture already in flight`() {
        val emu = emulator()
        emu.feed(open())
        emu.feed("half a step\r\n")
        emu.expectStep(null) // the user stopped the run

        emu.feed(close("t", 0))
        assertTrue(marks.isEmpty(), "the run is over; its output has no reason to be handed anywhere")
    }

    @Test
    fun `an unknown OSC code is still ignored`() {
        val emu = emulator()
        emu.feed("$esc]8376;t;0$bel")

        assertTrue(marks.isEmpty())
        assertNull(marks.firstOrNull())
    }
}
