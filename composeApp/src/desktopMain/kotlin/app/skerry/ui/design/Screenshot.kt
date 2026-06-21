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
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.HostKeyMismatchStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.KnownHostsStore
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.AppDependencies
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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

    // Телефонный макет: рендерим MobileDesignApp в узкой сцене. view=MobileTab (по умолчанию Hosts).
    if (System.getProperty("skerry.screenshot.device", "desktop") == "mobile") {
        renderMobile(out, viewName, live); return
    }

    val state = DesktopDesignState()
    runCatching { state.showView(DesktopView.valueOf(viewName)) }
    when (overlay) {
        "lock" -> state.lock()
        "modal" -> state.openModal()
        "settings" -> state.openSettings()
    }

    val hosts = if (live) seededHosts() else null
    val sessions = if (live && hosts != null) seededSessions(hosts) else null
    val knownHosts = if (live) seededKnownHosts() else null

    val content: @Composable () -> Unit = when (overlay) {
        "create" -> { { GateScreenPreview { DesktopCreateScreen(error = null) { _, _ -> } } } }
        "unlock" -> { { GateScreenPreview { DesktopUnlockScreen(error = null, canUseBiometric = true, onUnlock = {}, onBiometric = {}) } } }
        else -> { { DesktopDesignApp(state, hosts = hosts, sessions = sessions, knownHosts = knownHosts) } }
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

/**
 * Офскрин-рендер телефонного макета ([MobileDesignApp]) в узкой сцене 390×844 (density 2). `view` —
 * имя [MobileTab] (по умолчанию Hosts); `live=true` подаёт засеянный каталог [seededHosts], иначе
 * экран берёт встроенные превью-данные. Аналог desktop-ветки [main]; запускается той же задачей
 * `screenshotDesign` со свойством `skerry.screenshot.device=mobile`.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderMobile(out: String, viewName: String, live: Boolean) {
    val state = MobileDesignState()
    val deps = if (live) AppDependencies(hosts = seededHosts()) else AppDependencies()
    // view — имя MobileTab (корневой) либо MobileRoute (push-экран). HostDetail открывается на первом
    // хосте каталога, чтобы офскрин показал живую деталь.
    val tab = runCatching { MobileTab.valueOf(viewName) }.getOrNull()
    if (tab != null) {
        state.select(tab)
    } else {
        runCatching { MobileRoute.valueOf(viewName) }.getOrNull()?.let { route ->
            if (route == MobileRoute.HostDetail) {
                state.openHost(deps.hosts?.hosts?.firstOrNull()?.id ?: MOBILE_PREVIEW_HOSTS.first().id)
            } else {
                state.push(route)
            }
        }
    }
    // ширина/высота сцены — в ПИКСЕЛЯХ: 780×1688 при density 2 = логические 390×844dp (телефон).
    val scene = ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
        SkerryTheme { MobileDesignApp(deps = deps, state = state) }
    }
    var img = scene.render(0)
    for (i in 1..80) {
        img = scene.render(i * 16_000_000L)
        Thread.sleep(16)
    }
    val data = img.encodeToData() ?: error("encode failed")
    File(out).writeBytes(data.bytes)
    scene.close()
    println("screenshot(mobile) → $out (${File(out).length()} bytes)")
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
 * Менеджер known-hosts с демо-ключами и одним незакрытым событием смены ключа — чтобы офскрин-рендер
 * показал живую таблицу (firstSeen/Verified), предупреждение и панель сравнения отпечатков реальными
 * компонентами ([KnownHostsController]→[KnownHostsView]). In-memory, без файлов.
 */
private fun seededKnownHosts(): KnownHostsController {
    val ed = "ssh-ed25519"
    val store = object : KnownHostsStore {
        private val items = mutableListOf(
            KnownHost("prod-web-01", 22, ed, "SHA256:8c3F1a2bQzABCDEFGHIJKLMNpK9R", "2026-01-12T09:00:00Z"),
            KnownHost("db-master", 22, ed, "SHA256:2dE7bLm4xRABCDEFGHIJKLMNwQ1z", "2026-01-12T09:05:00Z"),
            KnownHost("nas-truenas", 22, ed, "SHA256:9aB0cTn2wE4rXp1kLm7sQ8vZabcd", "2026-03-04T18:30:00Z"),
            KnownHost("homelab-pi", 22, "ssh-rsa", "SHA256:5fG1hKp8sXYZ0123456789vB3nqrst", "2026-02-02T11:15:00Z"),
        )
        override fun all() = items.toList()
        override fun add(host: KnownHost) { items += host }
        override fun replace(host: KnownHost) {
            items.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
            items += host
        }
        override fun remove(host: String, port: Int, keyType: String) {
            items.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        }
    }
    val mismatches = object : HostKeyMismatchStore {
        private val items = mutableListOf(
            HostKeyMismatch("nas-truenas", 22, ed, "SHA256:9aB0cTn2wE4rXp1kLm7sQ8vZabcd", "SHA256:Kp3xQ9zR1tWv7nB4mL0sJ2dFefgh", "2026-06-22T08:00:00Z"),
        )
        override fun all() = items.toList()
        override fun record(mismatch: HostKeyMismatch) {
            items.removeAll { it.host == mismatch.host && it.port == mismatch.port && it.keyType == mismatch.keyType }
            items += mismatch
        }
        override fun clear(host: String, port: Int, keyType: String) {
            items.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        }
    }
    return KnownHostsController(store, mismatches) { "2026-06-22T12:00:00Z" }
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
    // Засеять пробросы на активной сессии для живого скриншота вкладки Tunnels: ждём, пока fake-
    // соединение поднимется (connect идёт асинхронно), затем поднимаем -L/-R/-D тем же путём, что и
    // UI (PortForwardController). Фейковый форвард сразу Active.
    val ctrl = sessions.sessions.first().controller
    scope.launch {
        // uiState — Compose snapshot-стейт; ждём перехода в Connected через snapshotFlow (а не
        // busy-spin), чтобы корректно подписаться на снапшот-систему и не крутить CPU.
        snapshotFlow { ctrl.uiState }.first { it is ConnectionUiState.Connected }
        val pf = ctrl.openPortForwards()
        pf.addLocal(bindPort = 0, destHost = "10.0.0.5", destPort = 80)
        pf.addRemote(bindPort = 9000, destHost = "localhost", destPort = 3000)
        pf.addDynamic(bindPort = 1080)
    }
    return sessions
}

/** Фейковый SSH-транспорт: shell отдаёт заготовленный баннер+листинг, затем висит до отмены. */
private fun fakeTransport(): SshTransport = object : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = FakeConnection(target)
}

