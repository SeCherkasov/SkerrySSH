package app.skerry.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
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
