package app.skerry.shared.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MoshBootstrapTest {

    @Test
    fun `MOSH CONNECT line yields port and key`() {
        val out = """
            |
            |MOSH CONNECT 60001 4NeCCgvZFe2RnPgrcU1PQw
            |
            |mosh-server (mosh 1.4.0) [build mosh 1.4.0]
        """.trimMargin()
        val r = MoshBootstrap.parse(exitCode = 0, stdout = out, stderr = "")
        val ok = assertIs<MoshBootstrapResult.Success>(r)
        assertEquals(60001, ok.port)
        assertEquals("4NeCCgvZFe2RnPgrcU1PQw", ok.key.encoded)
    }

    @Test
    fun `exit code 127 means mosh-server is not installed`() {
        val r = MoshBootstrap.parse(
            exitCode = 127,
            stdout = "",
            stderr = "bash: mosh-server: command not found",
        )
        assertIs<MoshBootstrapResult.NotInstalled>(r)
    }

    @Test
    fun `command not found text without exit code also means not installed`() {
        val r = MoshBootstrap.parse(
            exitCode = null,
            stdout = "",
            stderr = "zsh:1: command not found: mosh-server",
        )
        assertIs<MoshBootstrapResult.NotInstalled>(r)
    }

    @Test
    fun `UTF-8 locale complaint is classified as locale problem`() {
        val stderr = """
            |The locale requested by LANG=en_US.UTF-8 isn't available here.
            |Running `locale-gen en_US.UTF-8' may be necessary.
            |
            |mosh-server needs a UTF-8 native locale to run.
        """.trimMargin()
        val r = MoshBootstrap.parse(exitCode = 1, stdout = "", stderr = stderr)
        assertIs<MoshBootstrapResult.LocaleUnsupported>(r)
    }

    @Test
    fun `anything else is a generic failure carrying the server output`() {
        val r = MoshBootstrap.parse(exitCode = 1, stdout = "boom", stderr = "err")
        val f = assertIs<MoshBootstrapResult.Failed>(r)
        assertEquals("boom\nerr", f.output)
    }

    @Test
    fun `malformed key in MOSH CONNECT line is a generic failure`() {
        val r = MoshBootstrap.parse(
            exitCode = 0,
            stdout = "MOSH CONNECT 60001 not-a-key!!",
            stderr = "",
        )
        assertIs<MoshBootstrapResult.Failed>(r)
    }

    @Test
    fun `key decodes to 16 bytes and rejects wrong lengths`() {
        // 22 base64 chars == 128 bits (mosh's Base64Key, printed without padding).
        val key = MoshKey.parse("zM7RhBUAAcTLKwZTHYzGaw")!!
        assertEquals(16, key.bytes.size)
        assertNull(MoshKey.parse("short"))
        assertNull(MoshKey.parse("zM7RhBUAAcTLKwZTHYzGaw=="))
        assertNull(MoshKey.parse("zM7RhBUAAcTLKwZTHYzG@w"))
    }

    @Test
    fun `key round-trips through its base64 form`() {
        val key = MoshKey.parse("AAAAAAAAAAAAAAAAAAAAAA")!!
        assertContentEquals(ByteArray(16), key.bytes)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", key.encoded)
    }
}
