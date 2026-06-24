package app.skerry.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.skerry.ui.design.optimalWindowSize
import java.awt.GraphicsEnvironment
import app.skerry.shared.host.FileHostStore
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.FileKnownHostsStore
import app.skerry.shared.ssh.ProbeHostKeyVerifier
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.VaultMigration
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.shared.tunnel.FileTunnelStore
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.resolveTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Каталог конфигурации Skerry. По умолчанию `~/.config/skerry`; уважает XDG_CONFIG_HOME.
 */
private fun configDir(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")
    return base.resolve("skerry")
}

/**
 * Стабильный идентификатор устройства для записей vault (provenance + LWW будущего sync).
 * Генерируется один раз и персистится в `device_id`, чтобы переживать перезапуски.
 */
private fun deviceId(dir: Path): String {
    val file = dir.resolve("device_id")
    if (Files.exists(file)) return Files.readString(file).trim()
    Files.createDirectories(dir)
    val id = UUID.randomUUID().toString()
    Files.writeString(file, id)
    return id
}

/**
 * Видимость info-панели терминала, переживающая перезапуск: хранится как один символ в файле
 * `info_panel` (`0`/`1`) рядом с прочей конфигурацией. Отсутствует/нечитаем → дефолт `true`
 * (панель показана, как в макете). Запись best-effort: сбой персиста не должен ронять UI.
 */
private fun readInfoPanel(dir: Path): Boolean {
    val file = dir.resolve("info_panel")
    return runCatching { Files.readString(file).trim() != "0" }.getOrDefault(true)
}

private fun writeInfoPanel(dir: Path, visible: Boolean) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("info_panel"), if (visible) "1" else "0")
    }
}

fun main() {
    // libsodium (ionspin) требует асинхронной инициализации до первого вызова VaultCrypto;
    // на старте desktop делаем это блокирующе, чтобы граф зависимостей строился уже готовым.
    runBlocking { initializeVaultCrypto() }
    application {
        val dir = configDir()
        // TOFU: первый ключ хоста запоминается в known_hosts (с отметкой времени), при смене ключа —
        // отказ + запись события в known_hosts_mismatches, чтобы менеджер known-hosts мог показать
        // предупреждение и дать принять/отклонить новый ключ. Часы штампуют firstSeen/observedAt.
        val knownHostsStore = FileKnownHostsStore(dir.resolve("known_hosts"))
        val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches"))
        val transport = SshjTransport(
            TofuHostKeyVerifier(knownHostsStore, mismatchStore) { Instant.now().toString() },
        )
        // «Test connection» из формы — отдельный транспорт с read-only verifier: проба пускает
        // совпавший доверенный ключ, отвергает смену ключа у известного хоста, а новый хост принимает
        // БЕЗ записи в known_hosts. Постоянное доверие фиксирует только реальный коннект (TOFU выше).
        val probeTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
        val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
        // Менеджер хостов: профили в hosts.json рядом с known_hosts; id — случайный UUID.
        val hostStore = FileHostStore(dir.resolve("hosts.json"))
        val hosts = HostManagerController(hostStore) { UUID.randomUUID().toString() }
        // Локальный зашифрованный vault: гейт мастер-пароля (App → VaultGate) закрывает им весь UI.
        val vault = FileVault(
            dir.resolve("vault.json").toString().toPath(),
            IonspinVaultCrypto(),
            deviceId(dir),
            FileSystem.SYSTEM,
        ) { Instant.now().toString() }
        // Одноуровневая модель vault: keychain-секреты (записи CREDENTIAL). Хост ссылается на секрет
        // напрямую через credentialId.
        val credentials = CredentialManagerController(CredentialStore(vault)) { UUID.randomUUID().toString() }
        // Разовая миграция старых данных (секрет под IDENTITY → CREDENTIAL, хост → прямой credentialId,
        // снос учёток-обёрток) при разблокировке vault; идемпотентна. После неё обновляем кэш хостов.
        val onVaultUnlocked: () -> Unit = {
            // Сбой миграции не должен мешать показать уже доступные данные — гасим и перечитываем хосты.
            runCatching { VaultMigration(vault, hostStore).migrate() }
            hosts.reload()
        }
        // Генерация SSH-ключей в разделе Vault: BouncyCastle поверх sshj-формата (тот же, что читает транспорт).
        val keyGenerator = BouncyCastleSshKeyGenerator()
        // Разбор импортированных SSH-сертификатов (раздел Vault → Certificates) — sshj поверх ssh-wire.
        val certificateInspector = SshjCertificateInspector()
        // Глобальные туннели (модель Termius): сохранённые пробросы в tunnels.json. Активация ходит
        // через ОТДЕЛЬНЫЙ probe-транспорт (read-only verifier): включить можно только уже доверенный
        // хост — туннель открывается без терминала, поэтому тихого TOFU тут быть не должно. Резолв
        // хоста/секрета — через граф (hosts + credentials в открытом vault). Scope живёт всё приложение.
        val tunnelTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
        val tunnelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tunnels = TunnelManager(
            store = FileTunnelStore(dir.resolve("tunnels.json")),
            transport = tunnelTransport,
            resolve = { resolveTunnel(it, findHost = hosts::find, findCredential = credentials::find) },
            scope = tunnelScope,
        ) { UUID.randomUUID().toString() }
        val deps = AppDependencies(transport = transport, hosts = hosts, vault = vault, credentials = credentials, knownHosts = knownHosts, keyGenerator = keyGenerator, certificateInspector = certificateInspector, tunnels = tunnels)
        // Размер окна подбираем под доступную область экрана (без таскбара): ~90% экрана в рамках
        // MIN_WINDOW…MAX_WINDOW, не больше самого экрана. maximumWindowBounds учитывает панели ОС.
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val windowState = rememberWindowState(
            size = optimalWindowSize(DpSize(screen.width.dp, screen.height.dp)),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Skerry",
        ) {
            // Десктопный UI — точная реализация макета docs/new/Skerry.html (визуальный слой).
            // Живой vault + хосты + сессии + known-hosts подключены: chrome закрыт гейтом мастер-пароля,
            // клик по хосту открывает живой SSH-терминал во вкладке (transport+identities из `deps`),
            // менеджер known-hosts работает поверх своих сторов (knownHosts из `deps`).
            app.skerry.ui.theme.SkerryTheme {
                app.skerry.ui.design.DesktopDesignApp(
                    initialInfoPanel = readInfoPanel(dir),
                    onInfoPanelChange = { writeInfoPanel(dir, it) },
                    vault = deps.vault,
                    biometrics = deps.biometrics,
                    hosts = deps.hosts,
                    transport = deps.transport,
                    testTransport = probeTransport,
                    credentials = deps.credentials,
                    knownHosts = deps.knownHosts,
                    keyGenerator = deps.keyGenerator,
                    certificateInspector = deps.certificateInspector,
                    tunnels = deps.tunnels,
                    onVaultUnlocked = onVaultUnlocked,
                )
            }
        }
    }
}
