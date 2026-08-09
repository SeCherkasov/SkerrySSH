package app.skerry.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parser turning a free-form assistant reply into the panel's segments. Security-critical: a command
 * offered for execution must be a single line with no control or bidi characters, exactly like the
 * one-shot bar path ([AiReplyParser.sanitizeCommand]).
 */
class AssistantAnswerTest {

    @Test
    fun `prose without a fence is a single prose segment`() {
        val segments = AssistantAnswer.segments("The journal is the largest consumer.")

        assertEquals(1, segments.size)
        assertEquals(AssistantSegment.Prose("The journal is the largest consumer."), segments.single())
    }

    @Test
    fun `fenced block becomes a code segment with runnable commands`() {
        val raw = """
            Free the journal:
            ```bash
            journalctl --vacuum-size=500M
            docker system prune -af
            ```
            That reclaims about 12 GiB.
        """.trimIndent()

        val segments = AssistantAnswer.segments(raw)

        assertEquals(3, segments.size)
        assertEquals(AssistantSegment.Prose("Free the journal:"), segments[0])
        val code = assertIs<AssistantSegment.Code>(segments[1])
        assertEquals("journalctl --vacuum-size=500M\ndocker system prune -af", code.text)
        assertEquals(listOf("journalctl --vacuum-size=500M", "docker system prune -af"), code.commands)
        assertEquals(AssistantSegment.Prose("That reclaims about 12 GiB."), segments[2])
    }

    @Test
    fun `unterminated fence still yields a code segment while the reply streams`() {
        val segments = AssistantAnswer.segments("Try:\n```\ndf -h")

        assertEquals(2, segments.size)
        val code = assertIs<AssistantSegment.Code>(segments[1])
        assertEquals("df -h", code.text)
        assertEquals(listOf("df -h"), code.commands)
    }

    @Test
    fun `a CR cannot smuggle a second command into one visible line`() {
        // A bare CR would let "ls" carry "rm -rf /" past a user who only read the first word. Line
        // splitting normalizes it, so the second command is a separate, visible line — and each
        // runnable entry stays single-line, so one CR on send executes exactly what was displayed.
        val code = assertIs<AssistantSegment.Code>(AssistantAnswer.segments("```\nls\rrm -rf /\n```").single())

        assertEquals("ls\nrm -rf /", code.text)
        assertEquals(listOf("ls", "rm -rf /"), code.commands)
        assertTrue(code.commands.none { it.contains('\r') || it.contains('\n') })
    }

    @Test
    fun `invisible control and bidi characters are stripped from runnable commands`() {
        // A right-to-left override can make a command read as something else on screen; a BEL is
        // simply invisible. Neither may reach the shell. Escaped literals on purpose: a raw byte
        // here would be invisible in review and silently lost on edit.
        val raw = "```\nrm -rf \u202E/tmp\u0007\n```"

        val code = assertIs<AssistantSegment.Code>(AssistantAnswer.segments(raw).single())

        assertEquals(listOf("rm -rf /tmp"), code.commands)
    }

    @Test
    fun `comment and blank lines are shown but never offered as commands`() {
        val segments = AssistantAnswer.segments("```sh\n# frees the journal\n\njournalctl --vacuum-size=500M\n```")

        val code = assertIs<AssistantSegment.Code>(segments.single())
        assertEquals("# frees the journal\n\njournalctl --vacuum-size=500M", code.text)
        assertEquals(listOf("journalctl --vacuum-size=500M"), code.commands)
    }

    @Test
    fun `blank prose between fences is dropped`() {
        val segments = AssistantAnswer.segments("```\nls\n```\n\n\n```\ndf -h\n```")

        assertEquals(2, segments.size)
        assertTrue(segments.all { it is AssistantSegment.Code })
    }

    @Test
    fun `empty reply has no segments`() {
        assertEquals(emptyList(), AssistantAnswer.segments("   \n  "))
    }

    // --- inline spans (mono for `code`, bold for **text**) ---

    /**
     * The card shows [AssistantSegment.Code.text] and Run sends [AssistantSegment.Code.commands] —
     * two strings out of one block. A bidi override left in the shown one renders the line in an
     * order the shell never sees, which is the whole reason `isSafeTerminalInputChar` exists. Since
     * the block became selectable it is also an egress: what is swept up goes to the clipboard, and
     * a paste into the terminal is not filtered.
     */
    @Test
    fun `a fenced block is shown with bidi and control characters already stripped`() {
        val segments = AssistantAnswer.segments("```\necho safe\u202E; rm -rf /\n```")

        val code = segments.filterIsInstance<AssistantSegment.Code>().single()
        assertFalse(code.text.any { it == '\u202E' }, "the shown text still carries U+202E: `${'$'}{code.text}`")
        assertEquals(code.commands.single(), code.text)
    }

