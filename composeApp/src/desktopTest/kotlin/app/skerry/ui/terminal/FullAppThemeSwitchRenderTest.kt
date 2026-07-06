package app.skerry.ui.terminal

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import app.skerry.shared.sftp.SftpClient
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
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.desktop.DesktopDesignApp
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation

/**
 * Regression test for theme switching in the full app: [DesktopDesignApp] with a live
 * [SessionsController] over a fake transport (like the offscreen harness in design/Screenshot.kt),
 * terminal open and showing output with an ANSI background (SGR 44). Switches theme via
 * [DesktopDesignState.chooseTerminalTheme] (same as clicking the Appearance card) and checks that no
 * pixel of the old palette remains, scanning the whole frame.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FullAppThemeSwitchRenderTest {

    @Test
    fun themeSwitchRepaintsLiveTerminalInFullApp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hosts = seededHosts()
        val sessions = SessionsController(
            newId = generateSequence(0) { it + 1 }.map { "s$it" }.iterator()::next,
            controllerFactory = { ConnectionController(fakeTransport(), scope) },
        )
        val h = hosts.hosts.first()
        sessions.open(h.id, h.label, h.connectionSubtitle(), h.toTarget(), SshAuth.Password(""))
        sessions.activate(sessions.sessions.first().id)
        val state = DesktopDesignState()
        try {
            ImageComposeScene(width = 1280, height = 820, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme { DesktopDesignApp(state = state, hosts = hosts, sessions = sessions) }
                }
                var timeNanos = 0L
                fun frame(): PixelMap {
                    Snapshot.sendApplyNotifications()
                    timeNanos += 16_666_667L
                    val img = scene.render(timeNanos).toComposeImageBitmap().toPixelMap()
                    Thread.sleep(16) // async fonts/fake connection, as in Screenshot.kt
                    return img
                }

                // Wait for a live terminal with the Night Sea palette (SGR 44 fill).
                var pixels = frame()
                var attempts = 0
                while (!pixels.hasColor(NIGHT_SEA_ANSI_BLUE) && attempts < 120) {
                    pixels = frame()
                    attempts++
                }
                assertTrue(
                    pixels.hasColor(NIGHT_SEA_ANSI_BLUE),
                    "did not see a live terminal with SGR 44 fill in the Night Sea palette",
                )

                // Exact user path: open Settings -> Appearance over the terminal, click the theme
                // card, close settings; the terminal stays in composition the whole time.
                state.openSettings()
                state.showSettingsTab(app.skerry.ui.app.SettingsTab.Appearance)
                repeat(3) { pixels = frame() }
                state.chooseTerminalTheme(TerminalThemes.SolarizedLight)
                repeat(3) { pixels = frame() }
                state.closeSettings()
                repeat(5) { pixels = frame() }

                assertTrue(
                    pixels.hasColor(SOLARIZED_BG),
                    "terminal background should recolor to Solarized Light",
                )
                assertTrue(
                    pixels.hasColor(SOLARIZED_ANSI_BLUE),
                    "SGR 44 fill should recolor to Solarized blue",
                )
                if (pixels.hasColor(NIGHT_SEA_ANSI_BLUE)) {
                    fail("Night Sea blue pixels (ANSI 4) remain: terminal was not repainted after theme switch")
                }
            }
        } finally {
            sessions.disconnectAll()
            scope.cancel()
        }
    }

    private fun PixelMap.hasColor(argb: Int): Boolean {
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                if (this[x, y].toArgb() == argb) return true
            }
        }
        return false
    }

    private fun seededHosts(): HostManagerController {
        val store = object : HostStore {
            private val items = LinkedHashMap<String, Host>()
            override fun all(): List<Host> = items.values.toList()
            override fun put(host: Host) { items[host.id] = host }
            override fun remove(id: String) { items.remove(id) }
            override fun reorder(transform: (List<Host>) -> List<Host>) {
                val updated = transform(items.values.toList())
                items.clear()
                updated.forEach { items[it.id] = it }
            }
        }
        store.put(Host("h1", "prod-web-01", "192.168.1.45", 22, "root", "Production"))
        var seq = 0
        return HostManagerController(store) { "gen-${seq++}" }
    }

    private fun fakeTransport(): SshTransport = object : SshTransport {
        override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = FakeConnection()
    }

    private class FakeConnection : SshConnection {
        override val isConnected: Boolean = true
        override val cipher: String? = "chacha20-poly1305@openssh.com"
        override suspend fun exec(command: String): ExecResult = ExecResult(0, "", "")
        override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel()
        override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
        override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
        override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
        override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
        override suspend fun disconnect() {}
    }

    /** Shell: banner + a line with SGR 44 fill (spaces, pure color without glyphs), then hangs. */
    private class FakeChannel : ShellChannel {
        override val isOpen: Boolean = true
        override val output: Flow<ByteArray> = flow {
            emit(("Last login: Sat Jul  4 08:02:59 2026\r\n" +
                "\u001b[44m                    \u001b[0m\r\n" +
                "root@Uran:~# ").encodeToByteArray())
            awaitCancellation()
        }
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(size: PtySize) {}
        override suspend fun close() {}
    }

    private companion object {
        val NIGHT_SEA_ANSI_BLUE = 0xFF4A9EDB.toInt()
        val SOLARIZED_BG = 0xFFFDF6E3.toInt()
        val SOLARIZED_ANSI_BLUE = 0xFF268BD2.toInt()
    }
}
