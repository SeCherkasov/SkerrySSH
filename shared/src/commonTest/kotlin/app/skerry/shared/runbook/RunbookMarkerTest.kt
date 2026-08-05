package app.skerry.shared.runbook

import app.skerry.shared.terminal.STEP_MARK_OSC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunbookMarkerTest {

    /** What [RunbookMarker.probeLine] produces once the opening probe is put in front of [body]. */
    private fun opened(body: String, token: String = "TOK") = "${RunbookMarker.startProbe(token)}; $body"

    private fun probe(token: String = "TOK") = RunbookMarker.probe(token)

    @Test
    fun `token keeps only characters that are safe in a shell word and in the mark`() {
        val token = RunbookMarker.token("A1b2-c3/d4 e5'\$(x)", 7)
        assertTrue(token.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }, token)
        assertTrue(token.endsWith("_7"), token)
    }

    @Test
    fun `tokens of different steps in one run differ`() {
        assertTrue(RunbookMarker.token("run-1", 0) != RunbookMarker.token("run-1", 1))
    }

    @Test
    fun `the probes are escape sequences the terminal swallows, not printed lines`() {
        // The whole point of the OSC form: nothing of the protocol reaches the screen. The printed
        // marker it replaces left two rows of noise per step in the session the user is watching.
        val token = RunbookMarker.token("run", 0)
        assertEquals("printf '\\033]$STEP_MARK_OSC;$token;\\a'", RunbookMarker.startProbe(token))
        assertEquals("printf '\\033]$STEP_MARK_OSC;$token;%s\\a' \"\$?\"", RunbookMarker.probe(token))
        assertTrue(RunbookMarker.probe(token).none { it == '\n' }, "the probe prints no line of its own")
    }

    @Test
    fun `single-line command is framed by the two probes`() {
        val line = RunbookMarker.probeLine("systemctl restart nginx", "TOK")
        assertEquals(opened("systemctl restart nginx; ${probe()}"), line)
    }

    @Test
    fun `multi-line command keeps its own lines and gets the closing probe on a new one`() {
        val line = RunbookMarker.probeLine("cd /opt\nls -la", "TOK")
        assertEquals(opened("cd /opt\nls -la\n${probe()}"), line)
    }

    @Test
    fun `trailing separator is not doubled`() {
        assertEquals(opened("uptime; ${probe()}"), RunbookMarker.probeLine("uptime;", "TOK"))
        assertEquals(opened("sleep 5 & ${probe()}"), RunbookMarker.probeLine("sleep 5 &", "TOK"))
    }

    @Test
    fun `trailing whitespace is trimmed before the separator`() {
        assertEquals(opened("uptime; ${probe()}"), RunbookMarker.probeLine("uptime   \n  ", "TOK"))
    }

    @Test
    fun `empty command sends the probes alone`() {
        assertEquals(opened(probe()), RunbookMarker.probeLine("   ", "TOK"))
    }

    @Test
    fun `a command ending in a comment gets the closing probe on its own line`() {
        // `uptime # after deploy` with the probe appended on the same line would swallow the probe
        // into the comment: the step would run fine and never report, hanging the run forever.
        val line = RunbookMarker.probeLine("uptime # after deploy", "TOK")
        assertEquals(opened("uptime # after deploy\n${probe()}"), line)
    }

    @Test
    fun `a hash inside quotes is not a comment`() {
        assertEquals(opened("echo '#1'; ${probe()}"), RunbookMarker.probeLine("echo '#1'", "TOK"))
        assertEquals(opened("grep \"#tag\" f; ${probe()}"), RunbookMarker.probeLine("grep \"#tag\" f", "TOK"))
    }

    @Test
    fun `a hash glued to a word is not a comment`() {
        assertEquals(opened("echo a#b; ${probe()}"), RunbookMarker.probeLine("echo a#b", "TOK"))
    }

    @Test
    fun `a command ending in a doubled separator gets the closing probe on its own line`() {
        // `a && b &&; probe` is a syntax error, so the shell runs NOTHING on that line — not even
        // the part that was valid — and the step hangs with no mark.
        assertEquals(opened("a && b &&\n${probe()}"), RunbookMarker.probeLine("a && b &&", "TOK"))
        assertEquals(opened("a ;;\n${probe()}"), RunbookMarker.probeLine("a ;;", "TOK"))
    }

    @Test
    fun `a command ending in a pipe or logical operator gets the closing probe on its own line`() {
        assertEquals(opened("a &&\n${probe()}"), RunbookMarker.probeLine("a &&", "TOK"))
        assertEquals(opened("a |\n${probe()}"), RunbookMarker.probeLine("a |", "TOK"))
    }

    @Test
    fun `a command ending in a line continuation gets the closing probe past a blank line`() {
        // A trailing backslash swallows whichever separator comes next: `cmd \; probe` runs `cmd`
        // with the whole probe as its arguments, and `cmd \` followed by a plain newline does the
        // same. Verified against bash. Only a blank line ends the command — the continuation joins
        // with the empty line, and the newline after that terminates it.
        assertEquals(
            opened("docker run -d --rm nginx \\\n\n${probe()}"),
            RunbookMarker.probeLine("docker run -d --rm nginx \\", "TOK"),
        )
    }

    @Test
    fun `a continuation on the last line of a multi-line command is absorbed too`() {
        // The multi-line branch would otherwise hand this to the shell as `… nginx \` + newline +
        // probe, which is the same swallowed probe one line further down.
        assertEquals(
            opened("docker run \\\n  --rm nginx \\\n\n${probe()}"),
            RunbookMarker.probeLine("docker run \\\n  --rm nginx \\", "TOK"),
        )
    }

    @Test
    fun `three trailing backslashes are still a continuation`() {
        // The rule is parity, not "ends with one but not two". An implementation that only looked at
        // the last two characters would call this complete and swallow the probe again.
        assertEquals(opened("cmd \\\\\\\n\n${probe()}"), RunbookMarker.probeLine("cmd \\\\\\", "TOK"))
    }

    @Test
    fun `an escaped backslash ends the command and keeps the probe on the same line`() {
        // `cmd \\` is a literal backslash, not a continuation; only an odd number of them continues
        // the line. Verified against bash: the ordinary `;` form runs here.
        assertEquals(opened("echo a \\\\; ${probe()}"), RunbookMarker.probeLine("echo a \\\\", "TOK"))
    }

    @Test
    fun `the fragments hidden from the echo are exactly the line minus the command`() {
        // The terminal hides these from the echo; if they ever stopped adding up to the whole line
        // around the command, the user would see half a probe or lose part of their own command.
        for (command in listOf("uptime", "cd /opt\nls -la", "a &&", "uptime # note", "cmd \\", "sleep 5 &")) {
            val (opening, closing) = RunbookMarker.echoFragments(command, "TOK")
            val line = RunbookMarker.probeLine(command, "TOK")

            assertTrue(line.startsWith(opening), command)
            assertTrue(line.endsWith(closing), command)
            assertEquals(
                command.trimEnd(),
                line.removePrefix(opening).removeSuffix(closing).trimEnd('\n'),
                "what is left between the fragments is the operator's command and line breaks: $command",
            )
        }
    }

    @Test
    fun `no hidden fragment carries a line break`() {
        // A PTY echoes a newline as CR LF and the shell prints its continuation prompt between the
        // lines, so a fragment spanning a line break would never match the echo — and the closing
        // probe of every multi-line step would be back on the user's screen (issue #158).
        for (command in listOf("uptime", "cd /opt\nls -la", "a &&", "uptime # note", "cmd \\")) {
            for (fragment in RunbookMarker.echoFragments(command, "TOK")) {
                assertTrue(fragment.none { it == '\n' || it == '\r' }, "$command -> $fragment")
            }
        }
    }

    @Test
    fun `the opening probe cannot be swallowed by the command it opens`() {
        // It stands before the command with its own separator, so a comment, a here-doc or a dangling
        // operator inside the step can only affect what comes after it.
        val lines = listOf("uptime # note", "cat <<EOF\nbody\nEOF", "a &&", "cmd \\")
        for (command in lines) {
            assertTrue(
                RunbookMarker.probeLine(command, "TOK").startsWith("${RunbookMarker.startProbe("TOK")}; "),
                command,
            )
        }
    }
}
