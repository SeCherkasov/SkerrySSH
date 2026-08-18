package app.skerry.shared.ai.local

import app.skerry.shared.process.isLinux
import app.skerry.shared.process.resolveExecutableOnPath
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.bufferedReader
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.opentest4j.TestAbortedException

/**
 * llamatik's x86-64 natives must carry no more AVX-512 than the pinned version already does.
 *
 * `ggml_cpu_init` runs before any CPU dispatch, so one AVX-512 instruction there is an instant
 * `SIGILL` on every processor without it (all AMD Zen 1-3, Intel parts with E-cores), and the
 * library is loaded the moment a generation starts. Upstream has shipped that build twice — 1.9.1
 * and 1.10.0, which put 25 of them in that one function. See the pin in `gradle/libs.versions.toml`.
 *
 * Whole binary rather than that one function: a rebuild can move inlined code into a callee or an
 * ELF constructor without changing anything about the defect, and the pinned Linux native has a flat
 * zero, so there is no reason to look through a keyhole. The Windows DLL exports no symbol table at
 * all — a function-scoped check could not reach it, and it is the half that would otherwise ship
 * unchecked when upstream fixes only Linux.
 *
 * Reading the shipped binary answers the same on every machine rather than only on hardware that
 * happens to lack AVX-512 — which is how 1.9.1 got past review once. What it cannot answer is
 * whether an instruction is reachable: 1.9.0's DLL has two, an auto-vectorised `vpmullq` pair in a
 * popcount with no dispatch inside the function, and nothing here says whether a caller guards them.
 * So a bump still owes a live generation on a CPU without AVX-512, on Linux and on Windows.
 */
class LlamatikNativeIsaTest {

    @Test
    fun `the natives carry no AVX-512 beyond the pinned baseline`() {
        for ((resource, baseline) in BASELINE) {
            val native = javaClass.classLoader.getResource(resource)
                ?: fail("llamatik's native is not on the test classpath as $resource")
            val copy = Files.createTempFile("llama-jni-", ".bin")
            try {
                native.openStream().use { input -> Files.newOutputStream(copy).use(input::copyTo) }
                val counts = disassemble(
                    // Wide enough for the longest x86 instruction, so nothing wraps: a wrapped tail
                    // keeps the `address:` shape without a mnemonic, and a 0x62 byte in a string
                    // constant would then read as an EVEX prefix (`62 65 64` is "bed").
                    listOf(tool("objdump"), "-d", "--insn-width=$MAX_INSTRUCTION_BYTES", "$copy"),
                )

                // Positive control first: matching the baseline proves nothing if nothing was
                // decoded, and every way this check can break — a different objdump, a changed
                // column layout, a truncated copy — produces an empty result that reads as clean.
                if (counts.decoded < MIN_INSTRUCTIONS) {
                    unusable("only ${counts.decoded} instructions decoded in $resource")
                }

                assertEquals(baseline, counts.avx512, "AVX-512 in $resource — it will SIGILL without AVX-512")
                // Undecodable bytes are excluded from the count above, and an EVEX-shaped one is
                // ambiguous: data in a code section, or an encoding this binutils is older than.
                // Only growth is a signal, and only growth is checked — a newer disassembler that
                // decodes more of them is an improvement, and where the sweep resynchronises after
                // invalid bytes differs between binutils versions. If this trips on an unchanged
                // llamatik, compare `objdump --version` before touching the number.
                assertTrue(
                    counts.undecodableEvex <= UNDECODABLE_EVEX.getValue(resource),
                    "${counts.undecodableEvex} EVEX-shaped bytes this objdump cannot decode in " +
                        "$resource, was ${UNDECODABLE_EVEX.getValue(resource)}",
                )
            } finally {
                copy.deleteIfExists()
            }
        }
    }

    @Test
    fun `the jar ships the natives this checks and no others`() {
        // Iterating the baselines would silently pass over a native upstream added — a second Linux
        // variant, an x86-64 macOS slice — and that one would reach users unscanned.
        assertEquals(BASELINE.keys + MACOS_NATIVE, shippedNatives(), "the set of shipped natives moved")
    }

