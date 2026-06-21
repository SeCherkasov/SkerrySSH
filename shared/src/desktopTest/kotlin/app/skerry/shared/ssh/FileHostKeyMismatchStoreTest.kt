package app.skerry.shared.ssh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileHostKeyMismatchStoreTest {

    private val tempDir: Path = Files.createTempDirectory("skerry-mismatches")
    private val file: Path get() = tempDir.resolve("known_hosts_mismatches")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `starts empty when the file does not exist`() {
        assertEquals(emptyList(), FileHostKeyMismatchStore(file).all())
    }

    @Test
    fun `persists recorded mismatches across instances`() {
        val m = HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")

        FileHostKeyMismatchStore(file).record(m)

        assertEquals(listOf(m), FileHostKeyMismatchStore(file).all())
    }

    @Test
    fun `keeps only the latest mismatch per host key`() {
        val store = FileHostKeyMismatchStore(file)
        store.record(HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW1", "2026-06-22T11:00:00Z"))
        val latest = HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW2", "2026-06-22T12:00:00Z")

        store.record(latest)

        assertEquals(listOf(latest), FileHostKeyMismatchStore(file).all())
    }

    @Test
    fun `clear removes the mismatch and persists`() {
        val keep = HostKeyMismatch("db", 22, "ssh-ed25519", "SHA256:D1", "SHA256:D2", "2026-06-22T11:00:00Z")
        val store = FileHostKeyMismatchStore(file)
        store.record(HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z"))
        store.record(keep)

        store.clear("nas", 22, "ssh-ed25519")

        assertEquals(listOf(keep), FileHostKeyMismatchStore(file).all())
    }

    @Test
    fun `round-trips a record with an empty observedAt`() {
        val m = HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "")

        FileHostKeyMismatchStore(file).record(m)

        assertEquals(listOf(m), FileHostKeyMismatchStore(file).all())
    }

    @Test
    fun `ignores malformed lines`() {
        file.writeText(
            """
            nas 22 ssh-ed25519 SHA256:OLD SHA256:NEW 2026-06-22T11:00:00Z
            garbage
            db notaport ssh-ed25519 SHA256:D1 SHA256:D2 2026-06-22T12:00:00Z
            """.trimIndent(),
        )

        assertEquals(
            listOf(HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")),
            FileHostKeyMismatchStore(file).all(),
        )
    }
}
