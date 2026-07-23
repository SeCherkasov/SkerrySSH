package app.skerry.shared.snippet

import app.skerry.shared.snippet.SnippetSegment.Literal
import app.skerry.shared.snippet.SnippetSegment.Variable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetTemplateTest {

    // --- parsing ---

    @Test
    fun `plain text is a single literal`() {
        assertEquals(listOf(Literal("df -h")), SnippetTemplate.parse("df -h"))
        assertFalse(SnippetTemplate.hasVariables("df -h"))
    }

    @Test
    fun `builtin variable without format`() {
        val segments = SnippetTemplate.parse("echo ${'$'}{{date}}")
        assertEquals(
            listOf(Literal("echo "), Variable(SnippetVariableKind.DATE, "date", null, "${'$'}{{date}}")),
            segments,
        )
        assertTrue(SnippetTemplate.hasVariables("echo ${'$'}{{date}}"))
    }

    @Test
    fun `builtin variable with format`() {
        val v = SnippetTemplate.parse("${'$'}{{date:YYYYMMDD}}").single() as Variable
        assertEquals(SnippetVariableKind.DATE, v.kind)
        assertEquals("YYYYMMDD", v.format)
    }

    @Test
    fun `all builtin names map to their kinds`() {
        fun kindOf(cmd: String) = (SnippetTemplate.parse(cmd).single() as Variable).kind
        assertEquals(SnippetVariableKind.TIME, kindOf("${'$'}{{time}}"))
        assertEquals(SnippetVariableKind.TIMESTAMP, kindOf("${'$'}{{timestamp}}"))
        assertEquals(SnippetVariableKind.UUID, kindOf("${'$'}{{uuid}}"))
        assertEquals(SnippetVariableKind.RANDOM, kindOf("${'$'}{{random:4}}"))
        assertEquals(SnippetVariableKind.CLIPBOARD, kindOf("${'$'}{{clipboard}}"))
        assertEquals(SnippetVariableKind.VAULT, kindOf("${'$'}{{vault:prod-db}}"))
    }

    @Test
    fun `vault reference keeps the entry name as format`() {
        val v = SnippetTemplate.parse("${'$'}{{vault:prod db}}").single() as Variable
        assertEquals(SnippetVariableKind.VAULT, v.kind)
        assertEquals("prod db", v.format)
    }

    @Test
    fun `unknown name is a prompted parameter, format is its default value`() {
        val bare = SnippetTemplate.parse("${'$'}{{container}}").single() as Variable
        assertEquals(SnippetVariableKind.PARAM, bare.kind)
        assertEquals("container", bare.name)
        assertNull(bare.format)

        val withDefault = SnippetTemplate.parse("${'$'}{{container:web-1}}").single() as Variable
        assertEquals("web-1", withDefault.format)
    }

    @Test
    fun `literals around and between variables are preserved`() {
        val segments = SnippetTemplate.parse("tar -czf logs_${'$'}{{date}}_${'$'}{{time}}.tar.gz /var/log/")
        assertEquals(
            listOf(
                Literal("tar -czf logs_"),
                Variable(SnippetVariableKind.DATE, "date", null, "${'$'}{{date}}"),
                Literal("_"),
                Variable(SnippetVariableKind.TIME, "time", null, "${'$'}{{time}}"),
                Literal(".tar.gz /var/log/"),
            ),
            segments,
        )
    }

    @Test
    fun `malformed placeholders stay literal`() {
        assertEquals(listOf(Literal("echo ${'$'}{{date")), SnippetTemplate.parse("echo ${'$'}{{date"))
        assertEquals(listOf(Literal("echo ${'$'}{date}")), SnippetTemplate.parse("echo ${'$'}{date}"))
        assertEquals(listOf(Literal("echo ${'$'}{{1bad}}")), SnippetTemplate.parse("echo ${'$'}{{1bad}}"))
        assertEquals(listOf(Literal("echo ${'$'}{{}}")), SnippetTemplate.parse("echo ${'$'}{{}}"))
        // A vault reference needs an entry name — not a lookup for a credential labeled "".
        assertEquals(listOf(Literal("echo ${'$'}{{vault}}")), SnippetTemplate.parse("echo ${'$'}{{vault}}"))
        assertEquals(listOf(Literal("echo ${'$'}{{vault:}}")), SnippetTemplate.parse("echo ${'$'}{{vault:}}"))
        assertFalse(SnippetTemplate.hasVariables("echo ${'$'}"))
    }

    @Test
    fun `shell syntax does not trigger parsing`() {
        assertFalse(SnippetTemplate.hasVariables("echo ${'$'}HOME ${'$'}{PATH} ${'$'}(date) ${'$'}${'$'}"))
    }

    // --- machine resolution ---

    private val env = SnippetRunEnvironment(
        moment = SnippetMoment(year = 2026, month = 7, day = 3, hour = 9, minute = 5, second = 42, epochSeconds = 1_782_000_000L),
        newUuid = { "aabbccdd-0000-0000-0000-000000000000" },
        randomChars = { n -> "x".repeat(n) },
    )

    private fun variable(cmd: String) = SnippetTemplate.parse(cmd).single() as Variable

    @Test
    fun `date and time resolve with default formats and zero padding`() {
        assertEquals("2026-07-03", SnippetTemplate.resolveMachine(variable("${'$'}{{date}}"), env))
        assertEquals("09:05:42", SnippetTemplate.resolveMachine(variable("${'$'}{{time}}"), env))
    }

    @Test
    fun `date and time honor custom token formats`() {
        assertEquals("20260703", SnippetTemplate.resolveMachine(variable("${'$'}{{date:YYYYMMDD}}"), env))
        assertEquals("090542", SnippetTemplate.resolveMachine(variable("${'$'}{{time:HHmmss}}"), env))
        assertEquals("26/07", SnippetTemplate.resolveMachine(variable("${'$'}{{date:YY/MM}}"), env))
    }

    @Test
    fun `timestamp uuid and random resolve from the environment`() {
        assertEquals("1782000000", SnippetTemplate.resolveMachine(variable("${'$'}{{timestamp}}"), env))
        assertEquals("aabbccdd-0000-0000-0000-000000000000", SnippetTemplate.resolveMachine(variable("${'$'}{{uuid}}"), env))
        assertEquals("xxxx", SnippetTemplate.resolveMachine(variable("${'$'}{{random:4}}"), env))
        assertEquals("x".repeat(8), SnippetTemplate.resolveMachine(variable("${'$'}{{random}}"), env))
    }

    @Test
    fun `random length is clamped and survives garbage`() {
        assertEquals(64, SnippetTemplate.resolveMachine(variable("${'$'}{{random:999}}"), env)!!.length)
        assertEquals(1, SnippetTemplate.resolveMachine(variable("${'$'}{{random:0}}"), env)!!.length)
        assertEquals(8, SnippetTemplate.resolveMachine(variable("${'$'}{{random:abc}}"), env)!!.length)
    }

    @Test
    fun `context variables are not machine-resolvable`() {
        assertNull(SnippetTemplate.resolveMachine(variable("${'$'}{{clipboard}}"), env))
        assertNull(SnippetTemplate.resolveMachine(variable("${'$'}{{vault:prod}}"), env))
        assertNull(SnippetTemplate.resolveMachine(variable("${'$'}{{container}}"), env))
    }

    // --- full resolution ---

    @Test
    fun `resolve splices machine and context values`() {
        val segments = SnippetTemplate.parse("mysqldump -p${'$'}{{vault:prod}} db > b_${'$'}{{date}}.sql")
        val line = SnippetTemplate.resolve(segments, env) { v ->
            if (v.kind == SnippetVariableKind.VAULT) "s3cret" else "?"
        }
        assertEquals("mysqldump -ps3cret db > b_2026-07-03.sql", line)
    }

    @Test
    fun `resolve sanitizes context values but keeps literal newlines`() {
        val segments = SnippetTemplate.parse("echo ${'$'}{{clipboard}}\nwhoami")
        val line = SnippetTemplate.resolve(segments, env) { "a\nb" }
        assertEquals("echo a b\nwhoami", line)
    }

    @Test
    fun `machine values are drawn once and stay stable across assemble calls`() {
        var draws = 0
        val counting = SnippetRunEnvironment(env.moment, newUuid = { "uuid-${++draws}" }, randomChars = { "r".repeat(it) })
        val segments = SnippetTemplate.parse("a ${'$'}{{uuid}} b ${'$'}{{uuid}} c")

        val machine = SnippetTemplate.machineValues(segments, counting)
        val first = SnippetTemplate.assemble(segments, machine) { "" }
        val second = SnippetTemplate.assemble(segments, machine) { "" }

        assertEquals("a uuid-1 b uuid-2 c", first) // each placeholder draws its own uuid…
        assertEquals(first, second)                // …but re-assembling does not redraw
        assertEquals(2, draws)
    }

    @Test
    fun `resolve keeps a plain command byte-identical`() {
        val segments = SnippetTemplate.parse("df -h | grep /dev")
        assertEquals("df -h | grep /dev", SnippetTemplate.resolve(segments, env) { "" })
    }

    @Test
    fun `assemble strips bidi from literal template text but keeps its newlines`() {
        // A Teams-shared template is untrusted: bidi in the literal part would render the preview
        // one way and execute another (Trojan Source). Intentional multi-line scripts must survive.
        val segments = SnippetTemplate.parse("echo a\u202Eb\nwhoami ${'$'}{{date}}")
        assertEquals("echo ab\nwhoami 2026-07-03", SnippetTemplate.resolve(segments, env) { "" })
    }

    // --- value sanitization ---

    @Test
    fun `sanitize keeps ordinary text intact`() {
        assertEquals("web-1 привет 42", sanitizeSnippetValue("web-1 привет 42"))
    }

    @Test
    fun `sanitize flattens newlines and tabs to spaces`() {
        assertEquals("a b c", sanitizeSnippetValue("a\nb\r\nc"))
        assertEquals("a b", sanitizeSnippetValue("a\tb"))
    }

    @Test
    fun `sanitize strips control and bidi characters`() {
        assertEquals("ab", sanitizeSnippetValue("a" + Char(0x00) + Char(0x07) + "b"))
        assertEquals("ab", sanitizeSnippetValue("a\u202Eb"))
        assertEquals("ab", sanitizeSnippetValue("a\u200B\uFEFFb"))
    }

    @Test
    fun `sanitize strips DEL and C1 controls`() {
        // DEL is interpreted by the remote line discipline (erases already-sent characters), so a
        // value carrying it would execute differently from the previewed line.
        assertEquals("ab", sanitizeSnippetValue("a" + Char(0x7F) + Char(0x9B) + "b"))
    }
}
