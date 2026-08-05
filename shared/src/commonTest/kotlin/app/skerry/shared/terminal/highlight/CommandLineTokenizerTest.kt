package app.skerry.shared.terminal.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandLineTokenizerTest {

    private val vocab = SessionVocabulary()

    private fun tokens(line: String): List<HighlightSpan> {
        val spans = tokenizeCommandLine(line, vocab)
        assertSpansSane(line, spans)
        return spans
    }

    /** Every caller maps spans onto grid columns, so overlap or an out-of-range end corrupts the row. */
    private fun assertSpansSane(line: String, spans: List<HighlightSpan>) {
        var prevEnd = 0
        for (span in spans) {
            assertTrue(span.start >= prevEnd, "spans overlap or unsorted in `$line`: $spans")
            assertTrue(span.start < span.endExclusive, "empty span in `$line`: $span")
            assertTrue(span.endExclusive <= line.length, "span past end in `$line`: $span")
            prevEnd = span.endExclusive
        }
    }

    private fun textOf(line: String, span: HighlightSpan) = line.substring(span.start, span.endExclusive)

    private fun kindOf(line: String, text: String): HighlightKind? =
        tokens(line).firstOrNull { textOf(line, it) == text }?.kind

    @Test
    fun `known command and its subcommand`() {
        val line = "git status"
        assertEquals(HighlightKind.Command, kindOf(line, "git"))
        assertEquals(HighlightKind.Subcommand, kindOf(line, "status"))
    }

    @Test
    fun `unknown command is left alone`() {
        assertTrue(tokens("frobnicate bar").isEmpty())
    }

    @Test
    fun `subcommand of another command is not highlighted`() {
        assertEquals(null, kindOf("git restart", "restart"))
    }

    @Test
    fun `options and absolute paths`() {
        val line = "ls -la /var/log"
        assertEquals(HighlightKind.Command, kindOf(line, "ls"))
        assertEquals(HighlightKind.Option, kindOf(line, "-la"))
        assertEquals(HighlightKind.PathLit, kindOf(line, "/var/log"))
    }

    @Test
    fun `long options and home-relative paths`() {
        val line = "cat --number ~/.ssh/config ./local.txt"
        assertEquals(HighlightKind.Option, kindOf(line, "--number"))
        assertEquals(HighlightKind.PathLit, kindOf(line, "~/.ssh/config"))
        assertEquals(HighlightKind.PathLit, kindOf(line, "./local.txt"))
    }

    @Test
    fun `a lone dash is not an option`() {
        assertEquals(null, kindOf("cat -", "-"))
    }

    @Test
    fun `pipe reopens the command position`() {
        val line = "echo hello | grep -i x"
        assertEquals(HighlightKind.Command, kindOf(line, "echo"))
        assertEquals(HighlightKind.Operator, kindOf(line, "|"))
        assertEquals(HighlightKind.Command, kindOf(line, "grep"))
        assertEquals(HighlightKind.Option, kindOf(line, "-i"))
    }

    @Test
    fun `quoted string includes its quotes`() {
        val line = "echo \"hello world\""
        assertEquals(HighlightKind.StringLit, kindOf(line, "\"hello world\""))
    }

    @Test
    fun `an operator inside quotes is text`() {
        val line = "echo 'a | b'"
        assertEquals(HighlightKind.StringLit, kindOf(line, "'a | b'"))
        assertTrue(tokens(line).none { it.kind == HighlightKind.Operator })
    }

    @Test
    fun `escaped quote does not close the string`() {
        val line = "echo \"a \\\" b\" tail"
        assertEquals(HighlightKind.StringLit, kindOf(line, "\"a \\\" b\""))
    }

    @Test
    fun `unterminated quote runs to end of line`() {
        val line = "echo \"unfinished"
        val string = tokens(line).single { it.kind == HighlightKind.StringLit }
        assertEquals(line.length, string.endExclusive)
    }

    @Test
    fun `variables in their three forms`() {
        val line = "echo \$HOME \${XDG_DIR} \$1"
        val vars = tokens(line).filter { it.kind == HighlightKind.Variable }.map { textOf(line, it) }
        assertEquals(listOf("\$HOME", "\${XDG_DIR}", "\$1"), vars)
    }

    @Test
    fun `an assignment prefix keeps the command position`() {
        val line = "FOO=1 make build"
        assertEquals(HighlightKind.Variable, kindOf(line, "FOO="))
        assertEquals(HighlightKind.Command, kindOf(line, "make"))
    }

    @Test
    fun `sudo keeps the command position open`() {
        val line = "sudo systemctl restart nginx"
        assertEquals(HighlightKind.Command, kindOf(line, "sudo"))
        assertEquals(HighlightKind.Command, kindOf(line, "systemctl"))
        assertEquals(HighlightKind.Subcommand, kindOf(line, "restart"))
        assertEquals(null, kindOf(line, "nginx"))
    }

    @Test
    fun `redirect is an operator and its target stays a path`() {
        val line = "cat img > /dev/sda"
        assertEquals(HighlightKind.Operator, kindOf(line, ">"))
        assertEquals(HighlightKind.PathLit, kindOf(line, "/dev/sda"))
    }

    @Test
    fun `boolean operators`() {
        val line = "make && echo ok || echo fail"
        val ops = tokens(line).filter { it.kind == HighlightKind.Operator }.map { textOf(line, it) }
        assertEquals(listOf("&&", "||"), ops)
    }

    @Test
    fun `semicolon separates and reopens`() {
        val line = "cd /tmp; ls"
        assertEquals(HighlightKind.Operator, kindOf(line, ";"))
        assertEquals(HighlightKind.Command, kindOf(line, "ls"))
    }

    @Test
    fun `comment runs to end of line`() {
        val line = "make build # rebuild everything"
        val comment = tokens(line).single { it.kind == HighlightKind.Comment }
        assertEquals("# rebuild everything", textOf(line, comment))
    }

    @Test
    fun `a hash inside a word is not a comment`() {
        val line = "echo \"#1\""
        assertTrue(tokens(line).none { it.kind == HighlightKind.Comment })
    }

    @Test
    fun `an executable path is a path, not a command`() {
        assertEquals(HighlightKind.PathLit, kindOf("./deploy.sh prod", "./deploy.sh"))
        assertEquals(HighlightKind.PathLit, kindOf("/usr/bin/git status", "/usr/bin/git"))
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(tokens("").isEmpty())
        assertTrue(tokens("     ").isEmpty())
    }

    @Test
    fun `a partially typed command is not green yet`() {
        assertEquals(null, kindOf("gi", "gi"))
        assertEquals(HighlightKind.Command, kindOf("git", "git"))
    }

    @Test
    fun `overlong input is truncated instead of scanned whole`() {
        val line = "echo " + "x".repeat(MAX_HIGHLIGHT_LENGTH * 2)
        val spans = tokenizeCommandLine(line, vocab)
        assertTrue(spans.all { it.endExclusive <= MAX_HIGHLIGHT_LENGTH })
    }

    @Test
    fun `option carrying a quoted value keeps both parts`() {
        val line = "git commit --message=\"fix bug\""
        assertEquals(HighlightKind.Option, kindOf(line, "--message="))
        assertEquals(HighlightKind.StringLit, kindOf(line, "\"fix bug\""))
    }
}
