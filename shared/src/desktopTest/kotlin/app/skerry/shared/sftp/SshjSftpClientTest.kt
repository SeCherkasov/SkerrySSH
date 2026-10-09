package app.skerry.shared.sftp

import app.skerry.shared.files.MAX_LISTING_ENTRIES
import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import kotlinx.coroutines.test.runTest
import com.hierynomus.sshj.sftp.RemoteResourceSelector
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.PathComponents
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.sshd.server.SshServer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"
private const val README_BODY = "hello sftp\n"

private val acceptAllKeys = HostKeyVerifier { null }

/**
 * Integration tests for [SshjSftpClient] against an embedded Apache MINA SSHD with an SFTP subsystem.
 * The server root is a temp directory (virtual FS) seeded with `readme.txt` and a `sub` directory.
 */
class SshjSftpClientTest {

    private lateinit var server: SshServer
    private lateinit var root: Path

    @BeforeTest
    fun startServer() {
        root = Files.createTempDirectory("skerry-sftp-root")
        root.resolve("readme.txt").writeText(README_BODY)
        root.resolve("sub").createDirectory().resolve("nested.txt").writeText("nested\n")
        // Symlink to a directory: verifies lstat (not stat) doesn't follow the target —
        // type must be Symlink, not Directory, consistent with the listing.
        Files.createSymbolicLink(root.resolve("sub-link"), root.resolve("sub"))

        server = startSftpTestServer(root, USER, PASSWORD)
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
        root.toFile().deleteRecursively()
    }

    private fun target() = SshTarget(host = "127.0.0.1", port = server.port, username = USER)

    private suspend fun connect(): SshConnection =
        SshjTransport(acceptAllKeys).connect(target(), SshAuth.Password(PASSWORD))