    /**
     * Prose is not inert either: `spans` renders a `` `…` `` run in the mono font, across newlines,
     * so a stretch of prose can look exactly like the command card next to it. Since the panel
     * became selectable it is one Ctrl+C and one paste away from the shell.
     */
    @Test
    fun `prose is shown with bidi and control characters already stripped`() {
        val segments = AssistantAnswer.segments("Run `echo safe\u202E; rm -rf /` when ready.")

        val prose = segments.filterIsInstance<AssistantSegment.Prose>().single()
        assertFalse(prose.text.any { it == '\u202E' }, "the shown prose still carries U+202E: `${'$'}{prose.text}`")
    }

    /**
     * The input predicate rejects U+200B..U+200F wholesale, which takes the zero-width joiner with
     * it: filtering prose with it splits a family emoji into three people and writes Persian without
     * its joins. Prose is shown, not run, so it keeps them — only the reordering characters go.
     */
    @Test
    fun `prose keeps the joiners that hold an emoji and a Persian word together`() {
        val segments = AssistantAnswer.segments("Try \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 and \u0645\u06CC\u200C\u0634\u0648\u062F.")

        val prose = segments.filterIsInstance<AssistantSegment.Prose>().single()
        assertTrue(prose.text.contains('\u200D'), "the emoji joiner was stripped: `${'$'}{prose.text}`")
        assertTrue(prose.text.contains('\u200C'), "the Persian non-joiner was stripped: `${'$'}{prose.text}`")
    }

    /** A block sits next to Run, so it keeps the stricter predicate: shown and executed are one string. */
    @Test
    fun `a fenced block drops the joiners prose keeps`() {
        val segments = AssistantAnswer.segments("```\nls \u200Dsrc\n```")

        val code = segments.filterIsInstance<AssistantSegment.Code>().single()
        assertFalse(code.text.any { it == '\u200D' }, "the block kept a zero-width joiner: `${'$'}{code.text}`")
        assertEquals(code.commands.single(), code.text)
    }

    @Test
    fun `a block of nothing but rejected characters is dropped, not shown empty`() {
        // trim() sees no whitespace in a bidi override, so the block survives the blank check and
        // only collapses after filtering — the one path that can make a segment disappear.
        val segments = AssistantAnswer.segments("Before.\n```\n\u202E\u200B\n```\nAfter.")

        assertTrue(segments.none { it is AssistantSegment.Code }, "an empty block was still rendered")
        assertEquals(listOf("Before.", "After."), segments.filterIsInstance<AssistantSegment.Prose>().map { it.text })
    }

    @Test
    fun `a line that filters away leaves no blank line behind`() {
        // `flush` trims before filtering, and U+200B is not whitespace to String.trim(): without a
        // second trim the block would open with an empty line and stop matching its command.
        val segments = AssistantAnswer.segments("```\n\u200B\nls\n```")

        val code = segments.filterIsInstance<AssistantSegment.Code>().single()
        assertEquals("ls", code.text)
        assertEquals(listOf("ls"), code.commands)
    }

    @Test
    fun `a block is still shown line by line once sanitized`() {
        val segments = AssistantAnswer.segments("```\n# keep this\nsystemctl daemon-reload\n```")

        val code = segments.filterIsInstance<AssistantSegment.Code>().single()
        assertEquals("# keep this\nsystemctl daemon-reload", code.text)
        assertEquals(listOf("systemctl daemon-reload"), code.commands)
    }

    @Test
    fun `inline backticks become mono spans`() {
        val spans = AssistantAnswer.spans("5.9 GiB in `/var/log/journal`, 87% full")

        assertEquals(
            listOf(
                AiSpan("5.9 GiB in "),
                AiSpan("/var/log/journal", mono = true),
                AiSpan(", 87% full"),
            ),
            spans,
        )
    }

    @Test
    fun `double asterisks become bold spans`() {
        val spans = AssistantAnswer.spans("The journal is **5.9 GiB**.")

        assertEquals(
            listOf(AiSpan("The journal is "), AiSpan("5.9 GiB", bold = true), AiSpan(".")),
            spans,
        )
    }

    @Test
    fun `unmatched markers stay literal text`() {
        assertEquals(listOf(AiSpan("100% of `disk")), AssistantAnswer.spans("100% of `disk"))
        assertEquals(listOf(AiSpan("2 ** 10")), AssistantAnswer.spans("2 ** 10"))
    }
}
