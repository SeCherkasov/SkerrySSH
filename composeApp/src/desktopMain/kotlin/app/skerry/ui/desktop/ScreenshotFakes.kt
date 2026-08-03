package app.skerry.ui.desktop

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.tunnel.SERVICE_SCAN_COMMAND
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Fake SSH transport: the shell emits a canned banner+listing, then hangs until cancelled. */
internal fun fakeTransport(): SshTransport = object : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = FakeConnection(target)
}

internal class FakeConnection(private val target: SshTarget) : SshConnection {
    override val isConnected: Boolean = true
    override val cipher: String? = "chacha20-poly1305@openssh.com"

    // Realistic METRICS_COMMAND output so the offscreen render of the info panel shows a live
    // monitor (CPU/memory/swap, network, filesystems, top processes, host facts) instead of "..."
    // placeholders. The network counters advance per poll so the rates and their sparklines have
    // something to draw.
    private var polls = 0

    override suspend fun exec(command: String): ExecResult {
        // Service discovery asks a different question than the metrics poll; answer it with canned
        // `ss -ltnp` output so the offscreen Ports render shows a real scan result.
        if (command == SERVICE_SCAN_COMMAND) return ExecResult(0, SEEDED_SS_OUTPUT, "")
        val tick = polls++
        return ExecResult(
            exitCode = 0,
            stdout = """
                cpu  100 0 100 800 0 0 0 0
                cpu  168 0 132 900 0 0 0 0
                @MEM
                Mem:     4000000000  2100000000  1000000000
                Swap:    2000000000   210000000  1790000000
                @DISK
                Filesystem     1024-blocks      Used Available Capacity Mounted on
                /dev/sda1         51475068  42000000   6900000      87% /
                tmpfs               400000      1000    399000       1% /run
                /dev/sda2        209715200 120000000  78000000      62% /var
                @NET
                    lo: 1000000 100 0 0 0 0 0 0 1000000 100
                  eth0: ${'$'}{4_000_000 + tick * 380_000} 500 0 0 0 0 0 0 ${'$'}{1_200_000 + tick * 95_000} 200
                @PROC
                2481  12.4  8.1 postgres
                 991   4.2  2.3 nginx
                1204   1.8  5.7 node
                @UPTIME
                372765.42 1488907.15
                @LOAD
                0.42 0.51 0.48 1/512 28931
                @OS
                PRETTY_NAME="Ubuntu 22.04.4 LTS"
                @KERNEL
                Linux 5.15.0-105-generic x86_64
                @CPU
                4
            """.trimIndent(),
            stderr = "",
        )
    }

    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel(target)
    override suspend fun openSftp(): SftpClient = FakeSftpClient()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 50080)
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 9000)
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 1080)
    override suspend fun disconnect() {}
}

/** Fake forward: immediately "active", port echoed from the spec, for the offscreen tunnels table. */
internal class FakePortForward(override val boundPort: Int) : PortForward {
    override val isActive: Boolean = true
    override var isPaused: Boolean = false
        private set

    // Counters grow with every read, on a small wave, so the traffic column and the throughput
    // sparkline render with something in them. A constant would draw a flat line and read as a bug.
    private var reads = 0L
    override val bytesUp: Long get() = 46_000L * ++reads + (reads % 11) * 2_400
    override val bytesDown: Long get() = 138_000L * reads + (reads % 13) * 5_800
    override suspend fun pause() { isPaused = true }
    override suspend fun resume() { isPaused = false }
    override suspend fun close() = Unit
}

internal class FakeChannel(target: SshTarget) : ShellChannel {
    private val prompt = "${target.username}@${target.host.substringBefore('.')}:~# "
    private val banner =
        "Last login: Sat Jun 21 14:22:10 2026 from 10.0.0.15\r\n" +
            "$prompt" + "ls -la\r\n" +
            "total 24\r\n" +
            "drwxr-xr-x  5 root root 4096 Jun 21 14:02 app\r\n" +
            "drwxr-xr-x  2 root root 4096 Jun 21 09:11 deploy\r\n" +
            "-rw-r--r--  1 root root  812 Jun 20 23:40 backup.tar.gz\r\n" +
            "$prompt" + "df -h /\r\n" +
            "Filesystem      Size  Used Avail Use% Mounted on\r\n" +
            "/dev/sda1        50G   42G  5.2G  87% /\r\n" +
            prompt

    override val isOpen: Boolean = true
    override val output: Flow<ByteArray> = flow {
        emit(banner.encodeToByteArray())
        awaitCancellation()
    }

    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}

/** Fake SFTP client with a canned `/var/www` listing, for the offscreen render of a live panel. */
internal class FakeSftpClient : SftpClient {
    private val listing = listOf(
        SftpEntry("html", "/var/www/html", SftpEntryType.Directory, 4096, 0, 0b111_101_101),
        SftpEntry("releases", "/var/www/releases", SftpEntryType.Directory, 4096, 0, 0b111_101_101),
        SftpEntry("nginx.conf", "/var/www/nginx.conf", SftpEntryType.File, 3174, 0, 0b110_100_100),
        SftpEntry("robots.txt", "/var/www/robots.txt", SftpEntryType.File, 112, 0, 0b110_100_100),
        SftpEntry("deploy.sh", "/var/www/deploy.sh", SftpEntryType.File, 1843, 0, 0b111_101_101),
    )

    override suspend fun list(path: String): List<SftpEntry> = listing
    override suspend fun stat(path: String): SftpEntry? = null
    override suspend fun realpath(path: String): String = "/var/www"
    override suspend fun read(path: String, maxBytes: Long): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) = Unit
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) = Unit
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) = Unit
    override suspend fun mkdir(path: String) = Unit
    override suspend fun remove(path: String) = Unit
    override suspend fun rmdir(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun close() = Unit
}
