package app.skerry.shared.runbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunbookMarkerTest {

    @Test
    fun `token keeps only characters that are safe in a shell word and in a search`() {
        val token = RunbookMarker.token("A1b2-c3/d4 e5'\$(x)", 7)
        assertTrue(token.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }, token)
        assertTrue(token.endsWith("_7__"), token)
    }

    @Test
    fun `tokens of different steps in one run differ`() {
        assertTrue(RunbookMarker.token("run-1", 0) != RunbookMarker.token("run-1", 1))
    }

    @Test
    fun `single-line command gets the probe on the same line`() {
        val line = RunbookMarker.probeLine("systemctl restart nginx", "TOK")
        assertEquals("systemctl restart nginx; ${RunbookMarker.probe("TOK")}", line)
    }

    @Test
    fun `multi-line command keeps its own lines and gets the probe on a new one`() {
        val line = RunbookMarker.probeLine("cd /opt\nls -la", "TOK")
        assertEquals("cd /opt\nls -la\n${RunbookMarker.probe("TOK")}", line)
    }

    @Test
    fun `trailing separator is not doubled`() {
        assertEquals("uptime; ${RunbookMarker.probe("TOK")}", RunbookMarker.probeLine("uptime;", "TOK"))
        assertEquals("sleep 5 & ${RunbookMarker.probe("TOK")}", RunbookMarker.probeLine("sleep 5 &", "TOK"))
    }

    @Test
    fun `trailing whitespace is trimmed before the separator`() {
        assertEquals("uptime; ${RunbookMarker.probe("TOK")}", RunbookMarker.probeLine("uptime   \n  ", "TOK"))
    }

    @Test
    fun `empty command sends the probe alone`() {
        assertEquals(RunbookMarker.probe("TOK"), RunbookMarker.probeLine("   ", "TOK"))
    }

    @Test
    fun `exit code is read back from the printed marker`() {
        val token = RunbookMarker.token("run", 0)
        assertEquals(0, RunbookMarker.exitCodeIn("some output\n$token:0\n$ ", token))
        assertEquals(127, RunbookMarker.exitCodeIn("$token:127", token))
    }

    @Test
    fun `the echoed probe line alone never reads as an exit code`() {
        // The PTY echoes the line we typed before the shell runs it. If that echo parsed as a
        // result, every step would report success the instant it was sent.
        val token = RunbookMarker.token("run", 3)
        val echoed = RunbookMarker.probeLine("systemctl restart nginx", token)
        assertNull(RunbookMarker.exitCodeIn(echoed, token))
    }

    @Test
    fun `the last marker wins`() {
        val token = RunbookMarker.token("run", 0)
        assertEquals(1, RunbookMarker.exitCodeIn("$token:0\nmore\n$token:1\n", token))
    }

    @Test
    fun `a marker of another step is not read`() {
        val mine = RunbookMarker.token("run", 0)
        val other = RunbookMarker.token("run", 1)
        assertNull(RunbookMarker.exitCodeIn("$other:0\n", mine))
    }

    @Test
    fun `marker without digits or with an unparsable number is not an exit code`() {
        val token = RunbookMarker.token("run", 0)
        assertNull(RunbookMarker.exitCodeIn("$token:\n", token))
        assertNull(RunbookMarker.exitCodeIn("$token:abc\n", token))
        assertNull(RunbookMarker.exitCodeIn("$token:99999999999999999\n", token))
    }

    @Test
    fun `digits are ASCII only`() {
        val token = RunbookMarker.token("run", 0)
        assertNull(RunbookMarker.exitCodeIn("$token:٤\n", token))
    }
}
