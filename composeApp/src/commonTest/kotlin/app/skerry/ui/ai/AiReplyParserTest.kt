package app.skerry.ui.ai

import app.skerry.ui.ai.AiReplyParser.Reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Прямые тесты чистого разбора ответа модели (security-критичные функции бара подтверждения). */
class AiReplyParserTest {

    // --- sanitizeCommand ---

    @Test
    fun `sanitizeCommand keeps a plain command`() {
        assertEquals("ls -la", AiReplyParser.sanitizeCommand("ls -la"))
    }

    @Test
    fun `sanitizeCommand takes only the first non-blank line`() {
        val cmd = AiReplyParser.sanitizeCommand("ls -la\nrm -rf /\r\n")
        assertEquals("ls -la", cmd)
        assertFalse(cmd!!.contains('\n') || cmd.contains('\r'))
    }

    @Test
    fun `sanitizeCommand strips a fenced block with a language tag`() {
        assertEquals("free -h", AiReplyParser.sanitizeCommand("```bash\nfree -h\n```"))
    }

    @Test
    fun `sanitizeCommand strips a fenced block without a language tag`() {
        assertEquals("free -h", AiReplyParser.sanitizeCommand("```\nfree -h\n```"))
    }

    @Test
    fun `sanitizeCommand strips wrapping inline backticks`() {
        assertEquals("free -h", AiReplyParser.sanitizeCommand("`free -h`"))
    }

    @Test
    fun `sanitizeCommand removes bidi override characters (Trojan Source)`() {
        assertEquals("echo ab", AiReplyParser.sanitizeCommand("echo a\u202Eb"))
    }

    @Test
    fun `sanitizeCommand returns null for empty input`() {
        assertNull(AiReplyParser.sanitizeCommand("   \n  "))
    }

    // --- isSafeInputChar ---

    @Test
    fun `isSafeInputChar rejects bidi and zero-width characters`() {
        // RLO, LRE, LRI, ZWSP, LRM, BOM, soft hyphen, word joiner, line separator, ALM.
        listOf(0x202E, 0x202A, 0x2066, 0x200B, 0x200E, 0xFEFF, 0x00AD, 0x2060, 0x2028, 0x061C)
            .forEach { assertFalse(AiReplyParser.isSafeInputChar(it.toChar()), "ожидали запрет U+${it.toString(16)}") }
    }

    @Test
    fun `isSafeInputChar rejects control bytes except tab`() {
        assertFalse(AiReplyParser.isSafeInputChar('\n'))
        assertFalse(AiReplyParser.isSafeInputChar('\u0007'))
        assertTrue(AiReplyParser.isSafeInputChar('\t'))
        assertTrue(AiReplyParser.isSafeInputChar('a'))
        assertTrue(AiReplyParser.isSafeInputChar('я'))
    }

    // --- looksLikeProse ---

    @Test
    fun `looksLikeProse detects questions cyrillic and clarification phrases`() {
        assertTrue(AiReplyParser.looksLikeProse("Which directory?"))
        assertTrue(AiReplyParser.looksLikeProse("Уточните запрос"))
        assertTrue(AiReplyParser.looksLikeProse("please provide the path"))
        assertFalse(AiReplyParser.looksLikeProse("ls -la"))
        assertFalse(AiReplyParser.looksLikeProse("find /var/log -size +100M"))
    }

    // --- extractDescription ---

    @Test
    fun `extractDescription takes the second non-empty line and cleans markers`() {
        assertEquals("lists files", AiReplyParser.extractDescription("ls -la\n- `lists files`"))
    }

    @Test
    fun `extractDescription caps at 120 characters and returns null when absent`() {
        assertNull(AiReplyParser.extractDescription("ls -la"))
        val long = "ls\n" + "x".repeat(300)
        assertEquals(120, AiReplyParser.extractDescription(long)!!.length)
    }

    // --- parse ---

    @Test
    fun `parse reads the CMD and INFO format`() {
        val reply = assertIs<Reply.Command>(AiReplyParser.parse("CMD: ls -la\nINFO: lists files in long format"))
        assertEquals("ls -la", reply.command)
        assertEquals("lists files in long format", reply.info)
    }

    @Test
    fun `parse maps ASK to a message`() {
        val reply = assertIs<Reply.Ask>(AiReplyParser.parse("ASK: Which directory should I search?"))
        assertEquals("Which directory should I search?", reply.text)
    }

    @Test
    fun `parse falls back to the first line as a command with a description`() {
        val reply = assertIs<Reply.Command>(AiReplyParser.parse("uptime\nshows uptime"))
        assertEquals("uptime", reply.command)
        assertEquals("shows uptime", reply.info)
    }

    @Test
    fun `parse treats a hash-prefixed refusal as Ask`() {
        val reply = assertIs<Reply.Ask>(AiReplyParser.parse("# I cannot do that safely"))
        assertEquals("I cannot do that safely", reply.text)
    }

    @Test
    fun `parse treats unmarked prose as Prose not a command`() {
        val reply = assertIs<Reply.Prose>(AiReplyParser.parse("Уточните запрос, пожалуйста."))
        assertEquals("Уточните запрос, пожалуйста.", reply.text)
    }

    @Test
    fun `parse returns NoCommand for an empty reply`() {
        assertEquals(Reply.NoCommand, AiReplyParser.parse("   "))
    }

    @Test
    fun `parse ignores a CMD line that is itself prose`() {
        // CMD-строка с прозой не должна попасть в слот с кнопкой Run; ASK берёт верх.
        val reply = AiReplyParser.parse("CMD: please clarify the request\nASK: What exactly?")
        assertIs<Reply.Ask>(reply)
    }
}
