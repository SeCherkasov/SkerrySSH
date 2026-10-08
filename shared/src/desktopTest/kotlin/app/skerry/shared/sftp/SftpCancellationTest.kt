package app.skerry.shared.sftp

import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real sshj IO must stop before the server replies, without closing the SSH or SFTP session. */
class SftpCancellationTest {
    @Test
    fun `cancel interrupts a stalled download and preserves the previous local target`() = stalledTransfer(upload = false)

    @Test
    fun `cancel interrupts a stalled upload and the channel can transfer again`() = stalledTransfer(upload = true)

    private fun stalledTransfer(upload: Boolean) = runBlocking {
        val root = Files.createTempDirectory("skerry-sftp-cancel-server")
        val local = Files.createTempDirectory("skerry-sftp-cancel-local")
        val stalled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val listener = object : SftpEventListener {
            private fun stall(handle: FileHandle) {
                if (handle.file.fileName.toString() != "large.bin") return
                stalled.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS)) { "Test server was not released" }
            }
            override fun reading(session: ServerSession, remoteHandle: String, localHandle: FileHandle,
                offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                if (!upload) stall(localHandle)
            }
            override fun writing(session: ServerSession, remoteHandle: String, localHandle: FileHandle,
                offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                if (upload) stall(localHandle)
            }
        }
        root.resolve("readme.txt").writeText("channel still works")
        Files.write((if (upload) local else root).resolve("large.bin"), ByteArray(1024 * 1024))
        if (!upload) local.resolve("large.bin").writeText("previous target")
        val subsystem = SftpSubsystemFactory.Builder().apply { addSftpEventListener(listener) }.build()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == "test" && password == "test" }
            subsystemFactories = listOf(subsystem)
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
        try {
            val connection = SshjTransport(HostKeyVerifier { null }).connect(
                SshTarget("127.0.0.1", server.port, "test"), SshAuth.Password("test"))
            try {
                val sftp = connection.openSftp()
                try {
                    val job = launch {
                        if (upload) sftp.upload(local.resolve("large.bin").toString(), "/large.bin")
                        else sftp.download("/large.bin", local.resolve("large.bin").toString())
                    }
                    cancelWhileStalled(job, stalled, release)
                    assertTrue(job.isCancelled)
                    if (!upload) {
                        assertEquals("previous target", local.resolve("large.bin").readText())
                        Files.list(local).use { paths ->
                            assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".skerry-part") })
                        }
                    }
                    // Reusing this exact channel catches interrupt flags and abandoned request
                    // replies contaminating the next operation; reopening would conceal both.
                    withTimeout(5000) {
                        assertEquals("channel still works", sftp.read("/readme.txt").decodeToString())
                        local.resolve("next.txt").writeText("next upload")
                        sftp.upload(local.resolve("next.txt").toString(), "/next.txt")
                        assertEquals("next upload", sftp.read("/next.txt").decodeToString())
                    }
                } finally {
                    sftp.close()
                }
            } finally {
                connection.disconnect()
            }
        } finally {
            release.countDown()
            server.stop(true)
            root.toFile().deleteRecursively()
            local.toFile().deleteRecursively()
        }
    }

    private suspend fun cancelWhileStalled(job: Job, stalled: CompletableDeferred<Unit>, release: CountDownLatch) {
        try {
            withTimeout(5000) { stalled.await() }
            job.cancel()
            val stopped = withTimeoutOrNull(2000) { job.join(); true } ?: false
            assertTrue(stopped, "Cancellation waited for the stalled server instead of interrupting IO")
        } finally {
            release.countDown()
            job.cancel()
            job.join()
        }
    }
}
