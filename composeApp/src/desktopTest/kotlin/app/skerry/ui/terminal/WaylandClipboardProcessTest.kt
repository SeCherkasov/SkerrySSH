package app.skerry.ui.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app makes of `wl-paste`/`wl-copy` finishing, or not. The three outcomes are the contract
 * every clipboard surface above rests on: an empty clipboard is `null`, a utility that could not
 * answer throws — reporting that one as "nothing to paste" is how "Send mine" told the user a copy
 * worked when nothing had been read (#282) — and a copy is a copy only once the utility exits clean.
 *
 * Driven through a stub process rather than the real binaries: the outcomes are a Wayland session's
 * to produce, and the utilities are not on a CI machine at all.
 */
class WaylandClipboardProcessTest {

    @Test
    fun `text on stdout is what the clipboard holds`() {
        assertEquals("copied text", readPastedText { StubProcess(out = "copied text") })
    }

    /** `wl-paste` exits 1 for an empty clipboard and for one holding no text — neither is a failure. */
    @Test
    fun `exit 1 reads as an empty clipboard`() {
        assertNull(readPastedText { StubProcess(out = "", exit = 1) })
    }

    @Test
    fun `a clean exit with no output reads as an empty clipboard`() {
        assertNull(readPastedText { StubProcess(out = "") })
    }

    @Test
    fun `a utility that never answers is a failure, not an empty clipboard`() {
        val stub = StubProcess(out = "half a payload", finishes = false)
        assertFailsWith<IllegalStateException>("a hung utility passed for an empty clipboard") {
            readPastedText { stub }
        }
        assertTrue(stub.killed, "the utility was left running after the wait ran out")
    }

    /**
     * A clipboard larger than the read cap is a paste that cannot be served, and the cap is there so
     * the app does not try. Silently handing back the first 8 MiB would paste half a file into a
     * shell; reading it as an empty clipboard would tell the user there is nothing to paste (#282).
     */
    @Test
    fun `a clipboard past the read cap is a failure, not a truncated paste`() {
        val huge = "x".repeat(8 * 1024 * 1024 + 1)
        assertFailsWith<IllegalStateException>("an over-cap clipboard passed for a paste") {
            readPastedText { StubProcess(out = huge) }
        }
    }

    @Test
    fun `a copy counts once the utility took the text and exited clean`() {
        val stub = StubProcess()
        assertTrue(writeCopiedText("copy me", { stub }))
        assertEquals("copy me", stub.written())
    }

    @Test
    fun `a copy the utility refused does not count`() {
        assertTrue(!writeCopiedText("copy me") { StubProcess(exit = 1) })
    }

    @Test
    fun `a copy the utility never finished does not count`() {
        assertTrue(!writeCopiedText("copy me") { StubProcess(finishes = false) })
    }
}

/** A [Process] that hands back [out], exits with [exit], and can refuse to finish at all. */
private class StubProcess(
    out: String = "",
    private val exit: Int = 0,
    private val finishes: Boolean = true,
) : Process() {
    private val stdout = ByteArrayInputStream(out.toByteArray())
    private val stdin = ByteArrayOutputStream()
    var killed = false
        private set

    fun written(): String = stdin.toString(Charsets.UTF_8)

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finishes

    override fun exitValue(): Int = if (finishes) exit else throw IllegalThreadStateException()

    override fun destroy() {
        killed = true
    }

    override fun destroyForcibly(): Process = apply { killed = true }
}