    /** Opens SFTP, runs the block, and always closes it and disconnects. */
    private suspend fun <T> withSftp(block: suspend (SftpClient) -> T): T {
        val connection = connect()
        try {
            // openSftp() inside try: otherwise a failure to open would leave connection unclosed.
            val sftp = connection.openSftp()
            try {
                return block(sftp)
            } finally {
                sftp.close()
            }
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `list returns directory entries without dot and dotdot`() = runTest {
        withSftp { sftp ->
            val names = sftp.list("/", MAX_LISTING_ENTRIES).map { it.name }
            assertTrue("readme.txt" in names, "expected readme.txt, got $names")
            assertTrue("sub" in names, "expected directory sub, got $names")
            assertTrue("." !in names && ".." !in names, "listing should not contain . or .., got $names")
        }
    }

    @Test
    fun `the listing selector takes one entry past the limit and then breaks`() {
        // The selector is the object `list` installs, so testing it is testing the wiring: the count
        // and the name check both have to live on the instance `sftp.ls` is handed.
        val selector = listingSelector("/d", limit = 2)

        repeat(3) { assertEquals(RemoteResourceSelector.Result.ACCEPT, selector.select(entry("/d", "f$it"))) }
        assertEquals(RemoteResourceSelector.Result.BREAK, selector.select(entry("/d", "f3")))
    }

    @Test
    fun `the listing selector refuses an entry no filesystem could name`() {
        // A cap on how many entries a listing holds is not a cap on what it weighs: an SSH string
        // may be 32 KB. The limits are POSIX's own, so this cannot be provoked through a real server
        // — the filesystem behind it refuses the name first — and no real server ever trips them.
        val selector = listingSelector("/d", limit = 2)
        selector.select(entry(parent = "/d", name = "file.txt"))

        assertFailsWith<SftpException> { selector.select(entry("/d", "x".repeat(256))) }
        assertFailsWith<SftpException> { selector.select(entry("/" + "d".repeat(4096), "f")) }
    }

    private fun entry(parent: String, name: String) =
        RemoteResourceInfo(PathComponents(parent, name, "/"), FileAttributes.EMPTY)

    @Test
    fun `list stops one entry past the limit it was given`() = runTest {
        // The allocation the limit exists for happens inside sshj's READDIR loop, so the stop has
        // to be proven against a real server rather than a fake: what comes back must be limit + 1,
        // no matter how many names the directory actually holds.
        val wide = root.resolve("wide").createDirectory()
        repeat(40) { wide.resolve("f$it").writeText("x") }

        withSftp { sftp ->
            assertEquals(11, sftp.list("/wide", limit = 10).size)
            assertEquals(40, sftp.list("/wide", limit = 100).size)
        }
    }

    @Test
    fun `list distinguishes files from directories`() = runTest {
        withSftp { sftp ->
            val byName = sftp.list("/", MAX_LISTING_ENTRIES).associateBy { it.name }
            assertEquals(SftpEntryType.File, byName.getValue("readme.txt").type)
            assertEquals(SftpEntryType.Directory, byName.getValue("sub").type)
            assertEquals(README_BODY.length.toLong(), byName.getValue("readme.txt").size)
        }
    }

    @Test
    fun `stat returns metadata for a file and null for a missing path`() = runTest {
        withSftp { sftp ->
            val entry = sftp.stat("/readme.txt")
            assertEquals(SftpEntryType.File, entry?.type)
            assertEquals(README_BODY.length.toLong(), entry?.size)
            assertNull(sftp.stat("/nope.txt"))
        }
    }

    @Test
    fun `realpath canonicalizes a relative path to an absolute one`() = runTest {
        withSftp { sftp ->
            assertTrue(sftp.realpath("readme.txt").endsWith("/readme.txt"))
            assertTrue(sftp.realpath(".").startsWith("/"))
        }
    }

    @Test
    fun `read returns the full file contents`() = runTest {
        withSftp { sftp ->
            assertContentEquals(README_BODY.encodeToByteArray(), sftp.read("/readme.txt"))
        }
    }

    @Test
    fun `read refuses a file over the caller's cap`() = runTest {
        withSftp { sftp ->
            val e = assertFailsWith<SftpException> { sftp.read("/readme.txt", maxBytes = 4) }
            assertTrue("too large" in (e.message ?: ""), "unexpected message: ${e.message}")
        }
    }

    @Test
    fun `the streaming read stops at the cap when the source outruns its reported size`() {
        // A server can report any size (or 0, as special files do) and then keep sending; the guard
        // has to hold on the bytes themselves, not on the metadata that preceded them.
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }

        val e = assertFailsWith<SftpException> { readAtMost(endless, cap = 64 * 1024, label = "/dev/zero") }

        assertTrue("read limit" in (e.message ?: ""), "unexpected message: ${e.message}")
    }

    @Test
    fun `the streaming read returns content that exactly fills the cap`() {
        val body = ByteArray(1024) { 'x'.code.toByte() }

        val read = readAtMost(java.io.ByteArrayInputStream(body), cap = 1024, label = "/exact")

        assertContentEquals(body, read)
    }

    @Test
    fun `write creates a file that reads back identically`() = runTest {
        val payload = "uploaded by skerry\n".encodeToByteArray()
        withSftp { sftp ->
            sftp.write("/upload.bin", payload)
            assertContentEquals(payload, sftp.read("/upload.bin"))
        }
    }

    @Test
    fun `write truncates and overwrites an existing file`() = runTest {
        withSftp { sftp ->
            sftp.write("/readme.txt", "short".encodeToByteArray())
            assertContentEquals("short".encodeToByteArray(), sftp.read("/readme.txt"))
        }
    }

    @Test
    fun `download streams a remote file to a local path and reports progress`() = runTest {
        val dest = Files.createTempDirectory("skerry-sftp-dl").resolve("readme.txt")
        try {
            val progress = mutableListOf<Pair<Long, Long>>()
            withSftp { sftp ->
                sftp.download("/readme.txt", dest.toString()) { transferred, total ->
                    progress += transferred to total
                }
            }
            assertEquals(README_BODY, Files.readString(dest))
            assertTrue(progress.isNotEmpty(), "expected at least one progress callback")
            val (lastTransferred, lastTotal) = progress.last()
            assertEquals(README_BODY.length.toLong(), lastTransferred)
            assertEquals(README_BODY.length.toLong(), lastTotal)
        } finally {
            dest.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `upload streams a local file to the remote and reports progress`() = runTest {
        val payload = "uploaded by skerry stream\n"
        val src = Files.createTempDirectory("skerry-sftp-ul").resolve("payload.txt")
        src.writeText(payload)
        try {
            val progress = mutableListOf<Pair<Long, Long>>()
            withSftp { sftp ->
                sftp.upload(src.toString(), "/uploaded.txt") { transferred, total ->
                    progress += transferred to total
                }
                assertContentEquals(payload.encodeToByteArray(), sftp.read("/uploaded.txt"))
            }
            assertTrue(progress.isNotEmpty(), "expected at least one progress callback")
            assertEquals(payload.length.toLong(), progress.last().first)
        } finally {
            src.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download narrows the remote mode instead of copying it`() = runTest {
        // The remote mode is the server's to choose: 0666 copied onto a local file makes it
        // world-writable, 0400 leaves the user unable to edit what they just downloaded — and 0600
        // must stay 0600, or a key pulled from a host is readable by every local account.
        val dir = Files.createTempDirectory("skerry-sftp-dl-mode")
        try {
            root.resolve("open.txt").writeText("open")
            Files.setPosixFilePermissions(root.resolve("open.txt"), PosixFilePermissions.fromString("rw-rw-rw-"))
            root.resolve("closed.txt").writeText("closed")
            Files.setPosixFilePermissions(root.resolve("closed.txt"), PosixFilePermissions.fromString("r--------"))

            root.resolve("secret.txt").writeText("secret")
            Files.setPosixFilePermissions(root.resolve("secret.txt"), PosixFilePermissions.fromString("rw-------"))

            withSftp { sftp ->
                sftp.download("/open.txt", dir.resolve("open.txt").toString())
                sftp.download("/closed.txt", dir.resolve("closed.txt").toString())
                sftp.download("/secret.txt", dir.resolve("secret.txt").toString())
            }

            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secret.txt"))))

            val open = Files.getPosixFilePermissions(dir.resolve("open.txt"))
            assertTrue(PosixFilePermission.OTHERS_WRITE !in open, "world-writable after download: $open")
            assertTrue(PosixFilePermission.GROUP_WRITE !in open, "group-writable after download: $open")
            val closed = Files.getPosixFilePermissions(dir.resolve("closed.txt"))
            assertTrue(
                PosixFilePermission.OWNER_READ in closed && PosixFilePermission.OWNER_WRITE in closed,
                "owner locked out after download: $closed",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download keeps the remote modification time`() = runTest {
        val dir = Files.createTempDirectory("skerry-sftp-dl-mtime")
        try {
            val stamp = FileTime.from(1_577_836_800, TimeUnit.SECONDS) // 2020-01-01T00:00:00Z
            Files.setLastModifiedTime(root.resolve("readme.txt"), stamp)

            withSftp { sftp -> sftp.download("/readme.txt", dir.resolve("readme.txt").toString()) }

            assertEquals(stamp.to(TimeUnit.SECONDS), Files.getLastModifiedTime(dir.resolve("readme.txt")).to(TimeUnit.SECONDS))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download into a local directory is refused`() = runTest {
        // sshj would write under the remote's basename inside it, somewhere the caller never named.
        val dir = Files.createTempDirectory("skerry-sftp-dl-dir")
        try {
            withSftp { sftp -> assertFailsWith<SftpException> { sftp.download("/readme.txt", dir.toString()) } }

            assertFalse(Files.exists(dir.resolve("readme.txt")), "written inside the directory")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a failed download keeps the local file it was about to replace`() = runTest {
        val dir = Files.createTempDirectory("skerry-sftp-dl-fail")
        val dest = dir.resolve("readme.txt")
        dest.writeText("the only copy")
        try {
            withSftp { sftp ->
                assertFailsWith<SftpException> {
                    sftp.download("/readme.txt", dest.toString()) { _, _ -> throw IOException("link dropped") }
                }
            }

            assertEquals("the only copy", Files.readString(dest))
            assertEquals(listOf("readme.txt"), dir.toFile().list()!!.toList(), "a partial file was left behind")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download replaces an existing local file whole`() = runTest {
        val dir = Files.createTempDirectory("skerry-sftp-dl-over")
        val dest = dir.resolve("readme.txt")
        dest.writeText("an older and much longer body than the remote one")
        try {
            withSftp { sftp -> sftp.download("/readme.txt", dest.toString()) }

            assertEquals(README_BODY, Files.readString(dest))
            assertEquals(listOf("readme.txt"), dir.toFile().list()!!.toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `upload over a symlink writes through the link`() = runTest {
        // An upload writes into the path the user named, as OpenSSH's sftp does. Staging beside it and
        // renaming over it replaced the link with a plain file, dropped the owner and group of the
        // file it replaced, and failed outright in a directory the user can write files in but not
        // create them in.
        Files.createSymbolicLink(root.resolve("readme-link"), root.resolve("readme.txt").fileName)
        val src = Files.createTempDirectory("skerry-sftp-ul-link").resolve("payload.txt")
        src.writeText("new")
        try {
            withSftp { sftp -> sftp.upload(src.toString(), "/readme-link") }
            assertTrue(Files.isSymbolicLink(root.resolve("readme-link")), "the link was replaced by a file")
            assertEquals("new", root.resolve("readme.txt").readText())
        } finally {
            src.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `upload keeps the mode of the remote file it overwrites`() = runTest {
        // sshj's "preserve attributes" sends 0644 for every local file, whatever its mode: a 0600
        // secret edited and uploaded back would become readable by every account on the host.
        val secret = root.resolve("secret.env")
        secret.writeText("old")
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"))
        val src = Files.createTempDirectory("skerry-sftp-ul-mode").resolve("payload.txt")
        src.writeText("new")
        try {
            withSftp { sftp -> sftp.upload(src.toString(), "/secret.env") }

            assertEquals("new", secret.readText())
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secret)))
        } finally {
            src.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `upload replaces an existing remote file whole`() = runTest {
        val src = Files.createTempDirectory("skerry-sftp-ul-over").resolve("payload.txt")
        src.writeText("new")
        try {
            withSftp { sftp ->
                sftp.upload(src.toString(), "/readme.txt")
                assertContentEquals("new".encodeToByteArray(), sftp.read("/readme.txt"))
            }
            assertEquals(setOf("readme.txt", "sub", "sub-link"), root.toFile().list()!!.toSet())
        } finally {
            src.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download of a missing remote file throws SftpException`() = runTest {
        val dest = Files.createTempDirectory("skerry-sftp-dl-miss").resolve("out.bin")
        try {
            withSftp { sftp ->
                assertFailsWith<SftpException> { sftp.download("/nope.txt", dest.toString()) }
            }
        } finally {
            dest.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mkdir then stat shows the new directory`() = runTest {
        withSftp { sftp ->
            sftp.mkdir("/created")
            assertEquals(SftpEntryType.Directory, sftp.stat("/created")?.type)
        }
    }

    @Test
    fun `rename moves a file to a new name`() = runTest {
        withSftp { sftp ->
            sftp.rename("/readme.txt", "/renamed.txt")
            assertNull(sftp.stat("/readme.txt"))
            assertContentEquals(README_BODY.encodeToByteArray(), sftp.read("/renamed.txt"))
        }
    }

    @Test
    fun `remove deletes a file`() = runTest {
        withSftp { sftp ->
            sftp.remove("/readme.txt")
            assertNull(sftp.stat("/readme.txt"))
        }
    }

    @Test
    fun `rmdir deletes an empty directory`() = runTest {
        withSftp { sftp ->
            sftp.mkdir("/empty")
            sftp.rmdir("/empty")
            assertNull(sftp.stat("/empty"))
        }
    }

    @Test
    fun `list on a missing path throws SftpException`() = runTest {
        withSftp { sftp ->
            assertFailsWith<SftpException> { sftp.list("/does-not-exist", MAX_LISTING_ENTRIES) }
        }
    }

    @Test
    fun `read on a directory throws SftpException`() = runTest {
        withSftp { sftp ->
            assertFailsWith<SftpException> { sftp.read("/sub") }
        }
    }

    @Test
    fun `rmdir on a non-empty directory throws SftpException`() = runTest {
        withSftp { sftp ->
            assertFailsWith<SftpException> { sftp.rmdir("/sub") }
        }
    }

    @Test
    fun `lstat and list agree that a symlink is a symlink`() = runTest {
        withSftp { sftp ->
            // stat uses lstat — the link isn't followed, type is Symlink, not the target's Directory.
            assertEquals(SftpEntryType.Symlink, sftp.stat("/sub-link")?.type)
            val fromList = sftp.list("/", MAX_LISTING_ENTRIES).first { it.name == "sub-link" }
            assertEquals(SftpEntryType.Symlink, fromList.type)
        }
    }

    @Test
    fun `read rejects a file larger than the configured limit`() = runTest {
        // README_BODY = 11 bytes; limit 4 bytes — read must be rejected, not loaded into memory.
        val (ssh, sftp) = rawSftp(maxReadBytes = 4)
        try {
            assertFailsWith<SftpException> { sftp.read("/readme.txt") }
        } finally {
            sftp.close()
            ssh.disconnect()
        }
    }

    @Test
    fun `operations after close report a closed channel`() = runTest {
        val connection = connect()
        try {
            val sftp = connection.openSftp()
            sftp.close()
            assertFailsWith<SftpException> { sftp.list("/", MAX_LISTING_ENTRIES) }
            assertFailsWith<SftpException> { sftp.stat("/readme.txt") }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * SFTP client with a configurable read limit via [SshjSftpClient]'s direct constructor
     * (bypassing [SshConnection.openSftp], which uses the default limit). Also returns the
     * raw [SSHClient] so the test can close it.
     */
    private fun rawSftp(maxReadBytes: Long): Pair<SSHClient, SshjSftpClient> {
        val ssh = SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connect("127.0.0.1", server.port)
            authPassword(USER, PASSWORD)
        }
        return ssh to SshjSftpClient(ssh.newSFTPClient(), maxReadBytes)
    }
}
