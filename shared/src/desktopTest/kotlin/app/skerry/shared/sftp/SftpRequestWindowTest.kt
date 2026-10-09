package app.skerry.shared.sftp

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.Request
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import kotlin.test.Test
import kotlin.test.assertEquals

/** Trace actual requests while the server holds its first reply; no throughput/timing assertion. */
class SftpRequestWindowTest {
    @Test
    fun `download queues more than the old window before awaiting server data`() = fillsWindow(upload = false)

    @Test
    fun `upload queues more than the old window before awaiting write acknowledgements`() = fillsWindow(upload = true)

    private fun fillsWindow(upload: Boolean) = runBlocking {
        val root = Files.createTempDirectory("skerry-sftp-request-server")
        val local = Files.createTempDirectory("skerry-sftp-request-local")
        val payload = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }
        Files.write((if (upload) local else root).resolve("large.bin"), payload)
        val stalled = CompletableDeferred<Unit>()
        val filled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val listener = object : SftpEventListener {
            private fun stall() {
                stalled.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS)) { "Request-window test was not released" }
            }
            override fun reading(session: ServerSession, remoteHandle: String, localHandle: FileHandle,
                offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                if (!upload) stall()
            }
            override fun writing(session: ServerSession, remoteHandle: String, localHandle: FileHandle,
                offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                if (upload) stall()
            }
        }
        val server = startSftpTestServer(root, "test", "test", listener, metadataDelayMillis = 50)
        try {
            SSHClient().use { ssh ->
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connect("127.0.0.1", server.port)
                ssh.authPassword("test", "test")
                val kind = if (upload) PacketType.WRITE else PacketType.READ
                val count = AtomicInteger()
                val engine = object : SFTPEngine(ssh) {
                    override fun request(req: Request): Promise<Response, SFTPException> {
                        val response = super.request(req)
                        // Trace emitted packets; the server cannot reply until the assertion releases it.
                        if (req.type == kind && count.incrementAndGet() == 32) filled.complete(Unit)
                        return response
                    }
                }
                engine.init()
                val client = SshjSftpClient(SFTPClient(engine))
                try {
                    val transfer = async {
                        if (upload) client.upload(local.resolve("large.bin").toString(), "/large.bin")
                        else client.download("/large.bin", local.resolve("large.bin").toString())
                    }
                    try {
                        withTimeout(5_000) { stalled.await(); filled.await() }
                        release.countDown()
                        transfer.await()
                        assertEquals(-1L, Files.mismatch(root.resolve("large.bin"), local.resolve("large.bin")))
                    } finally {
                        release.countDown()
                        transfer.cancelAndJoin()
                    }
                } finally {
                    client.close()
                }
            }
        } finally {
            release.countDown()
            server.stop(true)
            root.toFile().deleteRecursively()
            local.toFile().deleteRecursively()
        }
    }
}
