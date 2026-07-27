package app.skerry.shared.vault

import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OkioSecretFileReaderTest {

    private val fs = FakeFileSystem()

    private fun reader(maxBytes: Long = 256L * 1024) =
        OkioSecretFileReader(fs, homeDir = "/home/dev", maxBytes = maxBytes)

    private fun write(path: String, content: String) {
        val p = path.toPath()
        p.parent?.let { fs.createDirectories(it) }
        fs.write(p) { writeUtf8(content) }
    }

    @Test
    fun `reads file content verbatim`() {
        write("/keys/id_ed25519", "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n")

        val result = reader().read("/keys/id_ed25519")

        assertEquals(
            SecretFileResult.Ok("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"),
            result,
        )
    }

    @Test
    fun `expands a leading tilde to the home directory`() {
        write("/home/dev/.ssh/id_ed25519-cert.pub", "ssh-ed25519-cert-v01@openssh.com AAAA")

        assertEquals(
            SecretFileResult.Ok("ssh-ed25519-cert-v01@openssh.com AAAA"),
            reader().read("~/.ssh/id_ed25519-cert.pub"),
        )
    }

    @Test
    fun `missing file reports not found`() {
        assertIs<SecretFileResult.NotFound>(reader().read("/keys/absent"))
    }

    @Test
    fun `directory in place of a file reports not found`() {
        fs.createDirectories("/keys/dir".toPath())

        assertIs<SecretFileResult.NotFound>(reader().read("/keys/dir"))
    }

    @Test
    fun `blank ref reports not found without touching the filesystem`() {
        assertIs<SecretFileResult.NotFound>(reader().read("   "))
    }

    @Test
    fun `file above the size limit is rejected without being read`() {
        write("/keys/huge", "x".repeat(2048))

        assertIs<SecretFileResult.TooLarge>(reader(maxBytes = 1024).read("/keys/huge"))
    }

    @Test
    fun `a readable-looking file whose read fails is denied, regardless of the message locale`() {
        // okio has no permission-denied type, and the OS message is localized ("Отказано в доступе"),
        // so classification can't be based on its text: metadata says the file is there, the read
        // says otherwise — that is the signal.
        write("/keys/locked", "secret")
        val refusing = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source = throw IOException("Отказано в доступе")
        }

        assertIs<SecretFileResult.Denied>(
            OkioSecretFileReader(refusing, homeDir = null).read("/keys/locked"),
        )
    }

    @Test
    fun `a ref carrying a URI scheme is unsupported rather than treated as a path`() {
        // Comes from a credential synced off Android: a content:// document Uri means nothing to
        // the filesystem, and reporting "not found" would send the user hunting for a file.
        assertIs<SecretFileResult.Unsupported>(reader().read("content://com.android.providers/document/42"))
    }

    @Test
    fun `tilde stays literal when no home directory is known`() {
        write("/work/~/key", "k")

        val reader = OkioSecretFileReader(fs, homeDir = null)

        assertIs<SecretFileResult.NotFound>(reader.read("~/key"))
    }
}
