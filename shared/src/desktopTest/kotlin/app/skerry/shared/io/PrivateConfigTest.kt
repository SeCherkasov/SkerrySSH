package app.skerry.shared.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivateConfigTest {

    private val tempDir: Path = Files.createTempDirectory("skerry-privconf")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `atomicWrite stores the bytes`() {
        val file = tempDir.resolve("data.json")

        PrivateConfig.atomicWrite(file, "hello".toByteArray())

        assertEquals("hello", Files.readAllBytes(file).decodeToString())
    }

    @Test
    fun `atomicWrite gives the file owner-only permissions`() {
        val file = tempDir.resolve("secrets.json")

        PrivateConfig.atomicWrite(file, "x".toByteArray())

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `atomicWrite creates parent directories owner-only`() {
        val nested = tempDir.resolve("a/b/data.json")

        PrivateConfig.atomicWrite(nested, "x".toByteArray())

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(nested.parent),
        )
    }

    @Test
    fun `atomicWrite overwrites an existing file and keeps it private`() {
        val file = tempDir.resolve("data.json")
        PrivateConfig.atomicWrite(file, "first".toByteArray())

        PrivateConfig.atomicWrite(file, "second".toByteArray())

        assertEquals("second", Files.readAllBytes(file).decodeToString())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `atomicWrite leaves no temp file behind`() {
        val file = tempDir.resolve("data.json")

        PrivateConfig.atomicWrite(file, "x".toByteArray())

        val leftovers = Files.list(tempDir).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "unexpected temp files: $leftovers")
    }

    @Test
    fun `harden makes an already written file owner-only`() {
        val file = tempDir.resolve("known_hosts")
        Files.write(file, listOf("a b c"))

        PrivateConfig.harden(file)

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `ensureDir creates the directory owner-only`() {
        val dir = tempDir.resolve("store")

        PrivateConfig.ensureDir(dir)

        assertTrue(Files.isDirectory(dir))
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(dir),
        )
    }

    @Test
    fun `ensureDir keeps contents when the directory already exists`() {
        val dir = tempDir.resolve("store")
        PrivateConfig.ensureDir(dir)
        Files.write(dir.resolve("keep"), "x".toByteArray())

        PrivateConfig.ensureDir(dir)

        assertTrue(Files.exists(dir.resolve("keep")))
    }

    @Test
    fun `ensureDir upgrades a pre-existing world-readable directory to owner-only`() {
        val dir = tempDir.resolve("legacy")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))

        PrivateConfig.ensureDir(dir)

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(dir),
        )
    }
}
