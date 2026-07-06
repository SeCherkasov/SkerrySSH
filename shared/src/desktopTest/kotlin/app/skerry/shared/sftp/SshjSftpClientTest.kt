package app.skerry.shared.sftp

import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"
private const val README_BODY = "hello sftp\n"

private val acceptAllKeys = HostKeyVerifier { _, _, _, _ -> true }

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

        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
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
            val names = sftp.list("/").map { it.name }
            assertTrue("readme.txt" in names, "expected readme.txt, got $names")
            assertTrue("sub" in names, "expected directory sub, got $names")
            assertTrue("." !in names && ".." !in names, "listing should not contain . or .., got $names")
        }
    }

    @Test
    fun `list distinguishes files from directories`() = runTest {
        withSftp { sftp ->
            val byName = sftp.list("/").associateBy { it.name }
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
            assertFailsWith<SftpException> { sftp.list("/does-not-exist") }
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
            val fromList = sftp.list("/").first { it.name == "sub-link" }
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
            assertFailsWith<SftpException> { sftp.list("/") }
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
