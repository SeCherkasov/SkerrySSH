package app.skerry.shared.ssh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileKnownHostsStoreTest {

    private val tempDir: Path = Files.createTempDirectory("skerry-known-hosts")
    private val file: Path get() = tempDir.resolve("known_hosts")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `starts empty when the file does not exist`() {
        assertEquals(emptyList(), FileKnownHostsStore(file).all())
    }

    @Test
    fun `persists added hosts across instances`() {
        val a = KnownHost("a.example", 22, "ssh-ed25519", "SHA256:AAA")
        val b = KnownHost("b.example", 2222, "rsa-sha2-512", "SHA256:BBB")

        FileKnownHostsStore(file).apply {
            add(a)
            add(b)
        }

        assertEquals(listOf(a, b), FileKnownHostsStore(file).all())
    }

    @Test
    fun `creates parent directories on first add`() {
        val nested = tempDir.resolve("nested/dir/known_hosts")
        val host = KnownHost("c.example", 22, "ssh-ed25519", "SHA256:CCC")

        FileKnownHostsStore(nested).add(host)

        assertEquals(listOf(host), FileKnownHostsStore(nested).all())
    }

    @Test
    fun `ignores malformed and blank lines`() {
        file.writeText(
            """
            a.example 22 ssh-ed25519 SHA256:AAA

            garbage line
            b.example notaport ssh-ed25519 SHA256:BBB
            c.example 22 ssh-ed25519 SHA256:CCC
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                KnownHost("a.example", 22, "ssh-ed25519", "SHA256:AAA"),
                KnownHost("c.example", 22, "ssh-ed25519", "SHA256:CCC"),
            ),
            FileKnownHostsStore(file).all(),
        )
    }
}
