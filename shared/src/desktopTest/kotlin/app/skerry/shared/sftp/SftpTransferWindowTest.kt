package app.skerry.shared.sftp

import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SftpTransferWindowTest {
    @Test
    fun `multi-window transfer preserves the final partial packet and cumulative progress`() = withServer(metadataDelayMillis = 50) { client, root, local ->
        // More than an entire request window, followed by a partial packet.
        val payload = ByteArray(3 * 1024 * 1024 + 23) { (it * 31).toByte() }
        val source = local.resolve("source.bin")
        Files.write(source, payload)
        val upload = mutableListOf<Pair<Long, Long>>()
        client.upload(source.toString(), "/file.bin") { done, total -> upload += done to total }
        assertContentEquals(payload, Files.readAllBytes(root.resolve("file.bin")))
        val download = mutableListOf<Pair<Long, Long>>()
        client.download("/file.bin", local.resolve("copy.bin").toString()) { done, total -> download += done to total }
        assertContentEquals(payload, Files.readAllBytes(local.resolve("copy.bin")))
        for (progress in listOf(upload, download)) {
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first })
            assertTrue(progress.all { it.second == payload.size.toLong() && it.first <= it.second })
            assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        }
    }

    @Test
    fun `empty files transfer in both directions`() = withServer { client, root, local ->
        val source = Files.createFile(local.resolve("empty.bin"))
        client.upload(source.toString(), "/empty.bin")
        client.download("/empty.bin", local.resolve("copy.bin").toString())
        assertEquals(0L, Files.size(root.resolve("empty.bin")))
        assertEquals(0L, Files.size(local.resolve("copy.bin")))
    }

    @Test
    fun `upload to a remote directory retains the local basename`() = withServer { client, root, local ->
        Files.createDirectory(root.resolve("folder"))
        val source = local.resolve("source.bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        client.upload(source.toString(), "/folder")
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(root.resolve("folder/source.bin")))
    }

    @Test
    fun `an error in the final write acknowledgement fails upload and leaves the channel reusable`() {
        val listener = object : SftpEventListener {
            override fun writing(session: ServerSession, remoteHandle: String, localHandle: FileHandle,
                offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                if (localHandle.file.fileName.toString() == "denied.bin") throw IOException("Write refused")
            }
        }
        withServer(listener) { client, root, local ->
            val source = local.resolve("source.bin")
            Files.write(source, ByteArray(4096) { 7 })
            // One packet: its STATUS must be checked when the output stream drains.
            assertFailsWith<SftpException> { client.upload(source.toString(), "/denied.bin") }
            client.upload(source.toString(), "/next.bin")
            assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(root.resolve("next.bin")))
            assertContentEquals(Files.readAllBytes(source), client.read("/next.bin"))
        }
    }

    private fun withServer(
        listener: SftpEventListener? = null,
        metadataDelayMillis: Long = 0,
        block: suspend (SftpClient, Path, Path) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("skerry-sftp-window-server")
        val local = Files.createTempDirectory("skerry-sftp-window-local")
        val server = startSftpTestServer(root, "test", "test", listener, metadataDelayMillis)
        try {
            val connection = SshjTransport(HostKeyVerifier { null }).connect(
                SshTarget("127.0.0.1", server.port, "test"), SshAuth.Password("test"))
            try {
                val client = connection.openSftp()
                try { block(client, root, local) } finally { client.close() }
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop(true)
            root.toFile().deleteRecursively()
            local.toFile().deleteRecursively()
        }
    }
}
