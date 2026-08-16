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
    fun `a name carrying a character that draws as nothing stays literal`() {
        // U+3164 is a letter, so the name rule accepted it: in a shared template the name with the
        // filler and the name without it prompt for two values under one caption, and the one the
        // user never fills is spliced from its inline default. Escaped, not the raw glyph — it
        // draws as nothing, and an invisible character in source is unreviewable.
        val filler = "echo ${'$'}{{token\u3164}}"
        assertEquals(listOf(Literal(filler)), SnippetTemplate.parse(filler))
        val blank = "echo ${'$'}{{\u3164}}"
        assertEquals(listOf(Literal(blank)), SnippetTemplate.parse(blank))
    }

    @Test
    fun `shell syntax does not trigger parsing`() {
        assertFalse(SnippetTemplate.hasVariables("echo ${'$'}HOME ${'$'}{PATH} ${'$'}(date) ${'$'}${'$'}"))
    }

    // --- machine resolution ---

    private val env = SnippetRunEnvironment(
        moment = SnippetMoment(year = 2026, month = 7, day = 3, hour = 9, minute = 5, second = 42, epochSeconds = 1_782_000_000L),
        newUuid = { "aabbccdd-0000-0000-0000-000000000000" },
        randomChars = { n, _ -> "x".repeat(n) },
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
        val counting = SnippetRunEnvironment(env.moment, newUuid = { "uuid-${++draws}" }, randomChars = { n, _ -> "r".repeat(n) })
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

    @Test
    fun `a date-time format cannot smuggle a second command into the line`() {
        // The format part of ${{date:…}} is arbitrary text carried through by formatMoment, and a
        // template can arrive over sync or Teams sharing. A newline there would end the previewed
        // command and start one the user never confirmed.
        val segments = SnippetTemplate.parse("echo ${'$'}{{date:X\nrm -rf ~}} done")
        assertEquals("echo X rm -rf ~ done", SnippetTemplate.resolve(segments, env) { "" })
    }

    @Test
    fun `a date-time format cannot smuggle bidi text into the line`() {
        val segments = SnippetTemplate.parse("echo ${'$'}{{time:a\u202Eb}}")
        assertEquals("echo ab", SnippetTemplate.resolve(segments, env) { "" })
    }

    // --- random charsets ---

    private class RecordingEnv {
        val alphabets = mutableListOf<String>()
        val env = SnippetRunEnvironment(
            moment = SnippetMoment(year = 2026, month = 7, day = 3, hour = 9, minute = 5, second = 42, epochSeconds = 1_782_000_000L),
            newUuid = { "u" },
            randomChars = { n, alphabet ->
                alphabets += alphabet
                "x".repeat(n)
            },
        )
    }

    @Test
    fun `random charset picks the alphabet`() {
        val recording = RecordingEnv()
        SnippetTemplate.resolveMachine(variable("${'$'}{{random:12,hex}}"), recording.env)
        SnippetTemplate.resolveMachine(variable("${'$'}{{random:12,alnum}}"), recording.env)
        SnippetTemplate.resolveMachine(variable("${'$'}{{random:12,special}}"), recording.env)
        SnippetTemplate.resolveMachine(variable("${'$'}{{random:12}}"), recording.env)
        assertEquals(
            listOf(
                SnippetRandomAlphabets.HEX,
                SnippetRandomAlphabets.ALNUM,
                SnippetRandomAlphabets.SPECIAL,
                SnippetRandomAlphabets.DEFAULT,
            ),
            recording.alphabets,
        )
    }

    @Test
    fun `random charset keeps the requested length and clamp`() {
        assertEquals(12, SnippetTemplate.resolveMachine(variable("${'$'}{{random:12,hex}}"), env)!!.length)
        assertEquals(64, SnippetTemplate.resolveMachine(variable("${'$'}{{random:999,hex}}"), env)!!.length)
    }

    @Test
    fun `random charset survives garbage and token order`() {
        val recording = RecordingEnv()
        // Unknown charset falls back to the default alphabet, the length is still honored.
        assertEquals(16, SnippetTemplate.resolveMachine(variable("${'$'}{{random:16,bogus}}"), recording.env)!!.length)
        // A charset alone keeps the default length.
        assertEquals(8, SnippetTemplate.resolveMachine(variable("${'$'}{{random:hex}}"), recording.env)!!.length)
        // Token order does not matter.
        assertEquals(16, SnippetTemplate.resolveMachine(variable("${'$'}{{random:hex,16}}"), recording.env)!!.length)
        assertEquals(
            listOf(SnippetRandomAlphabets.DEFAULT, SnippetRandomAlphabets.HEX, SnippetRandomAlphabets.HEX),
            recording.alphabets,
        )
    }

    @Test
    fun `random alphabets are shell-safe`() {
        // The generated value is spliced unquoted into the confirmed line, so no character may be
        // a shell metacharacter, a quote, whitespace, or a glob/expansion trigger — nor `#` (word
        // -start comment truncates the confirmed line), `^` (history substitution at line start)
        // or `=` (a first-word splice would parse as a variable assignment).
        val unsafe = " \t\"'`\\${'$'}|&;()<>*?[]{}!~#^="
        for (alphabet in listOf(
            SnippetRandomAlphabets.DEFAULT,
            SnippetRandomAlphabets.ALNUM,
            SnippetRandomAlphabets.HEX,
            SnippetRandomAlphabets.SPECIAL,
        )) {
            assertTrue(alphabet.isNotEmpty())
            assertTrue(alphabet.none { it in unsafe }, "unsafe char in $alphabet")
            assertEquals(alphabet.length, alphabet.toSet().size, "duplicate chars in $alphabet")
        }
        // The default alphabet is the historic one — `${'$'}{{random}}` output must not change shape.
        assertEquals("abcdefghijklmnopqrstuvwxyz0123456789", SnippetRandomAlphabets.DEFAULT)
        assertTrue(SnippetRandomAlphabets.HEX.all { it in "0123456789abcdef" })
        assertTrue(SnippetRandomAlphabets.ALNUM.any { it.isUpperCase() })
        assertTrue(SnippetRandomAlphabets.SPECIAL.any { !it.isLetterOrDigit() })
    }

    // --- param choices ---

    @Test
    fun `param choices split on pipe with the first as default`() {
        val v = variable("${'$'}{{env:dev|staging|prod}}")
        assertEquals(SnippetVariableKind.PARAM, v.kind)
        assertEquals("dev", v.paramDefault())
        assertEquals(listOf("dev", "staging", "prod"), v.paramChoices())
    }

    @Test
    fun `param without pipe has a default and no choices`() {
        val v = variable("${'$'}{{container:web-1}}")
        assertEquals("web-1", v.paramDefault())
        assertEquals(emptyList(), v.paramChoices())

        val bare = variable("${'$'}{{container}}")
        assertNull(bare.paramDefault())
        assertEquals(emptyList(), bare.paramChoices())
    }

    @Test
    fun `param choices drop blanks and duplicates and may lack a default`() {
        val v = variable("${'$'}{{env:|dev|dev| prod }}")
        assertNull(v.paramDefault())
        assertEquals(listOf("dev", "prod"), v.paramChoices())
    }

    @Test
    fun `choice options are sanitized so what is picked is what runs`() {
        // The option list can arrive in a team-shared template; picker, selected highlight and the
        // sent line must agree on one string, so options are sanitized at the parse, not on pick.
        val v = variable("${'$'}{{env:a\tb|de\u202Ev}}")
        assertEquals(listOf("a b", "dev"), v.paramChoices())
        assertEquals("a b", v.paramDefault())
    }

    @Test
    fun `an option of invisible characters is dropped, not offered as a blank row`() {
        // Zero-width/bidi characters are not whitespace, so trim() keeps them; without the
        // sanitize such an option renders as an empty row and splices as an empty string.
        val v = variable("${'$'}{{env:\u200B\u202E|dev|prod}}")
        assertNull(v.paramDefault())
        assertEquals(listOf("dev", "prod"), v.paramChoices())
    }

    @Test
    fun `sanitize strips invisible letters`() {
        // Hangul fillers and the Braille blank count as letters, not format characters — kept by
        // the format-category filter, invisible on screen. Two values differing only by one of
        // these would draw identically and execute differently.
        assertEquals("web-1", sanitizeSnippetValue("web-1\u3164"))
        assertEquals("ab", sanitizeSnippetValue("a\u115F\u1160\uFFA0\u2800b"))
        assertEquals("echo a", stripUnsafeFormatChars("echo a\u3164"))
    }

    @Test
    fun `choices never apply to non-param variables`() {
        // A vault entry named "a|b" is a name, not an option list.
        assertEquals(emptyList(), variable("${'$'}{{vault:a|b}}").paramChoices())
        assertNull(variable("${'$'}{{vault:a|b}}").paramDefault())
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
