package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
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
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Офскрин-рендер десктопного дизайна в PNG для визуальной проверки без окна/композитора.
 * Управляется системным свойством `skerry.screenshot.*`: out — путь PNG, view/overlay — что показать,
 * `live=true` — подать засеянные [HostManagerController] + [SessionsController] (живой сайдбар,
 * вкладки, терминал поверх фейкового транспорта с заготовленным выводом). Не часть приложения;
 * запускается Gradle-задачей `screenshotDesign`.
 *
 * Оверлеи `create`/`unlock` рендерят живые экраны гейта мастер-пароля ([DesktopCreateScreen]/
 * [DesktopUnlockScreen]) standalone (без `VaultGateController`/lifecycle) — проверка визуала; их
 * проводка к [app.skerry.ui.vault.VaultGate] покрыта тестами контроллера и компиляцией.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val out = System.getProperty("skerry.screenshot.out", "/tmp/skerry_design.png")
    val viewName = System.getProperty("skerry.screenshot.view", "Terminal")
    val overlay = System.getProperty("skerry.screenshot.overlay", "")
    val live = System.getProperty("skerry.screenshot.live", "false").toBoolean()

    val state = DesktopDesignState()
    runCatching { state.showView(DesktopView.valueOf(viewName)) }
    when (overlay) {
        "lock" -> state.lock()
        "modal" -> state.openModal()
        "settings" -> state.openSettings()
    }

    val hosts = if (live) seededHosts() else null
    val sessions = if (live && hosts != null) seededSessions(hosts) else null

    val content: @Composable () -> Unit = when (overlay) {
        "create" -> { { GateScreenPreview { DesktopCreateScreen(error = null) { _, _ -> } } } }
        "unlock" -> { { GateScreenPreview { DesktopUnlockScreen(error = null, canUseBiometric = true, onUnlock = {}, onBiometric = {}) } } }
        else -> { { DesktopDesignApp(state, hosts = hosts, sessions = sessions) } }
    }

    val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
        SkerryTheme { content() }
    }
    // Пампим кадры с реальной паузой, чтобы compose-resources успели подгрузить шрифты (async IO)
    // и фейковая сессия успела отдать вывод в терминал.
    var img = scene.render(0)
    for (i in 1..80) {
        img = scene.render(i * 16_000_000L)
        Thread.sleep(16)
    }
    val data = img.encodeToData() ?: error("encode failed")
    File(out).writeBytes(data.bytes)
    scene.close()
    sessions?.disconnectAll() // снять коллекторы фейковых сессий перед выходом
    println("screenshot → $out (${File(out).length()} bytes)")
}

/** Поставщик дизайн-шрифтов для standalone-рендера экранов, минуя [DesktopDesignApp]. */
@Composable
private fun GateScreenPreview(body: @Composable () -> Unit) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    CompositionLocalProvider(LocalFonts provides fonts) { body() }
}

/** In-memory каталог хостов с демо-профилями — только для офскрин-рендера живого сайдбара. */
private fun seededHosts(): HostManagerController {
    val store = object : HostStore {
        private val items = LinkedHashMap<String, Host>()
        override fun all(): List<Host> = items.values.toList()
        override fun put(host: Host) { items[host.id] = host }
        override fun remove(id: String) { items.remove(id) }
    }
    listOf(
        Host("h1", "prod-web-01", "192.168.1.45", 22, "root", "Production"),
        Host("h2", "db-master", "192.168.1.50", 22, "root", "Production"),
        Host("h3", "homelab-pi", "10.0.0.12", 22, "pi", "Homelab"),
        Host("h4", "vps-edge", "vps.example.com", 2222, "deploy", null),
    ).forEach(store::put)
    var seq = 0
    return HostManagerController(store) { "gen-${seq++}" }
}

/**
 * Менеджер сессий поверх фейкового транспорта ([fakeTransport]) с одной открытой вкладкой к первому
 * хосту — чтобы офскрин-рендер показал живой терминал/тулбар/вкладки реальными компонентами
 * ([SessionsController]→[ConnectionController]→[TerminalScreen]) без сети.
 */
private fun seededSessions(hosts: HostManagerController): SessionsController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var n = 0
    val sessions = SessionsController(newId = { "s${n++}" }, controllerFactory = { ConnectionController(fakeTransport(), scope) })
    // Пустой пароль: фейковый транспорт auth игнорирует (см. FakeConnection) — реального хендшейка нет.
    val h = hosts.hosts.first()
    sessions.open(h.id, h.label, h.connectionSubtitle(), h.toTarget(), SshAuth.Password(""))
    hosts.hosts.getOrNull(1)?.let { h2 -> sessions.open(h2.id, h2.label, h2.connectionSubtitle(), h2.toTarget(), SshAuth.Password("")) }
    sessions.activate(sessions.sessions.first().id)
    return sessions
}

/** Фейковый SSH-транспорт: shell отдаёт заготовленный баннер+листинг, затем висит до отмены. */
private fun fakeTransport(): SshTransport = object : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = FakeConnection(target)
}

private class FakeConnection(private val target: SshTarget) : SshConnection {
    override val isConnected: Boolean = true
    override suspend fun exec(command: String): ExecResult = ExecResult(0, "", "")
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel(target)
    override suspend fun openSftp(): SftpClient = FakeSftpClient()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = error("fake: no forward")
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = error("fake: no forward")
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = error("fake: no forward")
    override suspend fun disconnect() {}
}

private class FakeChannel(target: SshTarget) : ShellChannel {
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

/** Фейковый SFTP-клиент с заготовленным листингом `/var/www` — для офскрин-рендера живой панели. */
private class FakeSftpClient : SftpClient {
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
    override suspend fun read(path: String): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) = Unit
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) = Unit
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) = Unit
    override suspend fun mkdir(path: String) = Unit
    override suspend fun remove(path: String) = Unit
    override suspend fun rmdir(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun close() = Unit
}
