package app.skerry.ui.desktop

import app.skerry.shared.graphics.RemoteDesktopCapabilities
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.shared.graphics.RemoteRect
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

    override suspend fun write(data: ByteArray) = FakeShellInput.record(data.decodeToString())
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}

/**
 * What has been typed into the fake shells, in order.
 *
 * The channel has no server behind it, so nothing it is sent comes back as echo — this log is the
 * only place a test can see that a snippet, a runbook step or a keystroke actually reached the
 * session rather than stopping one layer short of it. Shared across the fakes because a test holds
 * the session, never the channel; call [clear] before the act it is about.
 */
internal object FakeShellInput {
    private val lines = mutableListOf<String>()

    /** Called from the writer's coroutine, read from the test thread — hence the lock. */
    fun record(text: String) = synchronized(lines) {
        // Bounded on purpose: this object sits in desktopMain beside the other screenshot fakes, so
        // it is compiled into the shipped jar even though only the offscreen render and the tests can
        // reach it. An unbounded record of everything typed into a shell is not a thing to ship,
        // however unreachable it is.
        if (lines.size >= CAPACITY) lines.removeAt(0)
        lines += text
        Unit
    }

    fun all(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }

    private const val CAPACITY = 256
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

/**
 * How many keys the fake desktop was handed. A count, not the keys themselves: the shell tests only
 * need to know whether typing reached the session, and what is typed into one is not a thing to
 * keep — see [FakeShellInput] on why this lives in the shipped jar at all.
 */
internal object FakeRemoteInput {
    private var keys = 0

    /** Called from the input actor's coroutine, read from the test thread — hence the lock. */
    fun record() = synchronized(this) { keys++; Unit }

    fun keys(): Int = synchronized(this) { keys }

    fun clear() = synchronized(this) { keys = 0 }
}

/**
 * Fake remote desktop: a still 1440×900 picture (gradient wallpaper with two "windows" on it) and
 * an update flow that hands it over once and then hangs. Lets the Desktops section render a live
 * session — its floating bar, its menus — without a VNC/RDP server.
 */
internal fun fakeRemoteDesktop(title: String): RemoteDesktopSession = object : RemoteDesktopSession {
    override val title: String = title
    override val framebuffer = RemoteFramebuffer(FAKE_DESKTOP_WIDTH, FAKE_DESKTOP_HEIGHT).also { paintFakeDesktop(it) }
    override val capabilities = RemoteDesktopCapabilities(
        adjustableQuality = true,
        remoteResize = true,
        cursorHandover = true,
        audio = true,
        clipboard = true,
    )
    override val updates: Flow<RemoteDesktopUpdate> = flow {
        emit(RemoteDesktopUpdate.Region(listOf(RemoteRect(0, 0, FAKE_DESKTOP_WIDTH, FAKE_DESKTOP_HEIGHT))))
        awaitCancellation()
    }

    override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int) = Unit
    override suspend fun sendKey(event: RemoteKeyEvent, down: Boolean) = FakeRemoteInput.record()
    override suspend fun sendClipboardText(text: String) = Unit
    override suspend fun requestFullUpdate() = Unit
    override suspend fun setQuality(quality: RemoteDesktopQuality) = Unit
    override suspend fun setDesktopSize(width: Int, height: Int, scale: Float) = Unit
    override suspend fun setLocalCursor(enabled: Boolean) = Unit
    override suspend fun setOutputVisible(visible: Boolean) = Unit
    override suspend fun setAudioMuted(muted: Boolean) = Unit
    override suspend fun close() = Unit
}

private fun paintFakeDesktop(fb: RemoteFramebuffer) {
    for (y in 0 until FAKE_DESKTOP_HEIGHT) {
        val shade = 0x18 + (y * 0x22 / FAKE_DESKTOP_HEIGHT)
        val argb = 0xFF shl 24 or (shade / 2 shl 16) or (shade shl 8) or (shade + 0x2A)
        val row = IntArray(FAKE_DESKTOP_WIDTH) { argb }
        fb.blitRow(0, y, FAKE_DESKTOP_WIDTH, row, 0)
    }
    fakeWindow(fb, x = 90, y = 120, w = 640, h = 380)
    fakeWindow(fb, x = 500, y = 380, w = 700, h = 440)
}

private fun fakeWindow(fb: RemoteFramebuffer, x: Int, y: Int, w: Int, h: Int) {
    for (dy in 0 until h) {
        val titleBar = dy < 26
        val argb = if (titleBar) 0xFF2B3550.toInt() else 0xFF10141F.toInt()
        val row = IntArray(w) { argb }
        fb.blitRow(x, y + dy, w, row, 0)
    }
}

private const val FAKE_DESKTOP_WIDTH = 1440
private const val FAKE_DESKTOP_HEIGHT = 900