    /** Every binary under `native/` in the jar the natives come from. */
    private fun shippedNatives(): Set<String> {
        val url = javaClass.classLoader.getResource(BASELINE.keys.first())
            ?: fail("llamatik's native is not on the test classpath as ${BASELINE.keys.first()}")
        // Through the connection, not by slicing the URL: that string is percent-encoded, and a
        // Gradle cache under a path with a space or a non-ASCII character then names no file.
        val connection = url.openConnection() as? JarURLConnection
            ?: unusable("the natives are not in a jar, so there is nothing to enumerate")
        connection.useCaches = false
        return connection.jarFile.use { jar ->
            jar.entries().asSequence()
                .map { it.name }
                .filterNot { it.endsWith("/") || it.endsWith(".txt") }
                .filter { it.startsWith("native/") }
                .toSet()
        }
    }

    @Test
    fun `the search knows an AVX-512 encoding from an ordinary one`() {
        // Real objdump lines, tabs and all. The binaries under test are whatever version is pinned,
        // and the pinned one sits at its baseline by definition — so without this, deleting half the
        // disjunction or mistyping a prefix byte still leaves the guard green.
        val avx512 = listOf(
            "  758c06:\t62 e2 7d 28 7c ca   \tvpbroadcastd %edx,%ymm17",
            "  758c86:\t62 a3 6d 20 25 d2 ff\tvpternlogd \$0xff,%ymm18,%ymm18,%ymm18",
            "  758cd8:\t62 f2 5d 29 47 d8   \tvpsllvd %ymm0,%ymm4,%ymm3{%k1}",
            // VEX, not EVEX: the mask registers are what make it AVX-512, and no operand says so.
            "  758cfc:\tc4 e3 79 31 d1 10   \tkshiftrd \$0x10,%k1,%k2",
            "  64758cfc:\t64 62 e2 7d 28 7c ca\tvpbroadcastd %edx,%ymm17",
        )
        val ordinary = listOf(
            "  7589c0:\tf3 0f 1e fa         \tendbr64",
            "  7589c4:\t41 55               \tpush   %r13",
            // AVX-2 on the same encoding as the kshift above, without a mask register.
            "  7589f8:\tc5 f9 6f 7d b0      \tvmovdqa -0x50(%rbp),%xmm7",
            // Only the byte column decides: those operand bytes read "el.em" and the immediate
            // spells "el.embed" in memory order, but none of that is where a prefix can be.
            "  6821b0:\t48 ba 65 6c 2e 65 6d 62 65 64\tmovabs \$0x6465626d652e6c65,%rdx",
            // Data in an executable section. Ten of these sit in the pinned Windows DLL; counting
            // them would put its baseline at 12 and bury the two real instructions among them.
            "  180086080:\t62                  \t(bad)",
        )

        for (line in avx512) assertTrue(isAvx512(line), "not recognised: $line")
        for (line in ordinary) assertFalse(isAvx512(line), "recognised as AVX-512: $line")
        for (line in avx512 + ordinary) assertTrue(INSTRUCTION.containsMatchIn(line), "not an instruction: $line")
    }

    /** Whether an objdump line is an AVX-512 instruction: EVEX, or VEX touching a mask register. */
    private fun isAvx512(line: String): Boolean {
        if (line.endsWith(UNDECODABLE)) return false
        return EVEX_INSTRUCTION.containsMatchIn(line) ||
            (VEX_INSTRUCTION.containsMatchIn(line) && MASK_REGISTER.containsMatchIn(line))
    }

    private fun tool(name: String): String = resolveExecutableOnPath(name) ?: unusable("no $name on PATH")

    /** What one pass over a disassembly found. */
    private data class Counts(val decoded: Int, val avx512: Int, val undecodableEvex: Int)