private class FakeConnection(private val target: SshTarget) : SshConnection {
    override val isConnected: Boolean = true
    override val cipher: String? = "chacha20-poly1305@openssh.com"

    // Реалистичный вывод METRICS_COMMAND, чтобы офскрин-рендер info-панели показал живые
    // метрики и факты хоста (CPU/память/диск/аптайм/load/ОС/ядро/CPU), а не плейсхолдеры «…».
    override suspend fun exec(command: String): ExecResult = ExecResult(
        exitCode = 0,
        stdout = """
            cpu  100 0 100 800 0 0 0 0
            cpu  168 0 132 900 0 0 0 0
            @MEM
            Mem:     4000000000  2100000000  1000000000
            @DISK
            /dev/sda1  51475068 42000000 6900000 87% /
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
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel(target)
    override suspend fun openSftp(): SftpClient = FakeSftpClient()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 50080)
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 9000)
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = FakePortForward(if (spec.bindPort != 0) spec.bindPort else 1080)
    override suspend fun disconnect() {}
}

/** Фейковый проброс: сразу «активен», порт echo'ится из spec — для офскрин-рендера таблицы туннелей. */
private class FakePortForward(override val boundPort: Int) : PortForward {
    override val isActive: Boolean = true
    override var isPaused: Boolean = false
        private set
    override val bytesUp: Long = 0
    override val bytesDown: Long = 0
    override suspend fun pause() { isPaused = true }
    override suspend fun resume() { isPaused = false }
    override suspend fun close() = Unit
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
