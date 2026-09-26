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

    /**
     * One line that is not UTF-8 (a hand edit, a host name written by another tool) is a malformed
     * line like any other. Decoding the file in one piece threw, the whole file read as empty, and
     * the next recorded mismatch rewrote it with that single warning.
     */
    @Test
    fun `a line that is not UTF-8 costs only itself`() {
        val keep = HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")
        Files.write(
            file,
            "nas 22 ssh-ed25519 SHA256:OLD SHA256:NEW 2026-06-22T11:00:00Z\n".toByteArray() +
                byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '\n'.code.toByte()),
        )

        assertEquals(listOf(keep), FileHostKeyMismatchStore(file).all(), "the valid warning was lost with the bad line")
    }

    /**
     * A read that fails when the store is created (the file briefly locked or replaced under it) is
     * not an empty file: the next mismatch must not be persisted as the only one. The store reads
     * the file again before it writes.
     */
    @Test
    fun `a file that could not be read at start is not overwritten by the next mismatch`() {
        val first = HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")
        val second = HostKeyMismatch("db", 22, "ssh-ed25519", "SHA256:D1", "SHA256:D2", "2026-06-22T12:00:00Z")
        FileHostKeyMismatchStore(file).record(first)
        val saved = Files.readAllBytes(file)
        Files.delete(file)
        Files.createDirectory(file) // a directory at the path: exists, and every read of it fails
        val store = FileHostKeyMismatchStore(file)
        Files.delete(file)
        Files.write(file, saved)

        store.record(second)

        assertEquals(
            setOf(first, second),
            FileHostKeyMismatchStore(file).all().toSet(),
            "the warnings the store never read were erased by the next one",
        )
    }

    /** While the file still cannot be read, a new mismatch is shown but not written over it. */
    @Test
    fun `a mismatch recorded while the file cannot be read is kept in memory and merged later`() {
        val first = HostKeyMismatch("nas", 22, "ssh-ed25519", "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")
        val second = HostKeyMismatch("db", 22, "ssh-ed25519", "SHA256:D1", "SHA256:D2", "2026-06-22T12:00:00Z")
        FileHostKeyMismatchStore(file).record(first)
        val saved = Files.readAllBytes(file)
        Files.delete(file)
        Files.createDirectory(file)
        val store = FileHostKeyMismatchStore(file)

        store.record(second)
        assertEquals(listOf(second), store.all())

        Files.delete(file)
        Files.write(file, saved)
        assertEquals(setOf(first, second), store.all().toSet(), "the unread warnings never came back")
        store.clear("nas", 22, "ssh-ed25519")
        assertEquals(listOf(second), FileHostKeyMismatchStore(file).all())
    }
}