    /**
     * Runs [command] and counts, in one streaming pass, the instructions it decoded, the AVX-512
     * among them, and the EVEX-shaped bytes it could not decode. Streamed rather than held: a whole
     * native disassembles to over a million lines, which is a heap the test JVM does not have.
     */
    private fun disassemble(command: List<String>): Counts {
        val output = Files.createTempFile("skerry-binutils-", ".txt")
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                unusable("${command.first()} did not finish in ${TOOL_TIMEOUT_SECONDS}s")
            }
            if (process.exitValue() != 0) {
                unusable("${command.first()} failed: ${output.bufferedReader().use { it.readLine() }}")
            }
            var decoded = 0
            var avx512 = 0
            var undecodableEvex = 0
            output.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!INSTRUCTION.containsMatchIn(line)) continue
                    when {
                        // Not counted as decoded: the positive control asks whether objdump read
                        // real code, and a sweep over data produces these by the thousand.
                        line.endsWith(UNDECODABLE) -> if (EVEX_INSTRUCTION.containsMatchIn(line)) undecodableEvex++
                        else -> {
                            decoded++
                            if (isAvx512(line)) avx512++
                        }
                    }
                }
            }
            return Counts(decoded, avx512, undecodableEvex)
        } finally {
            output.deleteIfExists()
        }
    }

    /**
     * Skipped where binutils cannot read an x86-64 binary — an arm64 workstation, a JDK image
     * without them — and **failed** where `-PskerryCi=1` says the tools were installed on purpose
     * and a green tick from a run that disassembled nothing is how the next AVX-512 build would
     * ship. A second workflow that runs the tests owes the same flag, or the guard skips there.
     */
    private fun unusable(reason: String): Nothing {
        // Blank, not absent: the Gradle test task always sets the property, empty when unasked.
        if (!System.getProperty("skerry.ci").isNullOrBlank() && isLinux) {
            fail("the AVX-512 guard cannot run where it is needed: $reason")
        }
        // What Assumptions.abort throws; thrown directly because a Java generic method is not
        // `Nothing`-returning to Kotlin, and this function has to be.
        throw TestAbortedException(reason)
    }

    private companion object {
        /**
         * What each shipped x86-64 native holds today. Zero is the only defensible number for a
         * binary that has none; the Windows two are the `vpmullq` pair described above, held as a
         * baseline rather than waved through, so a bump that changes either number stops for a
         * person to look. The macOS dylib is arm64-only and cannot carry AVX-512 at all.
         */
        val BASELINE: Map<String, Int> = mapOf(
            "native/linux/libllama_jni.so" to 0,
            "native/windows/llama_jni.dll" to 2,
        )

        /**
         * EVEX-shaped bytes objdump reports as `(bad)` in each pinned native: ten data bytes in the
         * Windows one, none in the Linux one. Measured with binutils 2.46 and cross-checked with
         * 2.42, the version CI runs — identical, so where the sweep resynchronises after invalid
         * bytes is not version-sensitive for these two binaries. Counted separately from real instructions so that a
         * candidate built with an encoding this binutils predates cannot read as clean.
         */
        val UNDECODABLE_EVEX: Map<String, Int> = mapOf(
            "native/linux/libllama_jni.so" to 0,
            "native/windows/llama_jni.dll" to 10,
        )

        /** Arm64 only in every release so far, so AVX-512 cannot apply — listed, not scanned. */
        const val MACOS_NATIVE = "native/macos/libllama_jni.dylib"

        const val TOOL_TIMEOUT_SECONDS = 120L
        const val MAX_INSTRUCTION_BYTES = 15

        /** Both natives decode over a million instructions; this only has to rule out "almost none". */
        const val MIN_INSTRUCTIONS = 100_000

        const val LEGACY_PREFIXES = "((26|2e|36|3e|64|65|66|67|f0|f2|f3) )*"

        /** Bytes objdump could not decode. They keep the shape of an instruction and are not one. */
        const val UNDECODABLE = "(bad)"

        /** An objdump line, by its `address:` column and the first byte of the instruction. */
        val INSTRUCTION = Regex("^\\s+[0-9a-f]+:\\s+[0-9a-f]{2}")

        /** The same, where the instruction carries the EVEX prefix — behind legacy prefixes if any. */
        val EVEX_INSTRUCTION = Regex("^\\s+[0-9a-f]+:\\s+$LEGACY_PREFIXES(62) ")

        /** VEX, two- or three-byte form. Ordinary AVX uses it too — only a mask operand makes it 512. */
        val VEX_INSTRUCTION = Regex("^\\s+[0-9a-f]+:\\s+$LEGACY_PREFIXES(c4|c5) ")

        /** An AVX-512 mask register: `kshiftrd %k1,%k2` is VEX-encoded and mentions no vector width. */
        val MASK_REGISTER = Regex("%k[0-7]")
    }
}
