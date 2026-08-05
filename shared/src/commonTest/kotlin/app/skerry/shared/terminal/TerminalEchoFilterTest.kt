package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hiding the echo of what the client itself typed. The runbook probes are the only caller: they are
 * protocol, the command between them is the operator's, and a shell that echoes something else must
 * make the filter get out of the way rather than eat output.
 */
class TerminalEchoFilterTest {

    private fun TerminalEchoFilter.run(text: String): String =
        buildString { for (ch in text) append(filter(ch)) }

    @Test
    fun `the probes are swallowed and the command between them is printed`() {
        val filter = TerminalEchoFilter()
        filter.expect(listOf("probe-open; ", "; probe-close"))

        assertEquals("ls -la", filter.run("probe-open; ls -la; probe-close"))
    }

    @Test
    fun `nothing is filtered until a caller asks for it`() {
        val filter = TerminalEchoFilter()

        assertFalse(filter.active)
        assertEquals("probe-open; ls", filter.run("probe-open; ls"))
    }

    @Test
    fun `a filter that swallowed everything it was given switches itself off`() {
        val filter = TerminalEchoFilter()
        filter.expect(listOf("A", "B"))

        filter.run("AxB")

        assertFalse(filter.active, "with both fragments gone there is nothing left to look for")
    }

    @Test
    fun `whatever arrives before the echo is printed and does not throw the search off`() {
        // The shell's next prompt lands between the step being declared and its echo coming back.
        // Demanding the fragment at a fixed position would give up on the very first prompt.
        val filter = TerminalEchoFilter()
        filter.expect(listOf("probe-open; ", "; probe-close"))

        assertEquals("host:~# ls", filter.run("host:~# probe-open; ls; probe-close"))
    }

    @Test
    fun `a partial match that breaks is printed in full`() {
        val filter = TerminalEchoFilter()
        filter.expect(listOf("printf", "; end"))

        assertEquals("prints", filter.run("prints"))
        assertTrue(filter.active, "the echo may still be coming; only a full match retires a fragment")
    }

    @Test
    fun `a separator inside the command does not start eating it`() {
        // `;` opens the closing fragment, so a command with its own `;` puts the filter into a
        // partial match that has to break cleanly — with every character printed, in order.
        val filter = TerminalEchoFilter()
        filter.expect(listOf("open ", "; close"))

        assertEquals("cd /opt; ls", filter.run("open cd /opt; ls; close"))
    }

    @Test
    fun `a broken match that is itself the start of the fragment is matched again`() {
        val filter = TerminalEchoFilter()
        filter.expect(listOf("open ", ";; end"))

        // The first `;` starts the fragment, the second breaks it — and is the start of the real one.
        assertEquals("a;", filter.run("open a;;; end"))
    }

    @Test
    fun `stopping hands back what was held so nothing is lost`() {
        val filter = TerminalEchoFilter()
        filter.expect(listOf("open ", "; close"))
        filter.run("open ls;")

        assertEquals(";", filter.stop(), "the half-matched separator is the operator's until proven otherwise")
        assertFalse(filter.active)
    }

    @Test
    fun `each fragment is swallowed once`() {
        val filter = TerminalEchoFilter()
        filter.expect(listOf("A", "B"))

        assertEquals("", filter.run("AB"))
        assertTrue(filter.run("AB") == "AB", "the run is over; a later copy of the same text is output")
    }
}
