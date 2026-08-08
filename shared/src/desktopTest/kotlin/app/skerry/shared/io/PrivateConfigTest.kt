package app.skerry.shared.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `writePrivateFile stores the bytes owner-only`() {
        val file = tempDir.resolve("id_ed25519.pem")

        PrivateConfig.writePrivateFile(file, "-----BEGIN-----".toByteArray())

        assertEquals("-----BEGIN-----", Files.readAllBytes(file).decodeToString())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `writePrivateFile leaves the directory the user chose alone`() {
        // The target here is a Save-As destination — Downloads, a USB stick — not Skerry's own
        // config dir. Narrowing someone's Downloads folder to 0700 as a side effect of exporting a
        // key is the reason this exists next to atomicWrite instead of reusing it.
        val downloads = tempDir.resolve("downloads")
        Files.createDirectories(downloads)
        val before = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(downloads, before)

        PrivateConfig.writePrivateFile(downloads.resolve("key.pem"), "x".toByteArray())

        assertEquals(before, Files.getPosixFilePermissions(downloads))
    }

    @Test
    fun `writePrivateFile narrows an existing world-readable target before writing into it`() {
        val file = tempDir.resolve("stale.pem")
        Files.write(file, "old".toByteArray())
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))

        PrivateConfig.writePrivateFile(file, "secret".toByteArray())

        assertEquals("secret", Files.readAllBytes(file).decodeToString())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `writePrivateFile writes every byte of a large secret`() {
        // A single channel write may be short; a truncated private key that still reported success
        // is the failure this guards.
        val file = tempDir.resolve("big.pem")
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }

        PrivateConfig.writePrivateFile(file, bytes)

        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test
    fun `writePrivateFile replaces a symlink planted at the target name instead of following it`() {
        // The export name is predictable, so in a shared directory the target may be someone else's
        // symlink. Following it would write the key wherever it points; the move replaces the link.
        val real = tempDir.resolve("attacker-owned")
        Files.write(real, "old".toByteArray())
        val link = tempDir.resolve("link.pem")
        Files.createSymbolicLink(link, real)

        PrivateConfig.writePrivateFile(link, "secret".toByteArray())

        assertEquals("old", Files.readAllBytes(real).decodeToString(), "the key followed the symlink")
        assertTrue(!Files.isSymbolicLink(link))
        assertEquals("secret", Files.readAllBytes(link).decodeToString())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(link),
        )
    }

    @Test
    fun `writePrivateFile leaves no temp file behind`() {
        val file = tempDir.resolve("id.pem")

        PrivateConfig.writePrivateFile(file, "x".toByteArray())

        val leftovers = Files.list(tempDir).use { s ->
            s.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "unexpected temp files: $leftovers")
    }

    @Test
    fun `a failed write leaves no half-written secret behind`() {
        // The move into place is what can fail here (the target is a directory, so neither ATOMIC_MOVE
        // nor REPLACE_EXISTING can land). What must not survive is the temp file: it holds the whole
        // private key, and the caller has just been told the export failed.
        val target = tempDir.resolve("occupied")
        Files.createDirectories(target)
        Files.write(target.resolve("child"), "x".toByteArray())

        assertFailsWith<java.io.IOException> { PrivateConfig.writePrivateFile(target, "secret".toByteArray()) }

        val leftovers = Files.list(tempDir).use { s ->
            s.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "the key was left in a temp file: $leftovers")
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
